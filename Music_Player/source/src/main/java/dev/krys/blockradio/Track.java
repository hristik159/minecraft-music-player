package dev.krys.blockradio;
import com.google.gson.*;
public record Track(String provider,String id,String title,String artist,String cover,long duration) {
 public static Track parse(JsonObject j){
  String provider=s(j,"provider"),id=s(j,"id");
  if(!(provider.equals("yandex")&&id.matches("[0-9]+(?::[0-9]+)?")||provider.equals("youtube")&&id.matches("[A-Za-z0-9_-]{11}")))throw new IllegalArgumentException("Invalid track");
  long duration=j.has("duration_ms")?j.get("duration_ms").getAsLong():0;
  return new Track(provider,id,limit(s(j,"title"),200),limit(s(j,"artist"),200),limit(s(j,"cover"),1000),Math.max(0,Math.min(86400000,duration)));
 }
 static String s(JsonObject j,String key){return j.has(key)&&!j.get(key).isJsonNull()?j.get(key).getAsString():"";}
 static String limit(String s,int n){return s.substring(0,Math.min(n,s.length()));}
 public JsonObject json(){JsonObject j=new JsonObject();j.addProperty("provider",provider);j.addProperty("id",id);j.addProperty("title",title);j.addProperty("artist",artist);j.addProperty("cover",cover);j.addProperty("duration_ms",duration);return j;}
}
