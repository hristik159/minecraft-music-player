package dev.krys.blockradio;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.math.BlockPos;
import com.google.gson.*;
import java.io.ByteArrayInputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import javax.sound.sampled.*;

public class BlockRadio implements ClientModInitializer {
 static final ExecutorService IO=Executors.newFixedThreadPool(4,r->{Thread t=new Thread(r,"BlockRadio-IO");t.setDaemon(true);return t;});
 static volatile Clip clip;
 static volatile double volume=.6;
 static volatile Track current;
 static volatile String status="",playing="";
 static volatile long generation,revision=-1,targetMs,receivedAt,endedRevision=-1,recommendRevision=-1;
 static volatile boolean paused,autoplay=true,controller,loading,videoUpdate;
 static volatile List<Track> queue=List.of();
 static volatile List<Track> candidates=List.of();
 static volatile JsonObject accounts=new JsonObject();
 static int ticks;
 static KeyBinding miniKey,openKey;
 static boolean hudVisible=true;
 static UUID sourcePlayerUUID;
 static double distanceVolume=0;
 static java.util.function.Consumer<String> feedback=m->status=m;
 public static void background(String name,Runnable task){IO.execute(task);}
 static long target(){return targetMs+(paused?0:(System.nanoTime()-receivedAt)/1000000);}
 static JsonObject get(String route)throws Exception{return BackendManager.get(route);}
 static void command(String action,Track track){
  var mc=MinecraftClient.getInstance();if(mc.getNetworkHandler()==null||!ClientPlayNetworking.canSend(RadioServer.CHANNEL)){status="У всех игроков и на сервере нужен BlockRadio 0.3.";return;}
  JsonObject j=new JsonObject();j.addProperty("action",action);j.addProperty("revision",revision);if(track!=null)j.add("track",track.json());send(j);
 }
 static void send(JsonObject j){var mc=MinecraftClient.getInstance();mc.execute(()->{if(mc.getNetworkHandler()!=null&&ClientPlayNetworking.canSend(RadioServer.CHANNEL)){var b=net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create();b.writeString(j.toString(),32767);ClientPlayNetworking.send(RadioServer.CHANNEL,b);}});}
 static void seek(long position){endedRevision=-1;JsonObject j=new JsonObject();j.addProperty("action","seek");j.addProperty("position",position);send(j);}
 static void toggleAutoplay(){JsonObject j=new JsonObject();j.addProperty("action","autoplay");j.addProperty("enabled",!autoplay);send(j);}
 static void action(String route,String provider){background("action",()->{try{JsonObject j=new JsonObject();j.addProperty("provider",provider);BackendManager.post(route,j);refreshAccounts();}catch(Exception e){status=e.getMessage();}});}
 static void refreshAccounts(){try{accounts=get("/accounts");}catch(Exception e){status=e.getMessage();}}
 @Override public void onInitializeClient(){
  BackendManager.start();Runtime.getRuntime().addShutdownHook(new Thread(()->{stop();BackendManager.close();}));
  ClientLifecycleEvents.CLIENT_STOPPING.register(mc->{stop();BackendManager.close();IO.shutdownNow();});
  net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register((d,delta)->{var mc=MinecraftClient.getInstance();if(hudVisible&&mc.currentScreen==null&&!mc.options.hudHidden&&current!=null)RadioScreen.drawHud(d,mc);});
  openKey=KeyBindingHelper.registerKeyBinding(new KeyBinding("Открыть BlockRadio",InputUtil.Type.KEYSYM,66,"category.blockradio"));
  miniKey=KeyBindingHelper.registerKeyBinding(new KeyBinding("key.blockradio.mini",InputUtil.Type.KEYSYM,77,"category.blockradio"));
  ClientPlayNetworking.registerGlobalReceiver(RadioServer.CHANNEL,(mc,handler,buf,sender)->{String raw=buf.readString(262144);mc.execute(()->receive(JsonParser.parseString(raw).getAsJsonObject()));});
  ClientPlayConnectionEvents.DISCONNECT.register((handler,mc)->{stop();current=null;queue=List.of();pushVideo();});
  ClientTickEvents.END_CLIENT_TICK.register(mc->{
   while(miniKey.wasPressed())hudVisible=!hudVisible;
   while(openKey.wasPressed())mc.setScreen(new RadioScreen(null));
   updateDistance();
   syncClip();
   if(++ticks%40==0&&BackendManager.ready){background("accounts",BlockRadio::refreshAccounts);pushVideo();}
   if(current!=null&&current.provider().equals("yandex")&&clip==null&&!loading&&ticks%200==0&&BackendManager.ready)load(current);
   if(controller&&current!=null&&!paused&&clip!=null&&clip.getMicrosecondLength()>0&&clip.getMicrosecondPosition()>=clip.getMicrosecondLength()-10000&&endedRevision!=revision){endedRevision=revision;command("ended",null);}
  });
 }
 static void receive(JsonObject j){
  String source=Track.s(j,"sourcePlayerUUID");sourcePlayerUUID=source.isEmpty()?null:UUID.fromString(source);
  long rev=j.get("revision").getAsLong();targetMs=j.get("position").getAsLong();receivedAt=System.nanoTime();paused=j.get("paused").getAsBoolean();autoplay=j.get("autoplay").getAsBoolean();controller=j.get("controller").getAsBoolean();
  ArrayList<Track> nextQueue=new ArrayList<>();for(var x:j.getAsJsonArray("queue"))nextQueue.add(Track.parse(x.getAsJsonObject()));queue=List.copyOf(nextQueue);
  if(!j.get("active").getAsBoolean()){stop();current=null;revision=rev;pushVideo();return;}
  Track t=Track.parse(j.getAsJsonObject("track"));boolean changed=rev!=revision||current==null||!t.id().equals(current.id())||!t.provider().equals(current.provider());revision=rev;current=t;playing=t.title();
  if(changed){stop();endedRevision=-1;if(t.provider().equals("yandex"))load(t);else status="YouTube: откройте видимый видеоплеер кнопкой Видео.";}
  syncClip();pushVideo();
  if(j.get("recommend").getAsBoolean()&&recommendRevision!=rev){recommendRevision=rev;recommend(t,rev);}
 }
 static void recommend(Track previous,long rev){background("autoplay",()->{
  try{
   Track next=null;
   if(previous.provider().equals("yandex")){var list=get("/wave").getAsJsonArray("tracks");if(!list.isEmpty())next=Track.parse(list.get(0).getAsJsonObject());}
   else{for(Track candidate:candidates)if(candidate.provider().equals("youtube")&&!candidate.id().equals(previous.id())){next=candidate;break;}}
   if(revision!=rev)return;
   if(next==null){status="Нет следующего трека. Добавьте видео в очередь или выполните поиск.";command("unavailable",null);}else{
    Track selected=next;candidates=candidates.stream().filter(x->!x.id().equals(selected.id())).toList();command("recommend",selected);
   }
  }catch(Exception e){status="Autoplay: источник недоступен.";if(revision==rev)command("unavailable",null);}
 });}
 public static void onVideoEnded(long rev){MinecraftClient.getInstance().execute(()->{if(current!=null&&current.provider().equals("youtube")&&controller&&rev==revision&&endedRevision!=rev){endedRevision=rev;command("ended",null);}});}
 static void pushVideo(){if(!BackendManager.ready||videoUpdate)return;videoUpdate=true;
  JsonObject j=new JsonObject();Track t=current;j.addProperty("active",t!=null);if(t!=null){j.addProperty("provider",t.provider());j.addProperty("id",t.id());j.addProperty("title",t.title());j.addProperty("duration_ms",t.duration());}j.addProperty("revision",revision);j.addProperty("position",target());j.addProperty("paused",paused);j.addProperty("volume",volume*distanceVolume);
  background("video",()->{try{BackendManager.post("/player/state",j);}catch(Exception ignored){}finally{videoUpdate=false;}});
 }
 static synchronized void stop(){generation++;loading=false;if(clip!=null){clip.stop();clip.close();clip=null;}}
 static void load(Track t){stop();loading=true;long job=generation;background("audio",()->{
  try{byte[] bytes=BackendManager.audio(t.id());try(var stream=AudioSystem.getAudioInputStream(new ByteArrayInputStream(bytes))){synchronized(BlockRadio.class){if(job!=generation)return;Clip next=AudioSystem.getClip();try{next.open(stream);}catch(Exception e){next.close();throw e;}clip=next;setVolume(volume);syncClip();}}status="";}
  catch(Exception e){if(job==generation)status=e.getMessage();}finally{if(job==generation)loading=false;}
 });}
 static synchronized void syncClip(){if(clip==null)return;long us=Math.min(clip.getMicrosecondLength(),Math.max(0,target()*1000));if(Math.abs(clip.getMicrosecondPosition()-us)>900000)clip.setMicrosecondPosition(us);if(paused)clip.stop();else if(us<clip.getMicrosecondLength()&&!clip.isRunning())clip.start();}
 static void removeQueue(int index){JsonObject j=new JsonObject();j.addProperty("action","remove");j.addProperty("index",index);send(j);}
 static void updateDistance(){
  var mc=MinecraftClient.getInstance();double desired=0;
  if(mc.player!=null&&sourcePlayerUUID!=null){if(sourcePlayerUUID.equals(mc.player.getUuid()))desired=1;else if(mc.world!=null){var source=mc.world.getPlayerByUuid(sourcePlayerUUID);if(source!=null)desired=RadioMath.attenuation(mc.player.distanceTo(source));}}
  distanceVolume+=.25*(desired-distanceVolume);if(Math.abs(distanceVolume-desired)<.0001)distanceVolume=desired;
  applyVolume();if(ticks%5==0)pushVideo();
 }
 static synchronized void setVolume(double value){volume=Math.max(0,Math.min(1,value));applyVolume();pushVideo();}
 static synchronized void applyVolume(){double effective=volume*distanceVolume;if(clip!=null&&clip.isOpen()){
  if(clip.isControlSupported(BooleanControl.Type.MUTE))((BooleanControl)clip.getControl(BooleanControl.Type.MUTE)).setValue(effective==0);
  if(clip.isControlSupported(FloatControl.Type.MASTER_GAIN)){FloatControl gain=(FloatControl)clip.getControl(FloatControl.Type.MASTER_GAIN);gain.setValue((float)Math.max(gain.getMinimum(),Math.min(gain.getMaximum(),20*Math.log10(Math.max(.0001,effective)))));}
 } }
}
