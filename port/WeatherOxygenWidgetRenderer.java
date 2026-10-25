package dev.weather.oneplus;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import java.io.InputStream;
import java.util.Calendar;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONObject;

/** Static Android adaptations of Oxygen Weather desktop and lock-screen templates.
 * All values come from the validated Xiaomi widget cache; private Oplus hosts are not used.
 */
final class WeatherOxygenWidgetRenderer {
    private static final String ASSETS = "oxygen_widget/";
    private static final int DAWN=0, MORNING=1, NOON=2, AFTERNOON=3, DUSK=4, NIGHT=5;
    private final Context context;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Typeface medium;
    private final Typeface semibold;

    WeatherOxygenWidgetRenderer(Context context) {
        this.context = context.getApplicationContext();
        medium = WeatherWidgetFonts.face(this.context, 380, 500);
        semibold = WeatherWidgetFonts.face(this.context, 450, 600);
    }

    Bitmap render(int style, WeatherWidgetData data, JSONObject details, long time, int width, int height) {
        // A batch reuses this renderer for several instances/responsive sizes.
        // Arc strokes and translucent separators must not tint the next background.
        paint.reset();
        paint.setFlags(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        text.reset();
        text.setFlags(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        if (style == 8) return renderOrdinary(data, details, time, width, height);
        return renderTemplate(style, data, details, time, width, height);
    }

    private Bitmap renderOrdinary(WeatherWidgetData data, JSONObject details, long time, int width, int height) {
        Bitmap out = Bitmap.createBitmap(Math.max(1,width), Math.max(1,height), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.save();
        // Fit only the sky to the host bounds. Keep glyphs/circles proportional
        // when a launcher's 2x2 cell is not square. Bottom content stays anchored.
        float scale = Math.min(out.getWidth(),out.getHeight())/144f;
        float w=out.getWidth()/scale, h=out.getHeight()/scale;
        c.scale(scale,scale);
        Path shape = new Path();
        shape.addRoundRect(new RectF(0,0,w,h), 16,16, Path.Direction.CW);
        c.clipPath(shape);
        int phase = phase(data,time);
        boolean night = phase == NIGHT;
        drawBackground(c,data,phase,w,h);
        marker(c,24,22);
        label(c,data==null?"--":data.cityName,34,26,12,w-50,medium);
        String temperature = data==null?"--":data.temperature;
        text.setTextSize(40); text.setTypeface(semibold);
        float natural=text.measureText(temperature), degree=text.measureText("°");
        float numberSize=40*Math.min(1,(w-16-6-26-7)/(natural+degree));
        label(c,temperature,16,76,numberSize,natural*numberSize/40+1,semibold);
        float degreeX=16+natural*numberSize/40+1;
        if(data!=null)label(c,"°",degreeX,76,numberSize,degree*numberSize/40+1,semibold);
        String icon = icon(data==null?-1:data.weatherType,night);
        if(icon!=null)drawAsset(c,"icons/"+icon,Math.min(w-32,degreeX+degree*numberSize/40+5),51,26,26);

        // Original 13dp-radius white translucent advice pill, now bound to actual data.
        paint.setShader(null);
        paint.setColor(night?0x1affffff:0x4dffffff);
        c.drawRoundRect(new RectF(16,h-40,w-16,h-14),13,13,paint);
        if(icon!=null)drawAsset(c,"icons/"+icon,22,h-36,18,18);
        String bottom = summary(data,time);
        float summaryX=icon==null?22:44;
        label(c,bottom,summaryX,h-23,10,w-24-summaryX,medium);
        c.restore();
        return out;
    }

    private Bitmap renderTemplate(int style, WeatherWidgetData data, JSONObject details, long time,
            int width, int height) {
        Bitmap out = Bitmap.createBitmap(Math.max(1,width), Math.max(1,height), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        if (details == null) details = new JSONObject();
        boolean expandedStrip = style == 16 && width > height * 1.35f;
        float baseW, baseH;
        switch (style) {
            case 10: baseW=340; baseH=150; break;
            case 11: case 12: baseW=340; baseH=340; break;
            case 13: case 15: case 22: baseW=150; baseH=60; break;
            case 16: baseW=150; baseH=expandedStrip?60:150; break;
            case 17: case 18: case 19: case 20: case 21: baseW=64; baseH=64; break;
            default: baseW=150; baseH=150; break;
        }
        boolean compact = style >= 17 && style <= 21;
        float scale=Math.min(out.getWidth()/baseW,out.getHeight()/baseH);
        if (compact) {
            // Some launchers give 1x1 widgets the whole icon cell with zero
            // padding. Match the 60dp compact-strip height instead of inflating
            // this card to the cell's full width. Keep a gutter on smaller hosts.
            float density=context.getResources().getDisplayMetrics().density;
            float side=Math.min(Math.min(out.getWidth(),out.getHeight())*.90f,60f*density);
            scale=side/baseW;
        }
        c.translate((out.getWidth()-baseW*scale)/2f,(out.getHeight()-baseH*scale)/2f);
        c.scale(scale,scale);
        c.save();
        float radius=style==24?24:(compact||style==9||style==13||style==15||style==22||expandedStrip)?14:20;
        Path shape=new Path();shape.addRoundRect(new RectF(0,0,baseW,baseH),radius,radius,Path.Direction.CW);
        c.clipPath(shape);
        int phase=phase(data,time);boolean night=phase==NIGHT;
        JSONArray hourly=details.optJSONArray("hourly"), daily=details.optJSONArray("daily");

        switch(style) {
            case 9: quickSmallSky(c,data,time,baseW,baseH); break;
            case 10: quickMiddle(c,data,time,hourly,baseW,baseH); break;
            case 11: quickBig(c,data,time,hourly,daily,baseW,baseH); break;
            case 12: dragonfly(c,data,details,time,hourly,baseW,baseH); break;
            case 13: seedlingStrip(c,data,time,baseW,baseH,false); break;
            case 14: seedlingSquare(c,data,time,details,baseW,baseH,false); break;
            case 15: seedlingStrip(c,data,time,baseW,baseH,true); break;
            case 16:
                if(expandedStrip)seedlingStrip(c,data,time,baseW,baseH,true);
                else seedlingSquare(c,data,time,details,baseW,baseH,true);
                break;
            case 17: airTemperature(c,data,baseW,baseH); break;
            case 18: liveWeather(c,data,night,baseW,baseH); break;
            case 19: uvIndex(c,details,baseW,baseH); break;
            case 20: sunriseSunset(c,data,baseW,baseH); break;
            case 21: rpkMicro(c,data,night,baseW,baseH); break;
            case 22: rpkStrip(c,data,time,baseW,baseH); break;
            case 23: rpkToday(c,data,details,time,hourly,daily,baseW,baseH); break;
            case 24: quickSmallOos(c,data,time,baseW,baseH); break;
            default: drawBackground(c,data,phase,baseW,baseH); break;
        }
        c.restore();
        return out;
    }

    private void quickSmallSky(Canvas c,WeatherWidgetData data,long time,float w,float h) {
        int period=phase(data,time);boolean night=period==NIGHT;
        drawBackground(c,data,period,w,h);
        label(c,data==null?"--":data.cityName,16,35,14,w-62,medium);
        String temperature=data==null?"--":missing(data.temperature);
        text.setTypeface(semibold);text.setTextSize(40);
        float measured=text.measureText(temperature);
        float size=40*Math.min(1,(w-40)/Math.max(1,measured));
        labelColor(c,temperature,16,83,size,w-52,semibold,0xffffffff);
        if(data!=null)drawOosGlyph(c,"degree_light",16+measured*size/40+1,55,9,9,false);
        String art=icon(data==null?-1:data.weatherType,night);
        if(art!=null)drawAsset(c,"icons/"+art,w-46,25,30,30);
        label(c,data==null?"--":missing(data.description),16,h-39,14,w-32,medium);
        String highLow=data==null||data.high.isEmpty()||data.low.isEmpty()?"-- / --"
                :data.low+"\u00b0 / "+data.high+"\u00b0";
        label(c,highLow,16,h-18,12,w-32,medium);
    }

    private void quickSmallOos(Canvas c,WeatherWidgetData data,long time,float w,float h) {
        // OOS takes precedence over ColorOS 16's sky-image branch in the source APK.
        // Its background is selected by time and theme, independently of the icon.
        int period=phase(data,time);boolean night=period==NIGHT;
        boolean dark=(context.getResources().getConfiguration().uiMode
                &android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES;
        String periodName=night?"night":(dark?"dark_":"")+(period==DUSK?"evening":"daytime");
        boolean lightText=dark||night;
        int primary=lightText?0xffffffff:0xe5000000,secondary=lightText?0x8cffffff:0x8c000000;
        drawOosBackground(c,"weather_quick_card_sunny_"+periodName+"_2x2.webp",w,h);
        String temperature=data==null?"--":missing(data.temperature);
        text.setTypeface(medium);text.setTextSize(40);
        float textWidth=text.measureText(temperature),size=40*Math.min(1,(w-62)/Math.max(1,textWidth));
        labelColor(c,temperature,12,45,size,w-62,medium,primary);
        if(data!=null)drawOosGlyph(c,"degree_"+(lightText?"light":"dark"),
                12+textWidth*size/40,19,8,8,false);
        labelColor(c,data==null?"--":data.cityName,12,68,12,w-24,medium,primary);
        String art=icon(data==null?-1:data.weatherType,night);
        if(art!=null)drawAsset(c,"icons/"+art,w-39,15,24,24);
        labelColor(c,data==null?"--":missing(data.description),12,h-35,12,w-24,medium,secondary);
        String high=data==null||data.high.isEmpty()?"--":data.high+"°";
        String low=data==null||data.low.isEmpty()?"--":data.low+"°";
        String arrow="icon_arrow_"+(lightText?"light":"dark")+"_up";
        drawOosGlyph(c,arrow,12,h-27,8,12,false);
        labelColor(c,high,22,h-17,10,40,medium,secondary);
        text.setTypeface(medium);text.setTextSize(10);float x=30+text.measureText(high);
        drawOosGlyph(c,arrow,x,h-27,8,12,true);
        labelColor(c,low,x+10,h-17,10,w-x-22,medium,secondary);
    }

    private void drawOosBackground(Canvas c,String name,float w,float h) {
        try(InputStream in=context.getAssets().open(ASSETS+"oos_backgrounds/"+name)){
            Bitmap bitmap=BitmapFactory.decodeStream(in);
            if(bitmap!=null){
                float scale=Math.max(w/bitmap.getWidth(),h/bitmap.getHeight());
                paint.setColor(Color.WHITE);paint.setShader(null);
                c.drawBitmap(bitmap,null,new RectF(w-bitmap.getWidth()*scale,0,w,bitmap.getHeight()*scale),paint);
                bitmap.recycle();
            }
        }catch(java.io.IOException error){throw new IllegalStateException("Missing OOS widget background: "+name,error);}
    }
    private void drawOosGlyph(Canvas c,String suffix,float x,float y,int width,int height,boolean flip) {
        int id=context.getResources().getIdentifier("weather_oxygen_oos_"+suffix,"drawable",context.getPackageName());
        android.graphics.drawable.Drawable glyph=context.getDrawable(id);
        c.save();c.translate(x,y);if(flip)c.rotate(180,width/2f,height/2f);
        glyph.setBounds(0,0,width,height);glyph.draw(c);c.restore();
    }

    private void quickMiddle(Canvas c,WeatherWidgetData data,long time,JSONArray hourly,float w,float h) {
        drawBackground(c,data,phase(data,time),w,h);
        marker(c,25,23);label(c,data==null?"--":data.cityName,36,27,12,110,medium);
        label(c,temp(data),16,83,43,118,semibold);
        String art=icon(data==null?-1:data.weatherType,phase(data,time)==NIGHT);
        if(art!=null)drawAsset(c,"icons/"+art,115,53,24,24);
        label(c,data==null?"--":missing(data.description),16,108,12,125,medium);
        label(c,range(data),16,130,10,125,medium);
        paint.setColor(0x55ffffff);c.drawLine(154,16,154,h-16,paint);
        for(int i=0;i<5;i++) {
            JSONObject item=at(hourly,i);float x=180+i*34;
            label(c,item==null?"--:--":clockTime(item.optLong("timeMs",0),data),x,39,9,34,medium,Paint.Align.CENTER);
            int type=item==null?-1:item.optInt("weatherType",-1);
            String weather=icon(type,nightAt(data,item==null?0:item.optLong("timeMs",0)));
            if(weather!=null)drawAsset(c,"icons/"+weather,x-9,51,18,18);
            label(c,item==null?"--":item.optString("temp","--")+"\u00b0",x,91,12,34,medium,Paint.Align.CENTER);
        }
    }

    private void quickBig(Canvas c,WeatherWidgetData data,long time,JSONArray hourly,JSONArray daily,float w,float h) {
        drawBackground(c,data,phase(data,time),w,h);
        marker(c,24,23);label(c,data==null?"--":data.cityName,35,27,13,w-92,medium);
        label(c,temp(data),17,91,48,150,semibold);
        String art=icon(data==null?-1:data.weatherType,phase(data,time)==NIGHT);
        if(art!=null)drawAsset(c,"icons/"+art,w-72,48,44,44);
        label(c,data==null?"--":missing(data.description),18,116,14,w-36,medium);
        label(c,range(data),18,137,12,w-36,medium);
        paint.setColor(0x55ffffff);c.drawLine(16,153,w-16,153,paint);
        for(int i=0;i<6;i++) {
            JSONObject item=at(hourly,i);float x=37+i*53;
            label(c,item==null?"--:--":clockTime(item.optLong("timeMs",0),data),x,178,10,51,medium,Paint.Align.CENTER);
            String weather=icon(item==null?-1:item.optInt("weatherType",-1),
                    nightAt(data,item==null?0:item.optLong("timeMs",0)));
            if(weather!=null)drawAsset(c,"icons/"+weather,x-10,188,20,20);
            label(c,item==null?"--":item.optString("temp","--")+"\u00b0",x,227,12,51,medium,Paint.Align.CENTER);
        }
        paint.setColor(0x55ffffff);c.drawLine(16,241,w-16,241,paint);
        for(int i=0;i<5;i++) {
            JSONObject item=at(daily,i);float x=40+i*65;
            label(c,item==null?"--":weekday(item.optLong("timeMs",0),data),x,270,10,62,medium,Paint.Align.CENTER);
            String weather=icon(item==null?-1:item.optInt("weatherType",-1),false);
            if(weather!=null)drawAsset(c,"icons/"+weather,x-8,279,16,16);
            label(c,item==null?"--/--":dailyRange(item),x,316,10,62,medium,Paint.Align.CENTER);
        }
    }

    private void dragonfly(Canvas c,WeatherWidgetData data,JSONObject details,long time,JSONArray hourly,float w,float h) {
        drawBackground(c,data,phase(data,time),w,h);
        marker(c,25,25);label(c,data==null?"--":data.cityName,37,29,14,w-90,medium);
        String current=icon(data==null?-1:data.weatherType,phase(data,time)==NIGHT);
        if(current!=null)drawAsset(c,"icons/"+current,w-65,54,42,42);
        label(c,temp(data),17,107,60,190,semibold);
        label(c,data==null?"--":missing(data.description),18,135,16,w-36,medium);
        label(c,range(data),18,157,13,w-36,medium);
        JSONObject first=at(hourly,0);
        if(first!=null) {
            label(c,clockTime(first.optLong("timeMs",0),data)+"  "+first.optString("temp","--")+"\u00b0",
                    18,182,12,w-36,medium);
        } else label(c,"--:--  --",18,182,12,w-36,medium);
        paint.setColor(0x55ffffff);c.drawLine(16,198,w-16,198,paint);
        for(int i=0;i<8;i++) {
            JSONObject item=at(hourly,i);float x=24+i*42;
            label(c,item==null?"--:--":clockTime(item.optLong("timeMs",0),data),x,227,9,41,medium,Paint.Align.CENTER);
            String weather=icon(item==null?-1:item.optInt("weatherType",-1),
                    nightAt(data,item==null?0:item.optLong("timeMs",0)));
            if(weather!=null)drawAsset(c,"icons/"+weather,x-8,236,16,16);
            label(c,item==null?"--":item.optString("temp","--")+"\u00b0",x,271,11,41,medium,Paint.Align.CENTER);
        }
        paint.setColor(0x55ffffff);c.drawLine(16,284,w-16,284,paint);
        label(c,"AQI  "+aqi(data),18,310,12,w-36,medium);
        label(c,local("Humidity","湿度")+"  "+missing(details.optString("humidity","")),w-18,310,12,w/2,medium,Paint.Align.RIGHT);
    }

    private void seedlingStrip(Canvas c,WeatherWidgetData data,long time,float w,float h,boolean dark) {
        paint.setShader(new LinearGradient(0,0,0,h,dark?new int[]{0xff244260,0xff386c98}
                :new int[]{0xffeaf3f8,0xffdce9f1},null,Shader.TileMode.CLAMP));
        c.drawRect(0,0,w,h,paint);paint.setShader(null);
        int fg=dark?Color.WHITE:0xff263746;
        String art=icon(data==null?-1:data.weatherType,phase(data,time)==NIGHT);
        if(art!=null)drawAsset(c,"icons/"+art,12,18,22,22);
        labelColor(c,data==null?"--":data.cityName,42,23,11,w-90,medium,fg);
        labelColor(c,data==null?"--":missing(data.description),42,39,10,w-92,medium,dark?0xffd7e6f0:0xff526574);
        labelColor(c,temp(data),w-12,38,21,w/2,semibold,fg,Paint.Align.RIGHT);
    }

    private void rpkStrip(Canvas c,WeatherWidgetData data,long time,float w,float h) {
        panel(c,w,h,0xff1e3f5b,0xff386783);
        String art=icon(data==null?-1:data.weatherType,phase(data,time)==NIGHT);
        if(art!=null)drawAsset(c,"icons/"+art,12,19,22,22);
        label(c,data==null?"--":data.cityName,43,24,11,w-92,medium);
        label(c,data==null?"--":missing(data.description),43,41,9,w-94,medium);
        label(c,temp(data),w-12,39,20,w/2,semibold,Paint.Align.RIGHT);
    }

    private void seedlingSquare(Canvas c,WeatherWidgetData data,long time,JSONObject details,float w,float h,boolean expanded) {
        if(expanded)panel(c,w,h,0xff2e5065,0xff457e98);
        else drawBackground(c,data,phase(data,time),w,h);
        marker(c,22,22);label(c,data==null?"--":data.cityName,32,26,11,w-85,medium);
        String art=icon(data==null?-1:data.weatherType,phase(data,time)==NIGHT);
        if(art!=null)drawAsset(c,"icons/"+art,w-48,12,28,28);
        label(c,temp(data),15,81,39,w-30,semibold);
        label(c,data==null?"--":missing(data.description),16,105,11,w-32,medium);
        label(c,range(data),16,120,11,w-32,medium);
        String condition=expanded?local("Feels like","体感")+"  "+missing(details.optString("feelsLike",""))
                :local("Humidity","湿度")+"  "+missing(details.optString("humidity",""));
        label(c,condition,16,h-11,10,w-32,medium);
    }

    private void airTemperature(Canvas c,WeatherWidgetData data,float w,float h) {
        panel(c,w,h,0xff153e61,0xff2f82ad);
        float cx=w/2;paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(5);
        paint.setStrokeCap(Paint.Cap.ROUND);paint.setColor(0x44ffffff);c.drawArc(new RectF(10,9,w-10,47),180,180,false,paint);
        paint.setStyle(Paint.Style.FILL);paint.setStrokeCap(Paint.Cap.BUTT);
        centerLabel(c,data==null?"--":data.temperature+"\u00b0",cx,36,17,w-12,semibold,Color.WHITE);
        centerLabel(c,data==null?"AIR":data.unit.equals("F")?"\u00b0F":"\u00b0C",cx,53,8,w-12,medium,0xffd4e7f3);
    }

    private void liveWeather(Canvas c,WeatherWidgetData data,boolean night,float w,float h) {
        panel(c,w,h,night?0xff193652:0xff4c91bf,night?0xff284f70:0xffa2d5f4);
        String art=icon(data==null?-1:data.weatherType,night);
        if(art!=null)drawAsset(c,"icons/"+art,22,7,20,20);
        centerLabel(c,temp(data),w/2,43,17,w-8,semibold,Color.WHITE);
        centerLabel(c,data==null?"--":missing(data.description),w/2,57,7,w-8,medium,Color.WHITE);
    }

    private void uvIndex(Canvas c,JSONObject details,float w,float h) {
        panel(c,w,h,0xff495f70,0xff6a8290);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(4);paint.setColor(0x55ffffff);
        c.drawArc(new RectF(11,8,w-11,50),180,180,false,paint);paint.setStyle(Paint.Style.FILL);
        centerLabel(c,missing(details.optString("uvIndex","")),w/2,36,17,w-10,semibold,Color.WHITE);
        centerLabel(c,local("UV","紫外线"),w/2,54,8,w-10,medium,0xffe1ebf0);
    }

    private void sunriseSunset(Canvas c,WeatherWidgetData data,float w,float h) {
        panel(c,w,h,0xffb8784b,0xffe8b66d);
        boolean valid=data!=null&&data.sunriseMinute>=0&&data.sunsetMinute>=0;
        centerLabel(c,local("RISE","日出"),w/2,17,7,w-8,medium,0xfffff1d9);
        centerLabel(c,valid?clock(data.sunriseMinute):"--:--",w/2,32,12,w-8,semibold,Color.WHITE);
        paint.setColor(0x66ffffff);c.drawLine(10,37,w-10,37,paint);
        centerLabel(c,local("SET","日落"),w/2,46,7,w-8,medium,0xfffff1d9);
        centerLabel(c,valid?clock(data.sunsetMinute):"--:--",w/2,58,12,w-8,semibold,Color.WHITE);
    }

    private void rpkMicro(Canvas c,WeatherWidgetData data,boolean night,float w,float h) {
        panel(c,w,h,night?0xff1b3655:0xff4787bc,night?0xff315c82:0xffa8d5f1);
        String art=icon(data==null?-1:data.weatherType,night);
        if(art!=null)drawAsset(c,"icons/"+art,23,5,18,18);
        centerLabel(c,temp(data),w/2,43,16,w-8,semibold,Color.WHITE);
        centerLabel(c,data==null?"--":missing(data.description),w/2,56,7,w-8,medium,Color.WHITE);
    }

    private void rpkToday(Canvas c,WeatherWidgetData data,JSONObject details,long time,JSONArray hourly,JSONArray daily,float w,float h) {
        drawBackground(c,data,phase(data,time),w,h);
        label(c,data==null?"--":data.cityName,15,21,11,w-65,medium);
        String art=icon(data==null?-1:data.weatherType,phase(data,time)==NIGHT);
        if(art!=null)drawAsset(c,"icons/"+art,w-42,10,27,27);
        label(c,temp(data),15,66,36,w-30,semibold);
        label(c,data==null?"--":missing(data.description),16,85,10,w-32,medium);
        label(c,range(data),16,101,10,w-32,medium);
        paint.setColor(0x55ffffff);c.drawLine(12,110,w-12,110,paint);
        JSONObject next=at(hourly,0);
        label(c,local("Next","接下来")+"  "+(next==null?"--":next.optString("temp","--")+"\u00b0"),12,126,9,w/2-20,medium);
        JSONObject today=at(daily,0);
        label(c,local("Today","今日")+"  "+(today==null?"--/--":dailyRange(today)),w-12,126,9,w/2-20,medium,Paint.Align.RIGHT);
        label(c,"AQI  "+aqi(data),12,142,9,w/2-20,medium);
        label(c,local("Humidity","湿度")+"  "+missing(details.optString("humidity","")),w-12,142,9,w/2-20,medium,Paint.Align.RIGHT);
    }

    private void panel(Canvas c,float w,float h,int top,int bottom) {
        paint.setShader(new LinearGradient(0,0,0,h,top,bottom,Shader.TileMode.CLAMP));
        c.drawRect(0,0,w,h,paint);paint.setShader(null);
    }

    private void centerLabel(Canvas c,String value,float center,float baseline,float size,float max,
            Typeface face,int color) {
        text.setTypeface(face);text.setTextSize(size);text.setColor(color);text.setTextAlign(Paint.Align.CENTER);
        String shown=TextUtils.ellipsize(value==null?"--":value,text,max,TextUtils.TruncateAt.END).toString();
        c.drawText(shown,center,baseline,text);
    }

    private void labelColor(Canvas c,String value,float x,float baseline,float size,float max,
            Typeface face,int color) { labelColor(c,value,x,baseline,size,max,face,color,Paint.Align.LEFT); }

    private void labelColor(Canvas c,String value,float x,float baseline,float size,float max,
            Typeface face,int color,Paint.Align align) {
        text.setTypeface(face);text.setTextSize(size);text.setColor(color);text.setTextAlign(align);
        String shown=TextUtils.ellipsize(value==null?"--":value,text,max,TextUtils.TruncateAt.END).toString();
        c.drawText(shown,x,baseline,text);
    }

    private static String missing(String value) { return value==null||value.trim().isEmpty()?"--":value.trim(); }
    private String local(String english,String chinese) {
        return "zh".equals(context.getResources().getConfiguration().getLocales().get(0).getLanguage())
                ?chinese:english;
    }
    private static String temp(WeatherWidgetData data) { return data==null?"--":data.temperature+"\u00b0"; }
    private static String range(WeatherWidgetData data) {
        return data==null||data.high.isEmpty()||data.low.isEmpty()?"-- / --":data.high+"\u00b0 / "+data.low+"\u00b0";
    }
    private static String aqi(WeatherWidgetData data) {
        return data==null||data.aqi<0?"--":Integer.toString(data.aqi);
    }
    private static JSONObject at(JSONArray values,int index) { return values==null?null:values.optJSONObject(index); }
    private static String dailyRange(JSONObject item) {
        return item==null?"--/--":item.optString("high","--")+"/"+item.optString("low","--");
    }
    private static String clock(int minute) {
        return String.format(java.util.Locale.getDefault(),"%02d:%02d",minute/60,minute%60);
    }
    private static String clockTime(long time,WeatherWidgetData data) {
        if(time<=0)return "--:--";
        java.text.SimpleDateFormat format=new java.text.SimpleDateFormat("HH:mm",java.util.Locale.getDefault());
        format.setTimeZone(data!=null&&!data.zoneId.isEmpty()?TimeZone.getTimeZone(data.zoneId):TimeZone.getDefault());
        return format.format(new java.util.Date(time));
    }
    private static String weekday(long time,WeatherWidgetData data) {
        if(time<=0)return "--";
        java.text.SimpleDateFormat format=new java.text.SimpleDateFormat("EEE",java.util.Locale.getDefault());
        format.setTimeZone(data!=null&&!data.zoneId.isEmpty()?TimeZone.getTimeZone(data.zoneId):TimeZone.getDefault());
        return format.format(new java.util.Date(time));
    }
    private static boolean nightAt(WeatherWidgetData data,long time) {
        if(time<=0)return data==null||data.sunriseMinute<0||data.sunsetMinute<0;
        return phase(data,time)==NIGHT;
    }

    private void drawBackground(Canvas c, WeatherWidgetData data, int phase,float w,float h) {
        int type = data==null?-1:data.weatherType;
        int[] colors;
        if(type<0||type>25)colors=new int[]{0xff455466,0xff7e929f};
        else if (phase==NIGHT) {
            if (type>=4 && type<=12 || type==25) colors=new int[]{0xff152947,0xff456486};
            else if (type>=13 && type<=17) colors=new int[]{0xff273653,0xff6686a0};
            else colors=new int[]{0xff133765,0xff5e84b3};
        } else if (type>=4 && type<=12 || type==25) {
            colors=new int[]{0xff386598,0xff87a8c5};
        } else if (type>=13 && type<=17) {
            colors=new int[]{0xff5e89b2,0xffa9c6db};
        } else if (type>=18 && type<=24) {
            colors=new int[]{0xff997960,0xffd5ad86};
        } else {
            colors=new int[]{0xff3674c5,0xff8cc5f0};
        }
        paint.setColor(Color.WHITE);
        paint.setShader(new LinearGradient(0,0,0,h,colors,null,Shader.TileMode.CLAMP));
        c.drawRect(0,0,w,h,paint);
        paint.setShader(null);
        String background=background(type,phase);
        if (background!=null) drawAsset(c,"backgrounds/"+background,0,0,w,h);
    }

    private static String background(int type,int phase) {
        switch(type) {
            case 0: return "weather_quick_card_sunday_"+sunPhase(phase)+"_small_16_bg.webp";
            case 1: return "weather_quick_card_cloudy_"+cloudPhase(phase)+"_small_16_bg.webp";
            case 2: return "weather_quick_card_overcast_"+overcastPhase(phase)+"_small_16_bg.webp";
            case 3: return phase==NIGHT?"weather_quick_card_fog_night_small_16_bg.webp":"weather_quick_card_fog_noon_night_small_16_bg.webp";
            case 4: case 5: case 6: case 9: return "weather_quick_card_heavy_rain_noon_small_16_bg.webp";
            case 7: return "weather_quick_card_thunderstorm_noon_small_16_bg.webp";
            case 8: return "weather_quick_card_thundershower_small_16_bg.webp";
            case 10: return "weather_quick_card_middle_rain_noon_small_16_bg.webp";
            case 11: return "weather_quick_card_rain_noon_small_16_bg.webp";
            case 12: case 25: return "weather_quick_card_sleeting_small_16_bg.webp";
            case 13: case 15: return "weather_quick_card_heavy_snow_noon_small_16_bg.webp";
            case 14: case 17: return "weather_quick_card_light_snow_noon_small_16_bg.webp";
            case 16: return "weather_quick_card_middle_snow_noon_small_16_bg.webp";
            case 18: case 19: case 20: case 21: case 23: return "weather_quick_card_sand_dust_small_16_bg.webp";
            case 22: return "weather_quick_card_hail_noon_small_16_bg.webp";
            case 24: return phase==NIGHT?"weather_quick_card_smog_night_small_16_bg.webp":"weather_quick_card_smog_small_16_bg.webp";
            default: return null;
        }
    }

    private static String sunPhase(int phase) {
        switch(phase) {
            case DAWN:return "dawn";
            case MORNING:return "morning";
            case AFTERNOON:return "af";
            case DUSK:return "nightfall";
            case NIGHT:return "night";
            default:return "noon";
        }
    }
    private static String cloudPhase(int phase) {
        switch(phase) {
            case DAWN:case MORNING:return "morning";
            case AFTERNOON:return "af";
            case DUSK:return "nightfall";
            case NIGHT:return "night";
            default:return "noon";
        }
    }
    private static String overcastPhase(int phase) {
        switch(phase) {
            case DAWN:case MORNING:return "morning";
            case DUSK:return "nightfall";
            case NIGHT:return "night";
            default:return "noon";
        }
    }

    private static String icon(int type,boolean night) {
        switch(type) {
            case 0:return night?"card_sunny_night.webp":"card_sunny.webp";
            case 1:return night?"card_cloudy_night.webp":"card_cloudy.webp";
            case 2:return "card_overcast.webp";
            case 3:return "card_fog.webp";
            case 4:case 5:case 6:return "card_storm.webp";
            case 7:return "card_thunder_storm.webp";
            case 8:return "card_thunder_shower.webp";
            case 9:return "card_heavy_rain.webp";
            case 10:return "card_middle_rain.webp";
            case 11:return "card_light_rain.webp";
            case 12:return "card_sleet.webp";
            case 13:case 15:return "card_heavy_snow.webp";
            case 14:case 17:return "card_light_snow.webp";
            case 16:return "card_middle_snow.webp";
            case 18:case 19:case 20:return "card_sand_dust.webp";
            case 21:case 23:return "card_blowing_dust.webp";
            case 22:return "card_hail.webp";
            case 24:return "card_smog.webp";
            case 25:return "card_freezing_rain.webp";
            default:return null;
        }
    }

    private static int phase(WeatherWidgetData data,long time) {
        Calendar local=Calendar.getInstance(data!=null&&!data.zoneId.isEmpty()
                ?TimeZone.getTimeZone(data.zoneId):TimeZone.getDefault());
        local.setTimeInMillis(time);
        int minute=local.get(Calendar.HOUR_OF_DAY)*60+local.get(Calendar.MINUTE);
        int rise=data!=null&&data.sunriseMinute>=0?data.sunriseMinute:360;
        int set=data!=null&&data.sunsetMinute>rise?data.sunsetMinute:1080;
        if(minute<rise-35||minute>=set+35)return NIGHT;
        if(minute<rise+45)return DAWN;
        if(minute<rise+240)return MORNING;
        if(minute<set-180)return NOON;
        if(minute<set-45)return AFTERNOON;
        return DUSK;
    }

    private static String summary(WeatherWidgetData data,long time) {
        if(data==null)return "--";
        if(data.hasRain(time))return data.rainDescription;
        String weather=data.description.trim();
        String range=!data.high.isEmpty()&&!data.low.isEmpty()
                ?data.high+"° / "+data.low+"°":"";
        if(!weather.isEmpty()&&!range.isEmpty())return weather+"  "+range;
        if(!weather.isEmpty())return weather;
        return range.isEmpty()?"--":range;
    }

    private void drawAsset(Canvas c,String name,float x,float y,float w,float h) {
        try(InputStream in=context.getAssets().open(ASSETS+name)) {
            Bitmap bitmap=BitmapFactory.decodeStream(in);
            if(bitmap!=null) {
                paint.setColor(Color.WHITE);paint.setAlpha(255);paint.setShader(null);
                c.drawBitmap(bitmap,null,new RectF(x,y,x+w,y+h),paint);
                bitmap.recycle();
            }
        } catch(Exception ignored) { /* Missing art leaves the gradient and real values visible. */ }
    }

    private void marker(Canvas c,float x,float y) {
        paint.setColor(Color.WHITE);paint.setShader(null);
        Path pin=new Path();
        pin.moveTo(x,y+6.3f);
        pin.cubicTo(x-1.5f,y+4.5f,x-5.3f,y+0.5f,x-5.3f,y-2.1f);
        pin.cubicTo(x-5.3f,y-5.1f,x-3f,y-7.4f,x,y-7.4f);
        pin.cubicTo(x+3f,y-7.4f,x+5.3f,y-5.1f,x+5.3f,y-2.1f);
        pin.cubicTo(x+5.3f,y+0.5f,x+1.5f,y+4.5f,x,y+6.3f);
        pin.close();pin.addCircle(x,y-2,1.65f,Path.Direction.CCW);
        pin.setFillType(Path.FillType.EVEN_ODD);c.drawPath(pin,paint);
    }

    private void label(Canvas c,String value,float x,float baseline,float size,float maxWidth,Typeface face) {
        label(c,value,x,baseline,size,maxWidth,face,Paint.Align.LEFT);
    }

    private void label(Canvas c,String value,float x,float baseline,float size,float maxWidth,Typeface face,
            Paint.Align align) {
        text.setColor(Color.WHITE);text.setTypeface(face);text.setTextSize(size);text.setTextAlign(align);
        text.setShadowLayer(0,0,0,Color.TRANSPARENT);
        String shown=TextUtils.ellipsize(value,text,maxWidth,TextUtils.TruncateAt.END).toString();
        c.drawText(shown,x,baseline,text);
    }
}
