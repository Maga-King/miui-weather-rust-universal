package dev.weather.oneplus;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.LruCache;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** Static Android renditions of the seven separately downloaded original MAML cards.
 * Original previews are picker-only; all live values come from the weather cache.
 */
final class WeatherCardWidgetRenderer {
    static final int BASE=0, DUAL=1, HOURLY=2, SMALL=3, CUSTOM=4, DAILY=5, COMPLETE=6, SUN=7;
    private final Context context;
    private final WeatherWidgetRenderer sky;
    private final TextPaint text=new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final Typeface medium;
    private final LruCache<String,Bitmap> images=new LruCache<String,Bitmap>(24);
    private int foreground=Color.WHITE;
    private long now;
    private WeatherWidgetData selected;

    WeatherCardWidgetRenderer(Context context) {
        this.context=context;
        sky=new WeatherWidgetRenderer(context);
        medium=WeatherWidgetFonts.face(context,380,500);
    }

    Bitmap render(int style,WeatherWidgetData data,JSONObject details,long time,int width,int height) {
        selected=data;now=time;
        if(details==null)details=new JSONObject();
        float w=style==DUAL?344:(style==HOURLY||style==DAILY||style==COMPLETE)?340:150;
        float h=style==SMALL?58:style==DUAL?60:style==COMPLETE?340:150;
        Bitmap result=Bitmap.createBitmap(Math.max(1,width),Math.max(1,height),Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(result);
        // A standard launcher's one-row cell is taller than the original MAML
        // strip. Keep the template's aspect ratio inside it instead of turning
        // the whole allocated cell into an oversized card.
        if(style==DUAL||style==SMALL) {
            float scale=Math.min(width/w,height/h);
            canvas.translate((width-w*scale)/2f,(height-h*scale)/2f);
            canvas.scale(scale,scale);
        } else canvas.scale(width/w,height/h);
        Path clipping=new Path();clipping.addRoundRect(new RectF(0,0,w,h),21.8f,21.8f,Path.Direction.CW);
        canvas.clipPath(clipping);
        foreground=Color.WHITE;
        if(style==DUAL)dual(canvas,data,details,w);
        else {
            sky.drawSky(canvas,data,time,w,h);
            if(style==SUN)sun(canvas,data);
            else if(style==SMALL)small(canvas,data,details,w);
            else if(style==CUSTOM)custom(canvas,data,details);
            else {
                header(canvas,data,w);
                if(style==DAILY)dailyColumns(canvas,details.optJSONArray("daily"),87);
                else hourly(canvas,details.optJSONArray("hourly"),style==COMPLETE?92:87);
                if(style==COMPLETE)dailyRows(canvas,details.optJSONArray("daily"),174);
            }
        }
        return result;
    }

    private void header(Canvas c,WeatherWidgetData d,float w) {
        label(c,d==null?"":d.cityName,16,12,15,w-72,Paint.Align.LEFT,255);
        label(c,temp(d),16,29,46,140,Paint.Align.LEFT,255);
        icon(c,d==null?-1:d.weatherType,w-39,11,24,night(d,now));
        label(c,d==null?"":d.description,w-16,40,13,w/2,Paint.Align.RIGHT,255);
        label(c,range(d),w-16,59,13,w/2,Paint.Align.RIGHT,245);
    }

    private void hourly(Canvas c,JSONArray values,float y) {
        for(int i=0;i<6;i++) {
            JSONObject item=at(values,i);float x=32+54*i;
            long time=item==null?0:item.optLong("timeMs",0);
            label(c,time>0?format(time,"HH:mm"):"--:--",x,y,11,49,Paint.Align.CENTER,180);
            if(item!=null)icon(c,item.optInt("weatherType",-1),x-10,y+17,20,night(selected,time));
            label(c,item==null?"--":degree(number(item,"temp")),x,y+36,13,49,Paint.Align.CENTER,255);
        }
    }

    private void dailyColumns(Canvas c,JSONArray values,float y) {
        for(int i=0;i<5;i++) {
            JSONObject item=at(values,i);float x=38+66*i;
            long time=item==null?0:item.optLong("timeMs",0);
            label(c,time>0?format(time,"EEE"):"--",x,y,11,58,Paint.Align.CENTER,180);
            if(item!=null)icon(c,item.optInt("weatherType",-1),x-11,y+16,22,false);
            label(c,item==null?"--/--":degree(number(item,"high"))+"/"+degree(number(item,"low")),x,y+36,11,60,Paint.Align.CENTER,255);
        }
    }

    private void dailyRows(Canvas c,JSONArray values,float y) {
        double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;
        for(int i=0;i<5;i++) {
            JSONObject item=at(values,i);
            if(item!=null)try { min=Math.min(min,Double.parseDouble(number(item,"low")));max=Math.max(max,Double.parseDouble(number(item,"high"))); }catch(Exception ignored){}
        }
        for(int i=0;i<5;i++) {
            JSONObject item=at(values,i);float top=y+29*i;
            long time=item==null?0:item.optLong("timeMs",0);
            label(c,time>0?format(time,"EEE"):"--",18,top,13,48,Paint.Align.LEFT,230);
            label(c,item==null?"":item.optString("description",""),66,top,12,49,Paint.Align.LEFT,190);
            if(item!=null)icon(c,item.optInt("weatherType",-1),119,top-1,20,false);
            label(c,item==null?"--":degree(number(item,"low")),176,top,13,35,Paint.Align.LEFT,205);
            label(c,item==null?"--":degree(number(item,"high")),321,top,13,35,Paint.Align.RIGHT,255);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(3);paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(0x3380b5ff);c.drawLine(213,top+9,289,top+9,paint);
            if(item!=null&&Double.isFinite(min)&&Double.isFinite(max))try {
                double low=Double.parseDouble(number(item,"low")),high=Double.parseDouble(number(item,"high"));
                if(high>=low) {float span=(float)Math.max(1,max-min);paint.setColor(0xff7deaff);
                    c.drawLine(213+(float)(low-min)/span*76,top+9,213+(float)(high-min)/span*76,top+9,paint);}
            }catch(Exception ignored){}
            paint.setStyle(Paint.Style.FILL);paint.setStrokeCap(Paint.Cap.BUTT);
        }
    }

    private void small(Canvas c,WeatherWidgetData d,JSONObject more,float w) {
        stripLabel(c,d==null?"":d.cityName,14,10,11,70,Paint.Align.LEFT,255,450,600);
        String temperature=d==null?"--":d.temperature;
        float temperatureWidth=stripLabel(c,temperature,14,23,20.5f,72,Paint.Align.LEFT,255,450,600);
        float degreeWidth=stripLabel(c,"\u00b0",14+temperatureWidth,23,20,20,Paint.Align.LEFT,255,520,800);
        // The original animated icon frame includes transparent padding; this
        // tight sprite occupies the same visible area at the upper right.
        icon(c,d==null?-1:d.weatherType,w-31,10,18,night(d,now));
        int panel=more.optInt("panel",0);
        String heading,value;
        if(panel==1) {
            heading=Locale.getDefault().getLanguage().equals("zh")?"\u6e7f\u5ea6":"Humidity";
            value=missing(more.optString("humidity",""));
        } else if(panel==2) {
            heading="";value=range(d);
        } else if(panel==3) {
            heading=Locale.getDefault().getLanguage().equals("zh")?"\u4f53\u611f":"Feels like";
            value=degree(missing(more.optString("feelsLike","")));
        } else {
            heading=d==null||d.aqi<0?"":stripAqi(d.aqi);
            value=d==null||d.aqi<0?"--":String.valueOf(d.aqi);
        }
        float valueWidth=stripMeasure((heading.isEmpty()?"":" ")+value,13,330,400);
        stripLabel(c,(heading.isEmpty()?"":" ")+value,w-13,30,13,w-60,Paint.Align.RIGHT,255,330,400);
        float headingWidth=Math.min(55,Math.max(0,w-13-valueWidth-(14+temperatureWidth+degreeWidth+4)));
        if(!heading.isEmpty())stripLabel(c,heading,w-13-valueWidth,31,12,headingWidth,Paint.Align.RIGHT,255,330,400);
    }

    private void custom(Canvas c,WeatherWidgetData d,JSONObject more) {
        label(c,d==null?"":d.cityName,15,14,14,90,Paint.Align.LEFT,255);
        label(c,temp(d),15,35,42,82,Paint.Align.LEFT,245);
        icon(c,d==null?-1:d.weatherType,113,15,24,night(d,now));
        label(c,d==null?"":d.description,136,44,13,58,Paint.Align.RIGHT,245);
        label(c,range(d),136,63,13,65,Paint.Align.RIGHT,245);
        int panel=more.optInt("panel",0);
        if(panel==1||panel==2) {
            JSONArray values=more.optJSONArray(panel==1?"hourly":"daily");
            for(int i=0;i<3;i++) {
                JSONObject item=at(values,i);float x=25+50*i;
                long time=item==null?0:item.optLong("timeMs",0);
                label(c,time>0?format(time,panel==1?"HH:mm":"EEE"):"--",x,97,10,47,Paint.Align.CENTER,180);
                if(item!=null)icon(c,item.optInt("weatherType",-1),x-8,113,16,panel==1&&night(d,time));
                label(c,item==null?"--":panel==1?degree(number(item,"temp")):number(item,"high")+"/"+number(item,"low"),x,132,10,47,Paint.Align.CENTER,255);
            }
            return;
        }
        if(panel==3) {
            label(c,context.getString(resource("weather_widget_sunrise")),38,101,11,65,Paint.Align.CENTER,180);
            label(c,context.getString(resource("weather_widget_sunset")),112,101,11,65,Paint.Align.CENTER,180);
            label(c,d!=null&&d.sunriseMinute>=0?clock(d.sunriseMinute):"--:--",38,121,18,66,Paint.Align.CENTER,255);
            label(c,d!=null&&d.sunsetMinute>=0?clock(d.sunsetMinute):"--:--",112,121,18,66,Paint.Align.CENTER,255);
            return;
        }
        label(c,"AQI",25,99,10,36,Paint.Align.CENTER,180);
        image(c,"others/humidity.png",69,98,12,15,180);
        image(c,"others/wind.png",116,98,15,15,180);
        label(c,d==null||d.aqi<0?"--":String.valueOf(d.aqi),25,117,16,40,Paint.Align.CENTER,255);
        fitLabel(c,missing(more.optString("humidity","")),75,117,16,11,44,Paint.Align.CENTER,255);
        windLabel(c,more.optString("wind",""),123);
    }

    private void dual(Canvas c,WeatherWidgetData d,JSONObject more,float w) {
        boolean dark=(context.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        foreground=dark?0xfff4f4f4:0xff171717;c.drawColor(dark?0xff252525:0xfffafafa);
        JSONArray cities=more.optJSONArray("cities");
        for(int i=0;i<2;i++) {
            WeatherWidgetData city=i==0?d:null;
            String desired=more.optString("secondCityId","");
            if(i==1&&!desired.isEmpty()&&cities!=null)for(int n=0;n<cities.length();n++)try {
                WeatherWidgetData candidate=new WeatherWidgetData(cities.getJSONObject(n));
                if(desired.equals(candidate.cityId)) {city=candidate;break;}
            }catch(Exception ignored){}
            float cell=w/2,x=i*cell,temperatureX=x+10;
            // Original MAML centers the number at y=height/2 and positions its
            // separately weighted degree and city from measured glyph widths.
            String temperature=city==null?"-":city.temperature;
            float iconX=x+cell-56;
            float degreeAdvance=stripMeasure("\u00b0",26,630,900);
            float naturalWidth=stripMeasure(temperature,39,380,500);
            // Preserve original sizing for ordinary temperatures; reserve the
            // adjacent forecast column for negative/three-digit values too.
            float numberSize=city==null?39:Math.min(39,39*Math.max(1,iconX-temperatureX-degreeAdvance-4-40)/Math.max(1,naturalWidth));
            text.setTypeface(WeatherWidgetFonts.face(context,380,500));text.setTextSize(numberSize);
            float baseline=30-(text.ascent()+text.descent())/2;
            float numberTop=baseline+text.ascent();
            float numberWidth=stripLabel(c,temperature,temperatureX,numberTop,numberSize,100,Paint.Align.LEFT,255,380,500);
            float degreeWidth=stripLabel(c,"\u00b0",temperatureX+numberWidth,8.5f,26,25,Paint.Align.LEFT,255,630,900);
            if(city!=null) {
                float infoX=temperatureX+numberWidth+degreeWidth+4;
                float available=Math.max(0,iconX-infoX);
                stripLabel(c,city.cityName,infoX,13.5f,13,available,Paint.Align.LEFT,255,450,600);
                stripLabel(c,range(city),infoX,32.5f,12,available,Paint.Align.LEFT,153,380,500);
                icon(c,city.weatherType,iconX+6,12,36,night(city,now));
            } else {
                // Explicit original show_c2_empty artwork: '-' + degree and a
                // sunny placeholder icon, not a claimed second observation.
                icon(c,0,iconX+6,12,36,false);
            }
        }
        paint.setColor(dark?0x1affffff:0x1a000000);c.drawRect(w/2-0.375f,20,w/2+0.375f,40,paint);
    }

    private String stripAqi(int aqi) {
        String level=aqi<=50?"good":aqi<=100?"moderate":aqi<=150?"pollution":aqi<=200?"unhealthy":aqi<=300?"dangerous":"hazardous";
        return context.getString(resource("weather_widget_aqi_"+level));
    }
    private float stripMeasure(String value,float size,int axis,int weight) {
        text.setTypeface(WeatherWidgetFonts.face(context,axis,weight));text.setTextSize(size);
        return text.measureText(value==null?"":value);
    }
    private float stripLabel(Canvas c,String value,float x,float y,float size,float max,Paint.Align align,int alpha,int axis,int weight) {
        text.setTypeface(WeatherWidgetFonts.face(context,axis,weight));text.setTextSize(size);
        text.setColor(foreground);text.setAlpha(alpha);text.setTextAlign(align);
        String visible=TextUtils.ellipsize(value==null?"":value,text,Math.max(0,max),TextUtils.TruncateAt.END).toString();
        c.drawText(visible,x,y-text.ascent(),text);
        return text.measureText(visible);
    }

    private void sun(Canvas c,WeatherWidgetData d) {
        Calendar local=Calendar.getInstance(zone());local.setTimeInMillis(now);
        int minute=local.get(Calendar.HOUR_OF_DAY)*60+local.get(Calendar.MINUTE);
        boolean known=d!=null&&d.sunriseMinute>=0&&d.sunsetMinute>d.sunriseMinute;
        boolean day=known&&minute>=d.sunriseMinute&&minute<d.sunsetMinute;
        // Original fallback frame and original curve sprites replace PAG animation.
        image(c,"sun/frames/"+(day?"sunset":"sunrise")+"/f_0.webp",0,0,150,150,255);
        String phase=day?"day":"night";
        image(c,"sun/curve/"+phase+"/top.png",5,77,140,52.85f,255);
        image(c,"sun/curve/"+phase+"/bottom.png",-3,105,156,52.85f,90);
        String event=context.getString(resource(day?"weather_widget_sunset":"weather_widget_sunrise"));
        label(c,event,14,14,14,122,Paint.Align.LEFT,255);
        if(known) {
            int next=day?d.sunsetMinute:d.sunriseMinute;
            int remaining=(next-minute+1440)%1440;
            String value=remaining>=60?context.getString(resource("weather_widget_hours_remaining"),remaining/60)
                :context.getString(resource("weather_widget_minutes_remaining"),remaining);
            label(c,value,14,33,19,122,Paint.Align.LEFT,255);
            float progress=day?(minute-d.sunriseMinute)/(float)(d.sunsetMinute-d.sunriseMinute)
                :((minute-d.sunsetMinute+1440)%1440)/(float)(1440-d.sunsetMinute+d.sunriseMinute);
            progress=Math.max(0,Math.min(1,progress));
            image(c,"sun/sun.png",5+140*progress-7,108-(float)Math.sin(progress*Math.PI)*42-7,14,14,255);
        } else label(c,"--",14,35,19,122,Paint.Align.LEFT,255);
        label(c,known?clock(d.sunriseMinute):"--:--",14,124,13,55,Paint.Align.LEFT,255);
        label(c,known?clock(d.sunsetMinute):"--:--",136,124,13,55,Paint.Align.RIGHT,255);
    }

    private void icon(Canvas c,int type,float x,float y,float size,boolean night) {
        if(type<0||type>25)return;
        image(c,"weather/weather_"+WeatherWidgetRenderer.icon(type)+"/"+(night?"night":"day")+".png",x,y,size,size,255);
    }
    private void image(Canvas c,String name,float x,float y,float w,float h,int alpha) {
        Bitmap bitmap=images.get(name);
        if(bitmap==null)try(InputStream in=context.getAssets().open("weather_cards/"+name)) {
            bitmap=BitmapFactory.decodeStream(in);if(bitmap!=null)images.put(name,bitmap);
        }catch(Exception ignored){}
        if(bitmap!=null){paint.setAlpha(alpha);c.drawBitmap(bitmap,null,new RectF(x,y,x+w,y+h),paint);paint.setAlpha(255);}
    }
    private void windLabel(Canvas c,String value,float x) {
        String wind=missing(value).trim();
        // The cache may include a heading followed by the measured speed.
        int separator=wind.lastIndexOf('\u00b7');
        if(separator>=0)wind=wind.substring(separator+1).trim();
        java.util.regex.Matcher measured=java.util.regex.Pattern.compile(
            "^(-?\\d+(?:\\.\\d+)?)\\s*(km/h|m/s|mph|kmh|kph|\u516c\u91cc/\u5c0f\u65f6|\u5343\u7c73/\u5c0f\u65f6|\u7c73/\u79d2|\u7ea7)$",
            java.util.regex.Pattern.CASE_INSENSITIVE).matcher(wind);
        if(measured.matches()) {
            String unit=measured.group(2);
            if(unit.equals("\u516c\u91cc/\u5c0f\u65f6")||unit.equals("\u5343\u7c73/\u5c0f\u65f6")||unit.equalsIgnoreCase("kmh")||unit.equalsIgnoreCase("kph"))unit="km/h";
            if(unit.equals("\u7c73/\u79d2"))unit="m/s";
            fitLabel(c,measured.group(1),x,115,16,11,40,Paint.Align.CENTER,255);
            label(c,unit,x,133,7.5f,40,Paint.Align.CENTER,210);
        } else fitLabel(c,wind,x,119,15,7,40,Paint.Align.CENTER,255);
    }
    private void fitLabel(Canvas c,String value,float x,float y,float size,float minimum,float max,Paint.Align align,int alpha) {
        text.setTypeface(medium);text.setTextSize(size);
        float measured=text.measureText(value==null?"":value);
        if(measured>max)size=Math.max(minimum,size*max/measured);
        label(c,value,x,y,size,max,align,alpha);
    }
    private void label(Canvas c,String value,float x,float y,float size,float max,Paint.Align align,int alpha) {
        text.setTypeface(medium);text.setTextSize(size);text.setColor(foreground);text.setAlpha(alpha);text.setTextAlign(align);
        c.drawText(TextUtils.ellipsize(value==null?"":value,text,max,TextUtils.TruncateAt.END).toString(),x,y-text.ascent(),text);
    }
    private int resource(String name) {return context.getResources().getIdentifier(name,"string",context.getPackageName());}
    private TimeZone zone() {return selected!=null&&!selected.zoneId.isEmpty()?TimeZone.getTimeZone(selected.zoneId):TimeZone.getDefault();}
    private String format(long time,String pattern) {SimpleDateFormat f=new SimpleDateFormat(pattern,Locale.getDefault());f.setTimeZone(zone());return f.format(time);}
    private static String clock(int minute) {return String.format(Locale.getDefault(),"%02d:%02d",minute/60,minute%60);}
    private static boolean night(WeatherWidgetData d,long time) {
        return WeatherWidgetDayNight.night(d,time);
    }
    private static JSONObject at(JSONArray array,int index) {return array==null?null:array.optJSONObject(index);}
    private static String number(JSONObject o,String key) {String s=o.optString(key,"");return s.matches("-?\\d{1,3}(?:\\.\\d)?")?s:"--";}
    private static String degree(String value) {return "--".equals(value)?value:value+"\u00b0";}
    private static String missing(String value) {return value.isEmpty()?"--":value;}
    private static String temp(WeatherWidgetData d) {return d==null?"--":d.temperature+"\u00b0";}
    private static String range(WeatherWidgetData d) {return d==null?"--/--":degree(missing(d.high))+"/"+degree(missing(d.low));}
}
