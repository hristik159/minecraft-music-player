package dev.krys.blockradio;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import net.fabricmc.loader.api.FabricLoader;

/** One owned helper per game directory; private capability never sent over Minecraft networking. */
public final class BackendManager {
 private static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
 private static final ScheduledExecutorService WORK=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"BlockRadio-helper");t.setDaemon(true);return t;});
 private static Process process;
 private static FileChannel lockFile;
 private static FileLock lock;
 private static String base="",key="";
 public static volatile boolean ready;
 public static volatile String status="Помощник запускается…";
 private static volatile boolean closing;
 private BackendManager(){}
 public static void start(){WORK.execute(BackendManager::launch);}
 private static void launch(){
  try{
   if(!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"))throw new IOException("Автозапуск этой сборки поддерживает Windows x64.");
   Path dir=FabricLoader.getInstance().getGameDir().resolve("config/blockradio");Files.createDirectories(dir);
   lockFile=FileChannel.open(dir.resolve("backend.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);lock=lockFile.tryLock();
   if(lock==null)throw new IOException("Этот игровой каталог уже занят BlockRadio.");
   String resource="/backend/blockradio-backend.exe";
   byte[] bytes;
   try(InputStream in=BackendManager.class.getResourceAsStream(resource)){
    if(in==null)throw new IOException("В JAR нет backend EXE. Установите Windows-пакет из Actions.");bytes=in.readAllBytes();
   }
   String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
   Path exe=dir.resolve("backend-"+hash.substring(0,16)+".exe");
   if(!Files.exists(exe)||!MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(exe)),MessageDigest.getInstance("SHA-256").digest(bytes))){
    Path temp=Files.createTempFile(dir,"backend-",".tmp");Files.write(temp,bytes);Files.move(temp,exe,StandardCopyOption.REPLACE_EXISTING);
   }
   int port;try(ServerSocket socket=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){port=socket.getLocalPort();}
   key=UUID.randomUUID().toString()+UUID.randomUUID();base="http://127.0.0.1:"+port;
   ProcessBuilder pb=new ProcessBuilder(exe.toString(),"--port",String.valueOf(port),"--data-dir",dir.toString());
   pb.environment().put("BLOCKRADIO_SESSION_KEY",key);pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);pb.redirectError(ProcessBuilder.Redirect.DISCARD);
   process=pb.start();
   WORK.scheduleWithFixedDelay(()->{
    if(closing)return;
    try{JsonObject reply=get("/health");ready=true;status="Готово";
     if(reply.has("events"))for(JsonElement event:reply.getAsJsonArray("events")){
      JsonObject e=event.getAsJsonObject();if("ended".equals(e.get("type").getAsString()))BlockRadio.onVideoEnded(e.get("revision").getAsLong());
     }
    }catch(Exception e){ready=false;status=process.isAlive()?"Ожидание помощника…":"Помощник завершился. Перезапустите Minecraft.";}
   },0,2,TimeUnit.SECONDS);
  }catch(Exception e){status=e.getMessage()==null?"Не удалось запустить помощник":e.getMessage();}
 }
 public static HttpRequest.Builder request(String route){return HttpRequest.newBuilder(URI.create(base+route)).header("X-BlockRadio-Key",key).timeout(Duration.ofSeconds(60));}
 public static JsonObject get(String route)throws Exception{return json(HTTP.send(request(route).GET().build(),HttpResponse.BodyHandlers.ofString()));}
 public static JsonObject post(String route,JsonObject body)throws Exception{return json(HTTP.send(request(route).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),HttpResponse.BodyHandlers.ofString()));}
 private static JsonObject json(HttpResponse<String> r)throws IOException{
  JsonObject j=JsonParser.parseString(r.body()).getAsJsonObject();if(r.statusCode()!=200)throw new IOException(j.has("error")?j.get("error").getAsString():"Ошибка помощника");return j;
 }
 public static byte[] audio(String id)throws Exception{
  var response=HTTP.send(request("/audio?id="+URLEncoder.encode(id,java.nio.charset.StandardCharsets.UTF_8)).timeout(Duration.ofSeconds(120)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
  if(response.statusCode()!=200)throw new IOException("Аудио недоступно: проверьте вход и подписку Яндекса.");return response.body();
 }
 public static void close(){
  closing=true;ready=false;WORK.shutdownNow();
  Process p=process;if(p!=null){
   var children=p.descendants().toList();
   try{HTTP.send(request("/shutdown").timeout(Duration.ofSeconds(1)).POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.discarding());}catch(Exception ignored){}
   children.forEach(ProcessHandle::destroy);p.destroy();
   try{if(!p.waitFor(2,TimeUnit.SECONDS))p.destroyForcibly();}catch(InterruptedException e){Thread.currentThread().interrupt();p.destroyForcibly();}
   children.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
  }
  try{if(lock!=null)lock.release();if(lockFile!=null)lockFile.close();}catch(IOException ignored){}
 }
}
