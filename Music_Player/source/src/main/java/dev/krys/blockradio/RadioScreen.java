package dev.krys.blockradio;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import com.google.gson.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public class RadioScreen extends Screen {
 private TextFieldWidget query;
 private final ArrayList<JsonObject> tracks=new ArrayList<>();
 private volatile String status="Запусти START_WINDOWS.bat из папки bridge.";
 private int left,top,w,selected=-1,page,offset,dragging;
 private String searchText="";
 private boolean busy;
 private final net.minecraft.util.math.BlockPos source;
 public RadioScreen(net.minecraft.util.math.BlockPos pos){super(Text.literal("BlockRadio"));source=pos.toImmutable();}
 @Override protected void init(){
  BlockRadio.feedback=s->status=s;
  w=Math.min(520,width-20);left=(width-w)/2;top=Math.max(8,(height-270)/2);
  query=new TextFieldWidget(textRenderer,left+12,top+37,w-100,20,Text.literal("Название или исполнитель"));
  query.setMaxLength(160);query.setText(searchText);addDrawableChild(query);
  addDrawableChild(ButtonWidget.builder(Text.literal("Найти"),b->{page=0;search();}).dimensions(left+w-80,top+37,68,20).build());
  addDrawableChild(ButtonWidget.builder(Text.literal("Войти в Яндекс"),b->login()).dimensions(left+12,top+10,125,20).build());
  addDrawableChild(ButtonWidget.builder(Text.literal("Статус входа"),b->request("/status",j->status="Яндекс: "+j.get("state").getAsString())).dimensions(left+142,top+10,100,20).build());
  addDrawableChild(ButtonWidget.builder(Text.literal("‹"),b->{if(page>0){page--;search();}}).dimensions(left+w-60,top+10,22,20).build());
  addDrawableChild(ButtonWidget.builder(Text.literal("›"),b->{page++;search();}).dimensions(left+w-35,top+10,22,20).build());
  addDrawableChild(ButtonWidget.builder(Text.literal("|◀"),b->play(selected-1)).dimensions(left+12,top+201,40,20).build());
  addDrawableChild(ButtonWidget.builder(Text.literal("▶ / II"),b->{var c=BlockRadio.clip;if(c!=null){BlockRadio.command(source,BlockRadio.paused?"resume":"pause","", "",0);}else play(selected<0?0:selected);}).dimensions(left+57,top+201,65,20).build());
  addDrawableChild(ButtonWidget.builder(Text.literal("▶|"),b->play(selected+1)).dimensions(left+127,top+201,40,20).build());
  addDrawableChild(ButtonWidget.builder(Text.literal("Стоп"),b->BlockRadio.command(source,"stop","","",0)).dimensions(left+172,top+201,45,20).build());
  setInitialFocus(query);
 }
 private void login(){if(busy)return;request("/login",j->status="Подтверди вход в браузере. Проверь кнопкой «Статус входа».");}
 private void request(String url,java.util.function.Consumer<JsonObject> done){
  busy=true;status="Подключение…";
  BlockRadio.background("BlockRadio-search",()->{try{JsonObject j=BlockRadio.get(url);client.execute(()->{done.accept(j);busy=false;});}
   catch(Exception e){client.execute(()->{status="Ошибка: "+(e.getMessage()==null?"помощник не запущен":e.getMessage());busy=false;});}});
 }
 private void search(){if(busy)return;searchText=query.getText().trim();if(searchText.isEmpty())return;
  request("/search?q="+URLEncoder.encode(searchText,StandardCharsets.UTF_8)+"&page="+page,j->{
   tracks.clear();for(var t:j.getAsJsonArray("tracks"))tracks.add(t.getAsJsonObject());selected=-1;offset=0;
   status=tracks.isEmpty()?"Ничего не найдено. Попробуй другой запрос.":"Найдено: "+tracks.size()+". Страница "+(page+1)+". Нажми на трек.";
  });
 }
 private void play(int i){if(i<0||i>=tracks.size())return;selected=i;var t=tracks.get(i);BlockRadio.feedback=s->status=s;BlockRadio.command(source,"play",t.get("id").getAsString(),t.get("title").getAsString(),0);}
 @Override public boolean keyPressed(int key,int scan,int modifiers){if(key==257){page=0;search();return true;}return super.keyPressed(key,scan,modifiers);}
 @Override public boolean mouseClicked(double x,double y,int button){
  if(button==0){
   if(x>=left+12&&x<=left+w-12&&y>=top+65&&y<top+185){play(offset+(int)(y-top-65)/15);return true;}
   if(x>=left+12&&x<=left+w-12&&y>=top+226&&y<=top+238){dragging=1;slider(x);return true;}
   if(x>=left+w-160&&x<=left+w-12&&y>=top+205&&y<=top+218){dragging=2;slider(x);return true;}
  }return super.mouseClicked(x,y,button);
 }
 private void slider(double x){if(dragging==1){var c=BlockRadio.clip;if(c!=null)BlockRadio.command(source,"seek","","",(long)(c.getMicrosecondLength()/1000*Math.max(0,Math.min(1,(x-left-12)/(w-24)))));}else if(dragging==2)BlockRadio.setVolume(Math.max(0,Math.min(1,(x-(left+w-160))/148)));}
 @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){if(button==0&&dragging!=0){slider(x);return true;}return super.mouseDragged(x,y,button,dx,dy);}
 @Override public boolean mouseReleased(double x,double y,int button){dragging=0;return super.mouseReleased(x,y,button);}
 @Override public void render(DrawContext d,int mx,int my,float delta){
  renderBackground(d);d.fill(left,top,left+w,top+269,0xf018181c);super.render(d,mx,my,delta);
  for(int row=0;row<Math.min(8,tracks.size()-offset);row++){
   int i=offset+row;int y=top+65+row*15;var t=tracks.get(i);
   if(i==selected)d.fill(left+10,y-1,left+w-10,y+13,0xff474025);
   String label=t.get("artist").getAsString()+" — "+t.get("title").getAsString();
   d.drawTextWithShadow(textRenderer,textRenderer.trimToWidth(label,w-30),left+15,y+2,0xffffff);
  }
  d.drawTextWithShadow(textRenderer,textRenderer.trimToWidth(BlockRadio.playing,w-25),left+12,top+187,0xffffcc44);
  int vx=left+w-160;d.fill(vx,top+210,left+w-12,top+214,0xff555555);d.fill(vx,top+210,vx+(int)(148*BlockRadio.volume),top+214,0xffffcc44);
  var c=BlockRadio.clip;double progress=c!=null&&c.getMicrosecondLength()>0?(double)c.getMicrosecondPosition()/c.getMicrosecondLength():0;
  d.fill(left+12,top+229,left+w-12,top+234,0xff555555);d.fill(left+12,top+229,left+12+(int)((w-24)*progress),top+234,0xffffcc44);
  String time=c==null?"0:00":format(c.getMicrosecondPosition())+" / "+format(c.getMicrosecondLength());
  d.drawTextWithShadow(textRenderer,time,left+12,top+240,0xffcccccc);
  d.drawTextWithShadow(textRenderer,textRenderer.trimToWidth(status,w-24),left+12,top+255,0xffaaaaaa);
 }
 private String format(long us){long s=us/1000000;return s/60+":"+String.format("%02d",s%60);}
 @Override public boolean mouseScrolled(double x,double y,double amount){offset=Math.max(0,Math.min(Math.max(0,tracks.size()-8),offset-(int)Math.signum(amount)));return true;}
 @Override public boolean shouldPause(){return false;}
}
