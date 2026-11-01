package dev.krys.blockradio;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import com.google.gson.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Small player with an optional source/navigation drawer and shared content area. */
public class RadioScreen extends Screen {
 private int left,top,w=280,h=112,offset,dragging,tab,source,requestId,page;
 private boolean drawer,busy;
 private long opened;
 private TextFieldWidget query;
 private String search="",message="",youtubePage="";
 private java.util.List<Track> tracks=java.util.List.of();
 private final String[] tabs={"Поиск","Моя музыка","Моя волна","Очередь","Аккаунты"};
 public RadioScreen(BlockPos ignored){super(Text.literal("BlockRadio"));}
 public RadioScreen(BlockPos ignored,boolean mini){this(ignored);}
 private void button(String label,int x,int y,int width,Runnable click){addDrawableChild(ButtonWidget.builder(Text.literal(label),b->click.run()).dimensions(x,y,width,18).build());}
 @Override protected void init(){
  w=Math.min(280,width-12);left=(width-w)/2;top=drawer?Math.max(6,(height-310)/2):(height-h)/2;
  if(drawer)left=Math.min(width-w-6,Math.max(130,left));
  button(drawer?"×":"☰",left+w-27,top+5,22,()->{drawer=!drawer;opened=System.nanoTime();initScreen();});
  button("|<",left+8,top+46,30,()->BlockRadio.command("previous",null));
  button(BlockRadio.paused?">":"||",left+42,top+46,34,()->BlockRadio.command(BlockRadio.paused?"resume":"pause",null));
  button(">|",left+80,top+46,30,()->BlockRadio.command("next",null));
  button(BlockRadio.autoplay?"Auto ON":"Auto OFF",left+116,top+46,65,BlockRadio::toggleAutoplay);
  button("Видео",left+185,top+46,48,()->BlockRadio.action("/player/open","youtube"));
  if(!drawer)return;
  int x=left-124;button((source==0?"● ":"")+"Яндекс",x+6,top+23,112,()->selectSource(0));button((source==1?"● ":"")+"YouTube",x+6,top+45,112,()->selectSource(1));
  for(int i=0;i<5;i++){if(i==2&&source==1)continue;final int n=i;String label=i==1&&source==1?"Понравившиеся":tabs[i];button((tab==i?"› ":"")+label,x+6,top+77+i*23,112,()->{tab=n;tracks=java.util.List.of();offset=0;requestId++;busy=false;initScreen();if(tab==1||tab==2)fetch();});}
  int cy=top+h+7;
  if(tab==0){query=new TextFieldWidget(textRenderer,left+7,cy,w-94,18,Text.literal("Поиск музыки"));query.setMaxLength(160);query.setText(search);query.setSuggestion(search.isEmpty()?"Поиск музыки…":null);addDrawableChild(query);button("Найти",left+w-82,cy,46,this::fetch);button("+",left+w-32,cy,25,()->fetch(true));}
  if(tab==1)button("Ещё",left+w-59,cy,52,()->fetch(true));
  if(tab==2)button("Следующий трек волны",left+7,cy,w-14,this::fetch);
  if(tab==3)button("Очистить",left+w-75,cy,68,()->BlockRadio.command("clear",null));
  if(tab==4)for(int i=0;i<2;i++){String provider=i==0?"yandex":"youtube";JsonObject a=account(provider);boolean ready=Track.s(a,"state").equals("ready");button(ready?"Выйти":"Войти",left+w-65,cy+14+i*66,58,()->BlockRadio.action(ready?"/logout":"/login",provider));}
 }
 private void selectSource(int value){source=value;if(source==1&&tab==2)tab=0;tracks=java.util.List.of();offset=0;requestId++;busy=false;initScreen();if(tab==1)fetch();}
 private void initScreen(){if(query!=null)search=query.getText();query=null;clearChildren();init();}
 private JsonObject account(String provider){return BlockRadio.accounts.has(provider)?BlockRadio.accounts.getAsJsonObject(provider):new JsonObject();}
 private void fetch(){fetch(false);}
 private void fetch(boolean append){if(busy)return;if(!BackendManager.ready){message=BackendManager.status;return;}if(query!=null)search=query.getText().trim();if(tab==0&&search.isEmpty())return;
  if(!append){page=0;youtubePage="";}else page++;
  String route=tab==2?"/wave":tab==1?"/liked?source="+(source==0?"yandex":"youtube"):"/search?source="+(source==0?"yandex":"youtube")+"&q="+URLEncoder.encode(search,StandardCharsets.UTF_8);
  if(tab!=2)route+="&page="+page+"&youtube_page="+URLEncoder.encode(youtubePage,StandardCharsets.UTF_8);
  final String requestRoute=route;
  busy=true;message="Загрузка…";int job=++requestId;
  BlockRadio.background("search",()->{try{JsonObject result=BlockRadio.get(requestRoute);ArrayList<Track> list=new ArrayList<>(append?tracks:java.util.List.of());for(var item:result.getAsJsonArray("tracks"))list.add(Track.parse(item.getAsJsonObject()));client.execute(()->{if(job!=requestId)return;tracks=list.stream().distinct().toList();BlockRadio.candidates=tracks;if(!append)offset=0;if(result.has("youtube_next"))youtubePage=result.get("youtube_next").getAsString();message=result.has("message")?result.get("message").getAsString():"";busy=false;});}catch(Exception e){client.execute(()->{if(job==requestId){busy=false;message=e.getMessage();}});}});
 }
 private int listTop(){return top+h+32;}
 private int rows(){return Math.max(1,Math.min(4,(height-12-listTop())/34));}
 private java.util.List<Track> visible(){return tab==3?BlockRadio.queue:tracks;}
 static void cover(DrawContext d,String url,int x,int y,int size){d.fill(x,y,x+size,y+size,0xff2b3543);var id=CoverCache.get(url);if(id!=null)d.drawTexture(id,x,y,0,0,size,size,size,size);}
 private void text(DrawContext d,String s,int x,int y,int width,int color){d.drawTextWithShadow(textRenderer,textRenderer.trimToWidth(s,width),x,y,color);}
 static void player(DrawContext d,MinecraftClient mc,int x,int y,int width,boolean hud){
  var tr=mc.textRenderer;Track t=BlockRadio.current;d.fill(x,y,x+width,y+(hud?77:112),0xee171d26);d.fill(x,y,x+width,y+1,0xff70d9b4);
  cover(d,t==null?"":t.cover(),x+7,y+7,30);
  d.drawTextWithShadow(tr,tr.trimToWidth(t==null?"BlockRadio":t.title(),width-73),x+44,y+8,0xfff3f5f7);
  d.drawTextWithShadow(tr,tr.trimToWidth(t==null?"Выберите музыку":t.artist(),width-54),x+44,y+22,0xffaab6c8);
  long pos=t==null?0:Math.max(0,BlockRadio.target()),dur=t==null?0:t.duration();int sy=y+(hud?57:73);
  d.fill(x+8,sy,x+width-8,sy+3,0xff3c485d);d.fill(x+8,sy,x+8+(int)((width-16)*Math.min(1,dur>0?(double)pos/dur:0)),sy+3,0xff70d9b4);
  d.drawTextWithShadow(tr,format(pos)+" / "+format(dur),x+8,sy+8,0xffaab6c8);
  d.drawTextWithShadow(tr,(int)(BlockRadio.volume*100)+"%",x+width-34,sy+8,0xff70d9b4);
  if(hud)d.drawTextWithShadow(tr,"|<   "+(BlockRadio.paused?">":"||")+"   >|     B: плеер",x+8,y+42,0xffaab6c8);
 }
 static void drawHud(DrawContext d,MinecraftClient mc){player(d,mc,8,8,210,true);}
 @Override public void render(DrawContext d,int mx,int my,float delta){
  player(d,client,left,top,w,false);
  if(drawer){int slide=(int)(124*Math.min(1,(System.nanoTime()-opened)/180000000.0));d.fill(left-slide,top,left-3,top+204,0xf21a202a);text(d,"Музыка",left-117,top+8,112,0xff70d9b4);
   d.fill(left,top+h+2,left+w,listTop()+rows()*34+5,0xf21a202a);
   if(tab==4){for(int i=0;i<2;i++){String provider=i==0?"yandex":"youtube";JsonObject a=account(provider);int y=top+h+15+i*66;cover(d,Track.s(a,"avatar"),left+8,y,24);text(d,i==0?"Яндекс Музыка":"YouTube",left+39,y,w-110,0xffffffff);text(d,Track.s(a,"name").isEmpty()?"Не подключён":Track.s(a,"name"),left+39,y+14,w-110,0xff70d9b4);text(d,Track.s(a,"message"),left+8,y+32,w-16,0xffdcb98b);}}
   else{if(tab==3)text(d,"Далее в очереди",left+8,top+h+12,w-90,0xffaab6c8);else if(tab==1)text(d,source==0?"Моя музыка":"Понравившиеся",left+8,top+h+12,w-70,0xffaab6c8);
    var list=visible();for(int row=0;row<rows()&&row+offset<list.size();row++){Track t=list.get(row+offset);int y=listTop()+row*34;cover(d,t.cover(),left+7,y,27);text(d,t.title(),left+41,y+2,w-96,0xffffffff);text(d,t.artist(),left+41,y+15,w-96,0xffaab6c8);text(d,">",left+w-43,y+9,14,0xffffffff);text(d,tab==3?"×":"+",left+w-21,y+9,14,0xff70d9b4);}}
  }
  int vx=left+w-111;d.fill(vx,top+83,left+w-42,top+87,0xff3c485d);d.fill(vx,top+83,vx+(int)(69*BlockRadio.volume),top+87,0xff70d9b4);
  text(d,!BackendManager.ready?BackendManager.status:message.isEmpty()?BlockRadio.status:message,left+8,top+99,w-16,0xffdcb98b);
  super.render(d,mx,my,delta);
 }
 static String format(long ms){long s=ms/1000;return s/60+":"+String.format("%02d",s%60);}
 @Override public boolean mouseClicked(double x,double y,int button){if(super.mouseClicked(x,y,button))return true;if(button!=0)return false;
  if(y>=top+69&&y<=top+80&&x>=left+8&&x<=left+w-8){dragging=1;slider(x);return true;}
  if(y>=top+81&&y<=top+94&&x>=left+w-111&&x<=left+w-42){dragging=2;slider(x);return true;}
  if(drawer&&tab!=4&&y>=listTop()&&y<listTop()+rows()*34&&x>=left+7&&x<left+w-7){int i=offset+(int)(y-listTop())/34;var list=visible();if(i<list.size()){if(x>left+w-28){if(tab==3)BlockRadio.removeQueue(i);else BlockRadio.command("enqueue",list.get(i));}else BlockRadio.command("play",list.get(i));return true;}}return false;
 }
 private void slider(double x){if(dragging==1&&BlockRadio.current!=null)BlockRadio.seek((long)(BlockRadio.current.duration()*Math.max(0,Math.min(1,(x-left-8)/(w-16)))));if(dragging==2)BlockRadio.setVolume((x-(left+w-111))/69);}
 @Override public boolean mouseDragged(double x,double y,int b,double dx,double dy){if(dragging!=0){slider(x);return true;}return super.mouseDragged(x,y,b,dx,dy);}
 @Override public boolean mouseReleased(double x,double y,int b){dragging=0;return super.mouseReleased(x,y,b);}
 @Override public boolean mouseScrolled(double x,double y,double amount){if(drawer)offset=Math.max(0,Math.min(Math.max(0,visible().size()-rows()),offset-(int)Math.signum(amount)));return true;}
 @Override public boolean keyPressed(int key,int scan,int mods){if(key==257&&tab==0&&drawer){fetch();return true;}return super.keyPressed(key,scan,mods);}
 @Override public void removed(){requestId++;}
 @Override public boolean shouldPause(){return false;}
}
