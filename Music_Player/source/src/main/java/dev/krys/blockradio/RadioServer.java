package dev.krys.blockradio;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.block.Blocks;
import java.util.*;

public class RadioServer implements ModInitializer {
 public static final Identifier CHANNEL=new Identifier("blockradio","sync");
 // First multiplayer version: one active station per server.
 static Station station;
 static int ticks;
 static long sequence;
 static final Map<UUID,Long> lastCommand=new HashMap<>();
 static class Station {
  BlockPos pos; String dimension,id,title; long base,started,revision; boolean paused;
  long position(){return base+(paused?0:(System.nanoTime()-started)/1_000_000);}
 }
 @Override public void onInitialize(){
  ServerPlayNetworking.registerGlobalReceiver(CHANNEL,(server,player,handler,buf,sender)->{
   String action=buf.readString(16); BlockPos pos=buf.readBlockPos();
   String id=buf.readString(40),title=buf.readString(200);long seek=buf.readLong();
   server.execute(()->{
    long now=System.nanoTime();
    if(now-lastCommand.getOrDefault(player.getUuid(),0L)<100_000_000L)return;
    lastCommand.put(player.getUuid(),now);
    if(player.squaredDistanceTo(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5)>64
      ||!player.getServerWorld().getBlockState(pos).isOf(Blocks.JUKEBOX))return;
    String dim=player.getWorld().getRegistryKey().getValue().toString();
    if(action.equals("play")){
     if(!id.matches("[0-9]+(?::[0-9]+)?"))return;
     Station s=new Station();s.pos=pos;s.dimension=dim;s.id=id;s.title=title;s.started=now;s.revision=++sequence;station=s;
    }else{
     Station s=station;if(s==null||!s.pos.equals(pos)||!s.dimension.equals(dim))return;
     switch(action){
      case "stop" -> station=null;
      case "pause" -> {s.base=s.position();s.paused=true;}
      case "resume" -> {if(s.paused){s.started=now;s.paused=false;}}
      case "seek" -> {s.base=Math.max(0,Math.min(900000,seek));s.started=now;}
      default -> {return;}
     }
    }
    for(var p:server.getPlayerManager().getPlayerList())send(p);
   });
  });
  ServerTickEvents.END_SERVER_TICK.register(server->{
   if(++ticks%20!=0)return;
   if(station!=null){
    var world=server.getWorld(net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.WORLD,new Identifier(station.dimension)));
    if(world==null||!world.isChunkLoaded(station.pos)||!world.getBlockState(station.pos).isOf(Blocks.JUKEBOX)||station.position()>900000)station=null;
   }
   for(var p:server.getPlayerManager().getPlayerList())send(p);
  });
  ServerLifecycleEvents.SERVER_STOPPED.register(server->{station=null;lastCommand.clear();ticks=0;});
  ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->lastCommand.remove(handler.player.getUuid()));
 }
 static void send(ServerPlayerEntity p){
  if(!ServerPlayNetworking.canSend(p,CHANNEL))return;
  var b=PacketByteBufs.create();Station s=station;
  boolean hear=s!=null&&p.getWorld().getRegistryKey().getValue().toString().equals(s.dimension)
    &&p.squaredDistanceTo(s.pos.getX()+.5,s.pos.getY()+.5,s.pos.getZ()+.5)<40*40;
  b.writeBoolean(hear);
  if(hear){b.writeBlockPos(s.pos);b.writeString(s.dimension,200);b.writeString(s.id,40);b.writeString(s.title,200);b.writeLong(s.revision);b.writeLong(s.position());b.writeBoolean(s.paused);}
  ServerPlayNetworking.send(p,CHANNEL,b);
 }
}
