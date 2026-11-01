package dev.krys.blockradio;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.*;
import net.minecraft.util.Identifier;

/** Bounded asynchronous image cache; texture registration/eviction stays on the client thread. */
public final class CoverCache {
 private static final Map<String,Identifier> CACHE=new LinkedHashMap<>(64,.75f,true);
 private static final Set<String> PENDING=ConcurrentHashMap.newKeySet();
 private static final Map<String,Long> FAILED=new ConcurrentHashMap<>();
 private static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
 public static Identifier get(String url){
  if(url==null||url.isEmpty())return null;
  Identifier cached=CACHE.get(url);if(cached!=null)return cached;
  if(PENDING.size()>=8||FAILED.getOrDefault(url,0L)>System.currentTimeMillis()||!PENDING.add(url))return null;
  BlockRadio.background("cover",()->{try{
   URI uri=URI.create(url);String h=uri.getHost();if(!"https".equals(uri.getScheme())||h==null||!(h.equals("i.ytimg.com")||h.endsWith(".googleusercontent.com")||h.equals("avatars.yandex.net")||h.equals("avatars.mds.yandex.net")))throw new Exception();
   var r=HTTP.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
   byte[] bytes;try(var input=r.body()){if(r.statusCode()!=200)throw new Exception();bytes=input.readNBytes(1024*1024+1);if(bytes.length>1024*1024)throw new Exception();}
   NativeImage img=NativeImage.read(new java.io.ByteArrayInputStream(bytes));if(img.getWidth()>2048||img.getHeight()>2048){img.close();throw new Exception();}
   MinecraftClient.getInstance().execute(()->{try{
    var tm=MinecraftClient.getInstance().getTextureManager();Identifier id=tm.registerDynamicTexture("blockradio_cover",new NativeImageBackedTexture(img));CACHE.put(url,id);
    while(CACHE.size()>128){var it=CACHE.entrySet().iterator();var old=it.next();it.remove();tm.destroyTexture(old.getValue());}
   }finally{PENDING.remove(url);}});
  }catch(Exception e){FAILED.put(url,System.currentTimeMillis()+60000);if(FAILED.size()>256)FAILED.clear();PENDING.remove(url);}});return null;
 }
}
