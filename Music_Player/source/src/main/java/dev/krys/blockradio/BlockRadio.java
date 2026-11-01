package dev.krys.blockradio;

import net.fabricmc.api.ClientModInitializer;
import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.io.ByteArrayInputStream;
import javax.sound.sampled.*;

public class BlockRadio implements ClientModInitializer {
 static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
 static final String BASE="http://127.0.0.1:18743";
 static volatile Clip clip;
 static volatile double volume=.6;
 static volatile String playing="";
 static volatile long generation;
 static net.minecraft.util.math.BlockPos source;
 static String trackId="",sourceDimension="";
 static volatile long targetMs,receivedAt;
 static volatile boolean paused;
 static volatile double attenuation;
 static volatile boolean loading;
 static int retry;
 static String currentTitle="";
 static long revision;
 static java.util.function.Consumer<String> feedback=message->{};
 static void command(net.minecraft.util.math.BlockPos pos,String action,String id,String title,long seek){
  if(!net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(RadioServer.CHANNEL)){
   feedback.accept("На сервере нужен BlockRadio 0.2.0.");return;
  }
  var b=net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create();
  b.writeString(action,16);b.writeBlockPos(pos);b.writeString(id,40);
  b.writeString(title.length()>200?title.substring(0,200):title,200);b.writeLong(seek);
  net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(RadioServer.CHANNEL,b);
 }
 static long target(){return targetMs+(paused?0:(System.nanoTime()-receivedAt)/1_000_000);}
 static synchronized void syncClip(){
  if(clip==null)return;
  long us=Math.min(clip.getMicrosecondLength(),Math.max(0,target()*1000));
  if(Math.abs(clip.getMicrosecondPosition()-us)>750000)clip.setMicrosecondPosition(us);
  if(paused||attenuation<=0||volume<=0)clip.stop();else if(us<clip.getMicrosecondLength()&&!clip.isRunning())clip.start();
 }

 @Override public void onInitializeClient() {
  Runtime.getRuntime().addShutdownHook(new Thread(BlockRadio::stop));
  net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(RadioServer.CHANNEL,(mc,handler,b,sender)->{
   boolean active=b.readBoolean();
   if(!active){mc.execute(()->{stop();trackId="";source=null;playing="";});return;}
   var pos=b.readBlockPos();String dimension=b.readString(200);String id=b.readString(40),title=b.readString(200);
   long rev=b.readLong();long time=b.readLong();boolean pause=b.readBoolean();
   mc.execute(()->{
    targetMs=time;receivedAt=System.nanoTime();paused=pause;
    boolean changed=rev!=revision||!id.equals(trackId)||!pos.equals(source);revision=rev;
    source=pos;sourceDimension=dimension;currentTitle=title;
    if(changed){trackId=id;attenuation=0;load(id,title,message->feedback.accept(message));}
    else syncClip();
   });
  });
  net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler,mc)->{stop();source=null;trackId="";playing="";});
  net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(mc->{
   if(source==null||mc.player==null||mc.world==null){attenuation=0;setVolume(volume);return;}
   double distance=Math.sqrt(mc.player.squaredDistanceTo(source.getX()+.5,source.getY()+.5,source.getZ()+.5));
   attenuation=RadioMath.attenuation(distance);
   attenuation*=mc.options.getSoundVolume(net.minecraft.sound.SoundCategory.MASTER)*mc.options.getSoundVolume(net.minecraft.sound.SoundCategory.RECORDS);
   if(!mc.world.getRegistryKey().getValue().toString().equals(sourceDimension)||!mc.world.getBlockState(source).isOf(net.minecraft.block.Blocks.JUKEBOX))attenuation=0;
   setVolume(volume);syncClip();
   if(clip==null&&!loading&&++retry>=200){retry=0;load(trackId,currentTitle,message->feedback.accept(message));}
  });
 }
 static void background(String name,Runnable task) {Thread t=new Thread(task,name);t.setDaemon(true);t.start();}
 static JsonObject get(String route) throws Exception {
  var req=HttpRequest.newBuilder(URI.create(BASE+route)).header("X-BlockRadio","1").timeout(Duration.ofSeconds(35)).GET().build();
  var res=HTTP.send(req,HttpResponse.BodyHandlers.ofString());
  var json=JsonParser.parseString(res.body()).getAsJsonObject();
  if(res.statusCode()!=200) throw new Exception(json.has("error")?json.get("error").getAsString():"HTTP "+res.statusCode());
  return json;
 }
 static synchronized void stop() { generation++; loading=false; if(clip!=null){clip.stop();clip.close();clip=null;} }
 static void load(String id,String title,java.util.function.Consumer<String> status) {
  stop();loading=true;retry=0; final long job=generation;
  background("BlockRadio-audio",()->{
   try {
    status.accept("Загрузка аудио…");
    var req=HttpRequest.newBuilder(URI.create(BASE+"/audio?id="+id)).header("X-BlockRadio","1").timeout(Duration.ofSeconds(120)).GET().build();
    var res=HTTP.send(req,HttpResponse.BodyHandlers.ofByteArray());
    if(res.statusCode()!=200) throw new Exception(JsonParser.parseString(new String(res.body(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject().get("error").getAsString());
    try(var stream=AudioSystem.getAudioInputStream(new ByteArrayInputStream(res.body()))) {
     synchronized(BlockRadio.class) {
      if(job!=generation)return;
      Clip next=AudioSystem.getClip();
      try { next.open(stream); if(!next.isControlSupported(FloatControl.Type.MASTER_GAIN))throw new Exception("Звуковое устройство не поддерживает регулировку громкости."); } catch(Exception e) {next.close();throw e;}
      clip=next; playing=title; setVolume(volume);syncClip();
     }
    }
    status.accept("Общий трек. Радиус слышимости: 32 блока.");
   } catch(Exception e){if(job==generation){
     String message="BlockRadio: "+e.getMessage();status.accept(message);
     var mc=net.minecraft.client.MinecraftClient.getInstance();
     mc.execute(()->{if(mc.player!=null)mc.player.sendMessage(net.minecraft.text.Text.literal(message),true);});
    }} finally {if(job==generation)loading=false;}
  });
 }
 static synchronized void setVolume(double value) {
  volume=value;Clip c=clip;
  if(c!=null && c.isOpen() && c.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
   if(c.isControlSupported(BooleanControl.Type.MUTE))
    ((BooleanControl)c.getControl(BooleanControl.Type.MUTE)).setValue(value*attenuation<=0);
   FloatControl gain=(FloatControl)c.getControl(FloatControl.Type.MASTER_GAIN);
   gain.setValue((float)Math.max(gain.getMinimum(),Math.min(gain.getMaximum(),20*Math.log10(Math.max(.0001,value*attenuation)))));
  }
 }
}
