package dev.krys.blockradio;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.*;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import com.google.gson.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class RadioScreen extends Screen {
 private final boolean mini;
 private int left,top,w,h,offset,dragging,tab,source;
 private TextFieldWidget query;
 private String search="",message="";
 private java.util.List<Track> tracks=java.util.List.of();
 private boolean busy;
 private int requestId,page;
 private String youtubePage="";
 private final String[] tabs={"Поиск","Моя музыка","Моя волна","Очередь","Аккаунты"};
 private final String[] sources={"all","yandex","youtube"};
 public RadioScreen(BlockPos ignored){this(ignored,false);}
 public RadioScreen(BlockPos ignored,boolean mini){super(Text.literal("BlockRadio"));this.mini=mini;}
 private void button(String label,int x,int y,int width,Runnable click){addDrawableChild(ButtonWidget.builder(Text.literal(label),b->click.run()).dimensions(x,y,width,20).build());}
 @Override protected void init(){
  w=Math.min(mini?340:640,width-16);h=mini?142:Math.min(390,height-16);left=(width-w)/2;top=mini?height-h-8:(height-h)/2;
  if(mini){button("Открыть полный плеер",left+10,top+8,w-20,()->client.setScreen(new RadioScreen(null)));}
  else{
   int tw=(w-20)/5;for(int i=0;i<5;i++){final int index=i;button(tabs[i],left+10+i*tw,top+9,tw-3,()->{tab=index;offset=0;requestId++;busy=false;initScreen();if(tab==1||tab==2)fetch();});}
   if(tab==0||tab==1){int sw=(w-20)/3;String[] labels={"Все","Яндекс","YouTube"};for(int i=0;i<3;i++){final int index=i;button((source==i?"● ":"")+labels[i],left+10+i*sw,top+35,sw-3,()->{source=index;initScreen();if(tab==1)fetch();});}}
   if(tab==0){query=new TextFieldWidget(textRenderer,left+10,top+62,w-164,20,Text.literal("Поиск музыки"));query.setMaxLength(160);query.setText(search);addDrawableChild(query);button("Найти",left+w-148,top+62,66,()->fetch());button("Ещё",left+w-76,top+62,66,()->fetch(true));}
   if(tab==1)button("Загрузить ещё",left+10,top+62,w-20,()->fetch(true));
   if(tab==2)button("Получить трек Моей волны",left+10,top+40,w-20,this::fetch);
   if(tab==3)button("Очистить очередь",left+10,top+40,w-20,()->BlockRadio.command("clear",null));
   if(tab==4){int cardW=(w-30)/2;for(int i=0;i<2;i++){final String provider=i==0?"yandex":"youtube";int x=left+10+i*(cardW+10);button("Войти",x+8,top+122,(cardW-24)/2,()->BlockRadio.action("/login",provider));button("Выйти",x+16+(cardW-24)/2,top+122,(cardW-24)/2,()->BlockRadio.action("/logout",provider));}}
  }
  int y=top+h-70;button("|<",left+10,y,32,()->BlockRadio.command("previous",null));button("> / ||",left+47,y,48,()->BlockRadio.command(BlockRadio.paused?"resume":"pause",null));button(">|",left+100,y,32,()->BlockRadio.command("next",null));
  button("Видео",left+137,y,52,()->BlockRadio.action("/player/open","youtube"));
  button("Auto",left+194,y,42,BlockRadio::toggleAutoplay);
  if(!mini)button("Стоп",left+241,y,45,()->BlockRadio.command("stop",null));
 }
 private void initScreen(){if(query!=null)search=query.getText();query=null;clearChildren();init();}
 private void fetch(){fetch(false);}
 private void fetch(boolean append){if(busy)return;if(!BackendManager.ready){message=BackendManager.status;return;}if(query!=null)search=query.getText().trim();if(tab==0&&search.isEmpty())return;
  if(!append){page=0;youtubePage="";}else page++;
  String route=tab==2?"/wave":tab==1?"/liked?source="+sources[source]:"/search?source="+sources[source]+"&q="+URLEncoder.encode(search,StandardCharsets.UTF_8);
  if(tab!=2)route+="&page="+page+"&youtube_page="+URLEncoder.encode(youtubePage,StandardCharsets.UTF_8);
  final String requestRoute=route;
  busy=true;message="Загрузка…";int job=++requestId;
  BlockRadio.background("search",()->{try{JsonObject result=BlockRadio.get(requestRoute);ArrayList<Track> list=new ArrayList<>(append?tracks:java.util.List.of());for(var item:result.getAsJsonArray("tracks"))list.add(Track.parse(item.getAsJsonObject()));client.execute(()->{if(job!=requestId)return;tracks=list.stream().distinct().toList();BlockRadio.candidates=tracks;if(!append)offset=0;if(result.has("youtube_next"))youtubePage=result.get("youtube_next").getAsString();message=result.has("message")?result.get("message").getAsString():"";busy=false;});}catch(Exception e){client.execute(()->{if(job==requestId){busy=false;message=e.getMessage();}});}});
 }
 private int listTop(){return top+(tab==0||tab==1?90:68);}
 private int rows(){return Math.max(0,(top+h-120-listTop())/44);}
 private java.util.List<Track> visible(){return tab==3?BlockRadio.queue:tracks;}
 private void cover(DrawContext d,String url,int x,int y,int size){d.fill(x,y,x+size,y+size,0xff2b3543);var id=CoverCache.get(url);if(id!=null)d.drawTexture(id,x,y,0,0,size,size,size,size);else d.drawTextWithShadow(textRenderer,"♪",x+size/2-3,y+size/2-4,0xff70d9b4);}
 private void text(DrawContext d,String s,int x,int y,int width,int color){d.drawTextWithShadow(textRenderer,textRenderer.trimToWidth(s,width),x,y,color);}
 @Override public void render(DrawContext d,int mx,int my,float delta){
  if(!mini)renderBackground(d);d.fill(left,top,left+w,top+h,0xf21a202a);d.fill(left,top,left+w,top+2,0xff70d9b4);super.render(d,mx,my,delta);
  if(!mini&&tab==4){int cw=(w-30)/2;for(int i=0;i<2;i++){String provider=i==0?"yandex":"youtube";int x=left+10+i*(cw+10);JsonObject a=BlockRadio.accounts.has(provider)?BlockRadio.accounts.getAsJsonObject(provider):new JsonObject();cover(d,Track.s(a,"avatar"),x+8,top+46,36);text(d,i==0?"Яндекс Музыка":"YouTube",x+50,top+46,cw-58,0xffffffff);text(d,Track.s(a,"name"),x+50,top+62,cw-58,0xff70d9b4);text(d,Track.s(a,"state"),x+8,top+91,cw-16,0xffb6bfd0);text(d,Track.s(a,"message"),x+8,top+107,cw-16,0xffdcb98b);}}
  else if(!mini){var list=visible();for(int row=0;row<rows()&&row+offset<list.size();row++){Track t=list.get(row+offset);int y=listTop()+row*44;d.fill(left+9,y-2,left+w-9,y+39,0xff242c39);cover(d,t.cover(),left+13,y+1,34);text(d,t.title(),left+55,y+2,w-160,0xfff4f6fa);text(d,t.artist(),left+55,y+15,w-160,0xffaab6c8);text(d,(t.provider().equals("yandex")?"Яндекс":"YouTube")+" · "+format(t.duration()),left+55,y+28,w-160,0xff70d9b4);text(d,">",left+w-78,y+13,20,0xffffffff);text(d,"+",left+w-38,y+13,20,0xff70d9b4);}}
  Track t=BlockRadio.current;int y=top+h-113;cover(d,t==null?"":t.cover(),left+10,y,34);text(d,t==null?"Выберите музыку":t.title(),left+52,y+2,w-62,0xffffffff);text(d,t==null?"BlockRadio":t.artist()+" · "+(t.provider().equals("yandex")?"Яндекс":"YouTube"),left+52,y+17,w-62,0xffaab6c8);
  long pos=BlockRadio.current==null?0:Math.max(0,BlockRadio.target()),duration=t==null?0:t.duration();
  int sy=top+h-42;d.fill(left+10,sy,left+w-10,sy+4,0xff3c485d);int length=(int)((w-20)*Math.min(1,duration>0?(double)pos/duration:0));d.fill(left+10,sy,left+10+length,sy+4,0xff70d9b4);
  text(d,format(pos)+" / "+format(duration),left+10,sy+8,100,0xffaab6c8);
  int vx=left+w-115;d.fill(vx,sy+10,left+w-10,sy+14,0xff3c485d);d.fill(vx,sy+10,vx+(int)(105*BlockRadio.volume),sy+14,0xff70d9b4);
  text(d,BlockRadio.autoplay?"Auto ON":"Auto OFF",left+112,sy+8,80,0xff70d9b4);
  String info=!BackendManager.ready?BackendManager.status:(!message.isEmpty()?message:BlockRadio.status);text(d,info,left+10,top+h-13,w-20,0xffdcb98b);
 }
 static String format(long ms){long s=ms/1000;return s/60+":"+String.format("%02d",s%60);}
 @Override public boolean mouseClicked(double x,double y,int button){if(super.mouseClicked(x,y,button))return true;if(button!=0)return false;
  if(y>=top+h-46&&y<=top+h-35&&x>=left+10&&x<=left+w-10){dragging=1;slider(x);return true;}
  if(y>=top+h-35&&y<=top+h-23&&x>=left+w-115&&x<=left+w-10){dragging=2;slider(x);return true;}
  if(!mini&&tab!=4&&y>=listTop()&&y<listTop()+rows()*44){int i=offset+(int)(y-listTop())/44;var list=visible();if(i<list.size()&&x>=left+10&&x<left+w-10){BlockRadio.command(x>left+w-50?"enqueue":"play",list.get(i));return true;}}
  return false;
 }
 private void slider(double x){if(dragging==1&&BlockRadio.current!=null)BlockRadio.seek((long)(BlockRadio.current.duration()*Math.max(0,Math.min(1,(x-left-10)/(w-20)))));if(dragging==2)BlockRadio.setVolume((x-(left+w-115))/105);}
 @Override public boolean mouseDragged(double x,double y,int b,double dx,double dy){if(dragging!=0){slider(x);return true;}return super.mouseDragged(x,y,b,dx,dy);}
 @Override public boolean mouseReleased(double x,double y,int b){dragging=0;return super.mouseReleased(x,y,b);}
 @Override public boolean mouseScrolled(double x,double y,double amount){offset=Math.max(0,Math.min(Math.max(0,visible().size()-rows()),offset-(int)Math.signum(amount)));return true;}
 @Override public boolean keyPressed(int key,int scan,int mods){if(key==257&&tab==0&&!mini){fetch();return true;}return super.keyPressed(key,scan,mods);}
 @Override public void removed(){requestId++;}
 @Override public boolean shouldPause(){return false;}
}
