package dev.krys.blockradio;

import com.google.gson.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.minecraft.util.Identifier;
import net.minecraft.server.network.ServerPlayerEntity;
import java.util.*;

/** Server owns queue and playback clock; credentials and audio never cross this channel. */
public class RadioServer implements ModInitializer {
 public static final Identifier CHANNEL=new Identifier("blockradio","sync_v3");
 static Track current;
 static final ArrayDeque<Track> queue=new ArrayDeque<>(),history=new ArrayDeque<>();
 static long base,started,revision;
 static boolean paused,autoplay=true,requesting;
 static UUID controller,sourcePlayerUUID;
 static int ticks;
 static final Map<UUID,Long> lastCommand=new HashMap<>();
 static long position(){return base+(paused?0:(System.nanoTime()-started)/1000000);}
 static void play(Track track,boolean remember){
  if(remember&&current!=null){history.addLast(current);while(history.size()>50)history.removeFirst();}
  current=track;base=0;started=System.nanoTime();paused=false;requesting=false;revision++;
 }
 static void next(){
  if(!queue.isEmpty())play(queue.removeFirst(),true);
  else if(autoplay&&current!=null){base=position();requesting=true;paused=true;}
  else {paused=true;base=0;current=null;revision++;}
 }
 @Override public void onInitialize(){
  ServerPlayNetworking.registerGlobalReceiver(CHANNEL,(server,player,handler,buf,sender)->{
   String raw=buf.readString(32767);
   server.execute(()->{try{
    JsonObject j=JsonParser.parseString(raw).getAsJsonObject();String action=Track.s(j,"action");
    long now=System.nanoTime();boolean automatic=Set.of("ended","recommend","unavailable").contains(action);
    if(!automatic){if(now-lastCommand.getOrDefault(player.getUuid(),0L)<80000000)return;lastCommand.put(player.getUuid(),now);}
    switch(action){
     case "play" -> {play(Track.parse(j.getAsJsonObject("track")),true);controller=player.getUuid();sourcePlayerUUID=player.getUuid();}
     case "enqueue" -> {if(queue.size()<100)queue.addLast(Track.parse(j.getAsJsonObject("track")));}
     case "pause" -> {if(current!=null&&!paused){base=position();paused=true;}}
     case "resume" -> {if(current!=null&&paused){started=now;paused=false;}}
     case "seek" -> {if(current!=null){base=Math.max(0,Math.min(current.duration()>0?current.duration():86400000,j.get("position").getAsLong()));started=now;}}
     case "next" -> {controller=player.getUuid();if(!requesting)next();}
     case "previous" -> {if(!history.isEmpty()){if(current!=null&&queue.size()<100)queue.addFirst(current);play(history.removeLast(),false);}}
     case "autoplay" -> {autoplay=j.get("enabled").getAsBoolean();if(!autoplay&&requesting){requesting=false;revision++;}}
     case "remove" -> {int index=j.get("index").getAsInt();if(index>=0&&index<queue.size()){var list=new ArrayList<>(queue);list.remove(index);queue.clear();queue.addAll(list);}}
     case "clear" -> queue.clear();
     case "stop" -> {current=null;requesting=false;revision++;}
     case "ended" -> {if(player.getUuid().equals(controller)&&current!=null&&!requesting&&j.get("revision").getAsLong()==revision){if(autoplay)next();else{base=position();paused=true;}}}
     case "recommend" -> {if(requesting&&player.getUuid().equals(controller)&&j.get("revision").getAsLong()==revision)play(Track.parse(j.getAsJsonObject("track")),true);}
     case "unavailable" -> {if(requesting&&player.getUuid().equals(controller)&&j.get("revision").getAsLong()==revision){current=null;requesting=false;revision++;}}
     default -> {return;}
    }
    for(var p:server.getPlayerManager().getPlayerList())send(p);
   }catch(RuntimeException ignored){/* Reject malformed packets without crashing the server. */}});
  });
  ServerTickEvents.END_SERVER_TICK.register(server->{if(++ticks%20!=0)return;
   if(controller==null||server.getPlayerManager().getPlayer(controller)==null){controller=null;for(var p:server.getPlayerManager().getPlayerList())if(ServerPlayNetworking.canSend(p,CHANNEL)){controller=p.getUuid();break;}}
   // End detection is supplied by the actual local player (YouTube may buffer/show ads).
   for(var p:server.getPlayerManager().getPlayerList())send(p);
  });
  ServerLifecycleEvents.SERVER_STOPPED.register(server->{current=null;queue.clear();history.clear();controller=null;sourcePlayerUUID=null;lastCommand.clear();ticks=0;autoplay=true;paused=false;requesting=false;revision=0;});
  ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->lastCommand.remove(handler.player.getUuid()));
 }
 static void send(ServerPlayerEntity p){
  if(!ServerPlayNetworking.canSend(p,CHANNEL))return;
  JsonObject j=new JsonObject();j.addProperty("sourcePlayerUUID",sourcePlayerUUID==null?"":sourcePlayerUUID.toString());j.addProperty("active",current!=null);j.addProperty("revision",revision);j.addProperty("position",current==null?0:position());j.addProperty("paused",paused);j.addProperty("autoplay",autoplay);j.addProperty("recommend",requesting&&p.getUuid().equals(controller));j.addProperty("controller",p.getUuid().equals(controller));
  if(current!=null)j.add("track",current.json());JsonArray q=new JsonArray();queue.forEach(t->q.add(t.json()));j.add("queue",q);
  var b=PacketByteBufs.create();b.writeString(j.toString(),262144);ServerPlayNetworking.send(p,CHANNEL,b);
 }
}
