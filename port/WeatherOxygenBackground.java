package dev.weather.oneplus;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Calendar;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONObject;

/** Original ColorOS 16 sky composition, independent of Xiaomi MAML duration.
 * Rule data is extracted by executing original WeatherCardColor/BackgroundRes16
 * method bodies. Original binding disables ThemeColor, so the 16 sky arrays are
 * identical in light/dark themes; the separate OOS template owns theme artwork.
 */
final class WeatherOxygenBackground {
    static final int MORNING=0, NOON=1, NIGHTFALL=2, DAWN=3, AFTERNOON=4, EVENING=5, NIGHT=259;
    private static final String ASSETS="oxygen_widget/";
    private final Context context;
    private final JSONObject rules;

    WeatherOxygenBackground(Context context) {
        this.context=context;
        try(InputStream in=context.getAssets().open(ASSETS+"background_rules.json")) {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            byte[] buffer=new byte[8192];int length;
            while((length=in.read(buffer))!=-1)bytes.write(buffer,0,length);
            rules=new JSONObject(new String(bytes.toByteArray(),java.nio.charset.StandardCharsets.UTF_8)).getJSONObject("rules");
        }catch(Exception error){throw new IllegalStateException("Missing original Oxygen sky rules",error);}
    }

    void draw(Canvas canvas,WeatherWidgetData data,JSONObject details,long time,int style,float width,float height) {
        int period=period(data,time),type=data==null?-1:data.weatherType;
        boolean unusual=unusual(data,details,period);
        int size=cardSize(style);
        try {
            // Original no-data 16 card uses cloudy/noon. This only selects art;
            // the calling layout retains -- for absent weather observations.
            if(type<0||type>25){type=1;period=NOON;unusual=false;}
            JSONObject rule=rules.getJSONObject(type+":"+period+":"+unusual);
            JSONObject image=rule.getJSONObject("images").getJSONObject(Integer.toString(size));
            int[] colors=colors(rule);float[] offsets=offsets(rule);
            String name=image.getString("asset");
            Bitmap source;
            try(InputStream in=context.getAssets().open(ASSETS+"backgrounds/"+name)){
                source=BitmapFactory.decodeStream(in);
            }
            if(source==null)throw new IllegalStateException("Cannot decode original Oxygen background "+name);
            Bitmap combined=null;
            try {
                combined=compose(source,colors,offsets,image.getString("mode"),image.getInt("alpha"));
                Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
                paint.setColor(Color.WHITE);
                // Original CardBackgroundImageView scales to fill, anchored at
                // the top right. Keep the sky proportional on responsive hosts.
                if(style==8){
                    // Standard TodayWeatherWidget uses ImageView.fitXY.
                    canvas.drawBitmap(combined,null,new RectF(0,0,width,height),paint);
                }else{
                    float scale=Math.max(width/combined.getWidth(),height/combined.getHeight());
                    float drawnWidth=combined.getWidth()*scale,drawnHeight=combined.getHeight()*scale;
                    canvas.drawBitmap(combined,null,new RectF(width-drawnWidth,0,width,drawnHeight),paint);
                }
            }finally{source.recycle();if(combined!=null)combined.recycle();}
        }catch(Exception error){throw new IllegalStateException("Cannot render original Oxygen sky",error);}
    }

    static Bitmap compose(Bitmap source,int[] colors,float[] offsets,String mode,int alpha) {
        int width=source.getWidth(),height=source.getHeight();
        Bitmap out=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(out);
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        int layer=canvas.saveLayer(null,null);
        // TodayWeatherWidgetBgCreator.handleBitmap keeps the original mode
        // active for BOTH draws: first the art, then the bottom-to-top gradient.
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.valueOf(mode)));
        if(alpha>=0)paint.setAlpha(alpha);
        canvas.drawBitmap(source,0,0,paint);
        paint.setAlpha(255);paint.setDither(true);
        paint.setShader(new LinearGradient(0,height,0,0,colors,offsets,Shader.TileMode.CLAMP));
        canvas.drawRect(0,0,width,height,paint);
        paint.setShader(null);paint.setXfermode(null);
        canvas.restoreToCount(layer);
        return out;
    }

    static int[] colors(JSONObject rule) throws org.json.JSONException {
        JSONArray input=rule.getJSONArray("colors");int[] values=new int[input.length()];
        for(int i=0;i<values.length;i++)values[i]=input.getInt(i);
        return values;
    }
    static float[] offsets(JSONObject rule) throws org.json.JSONException {
        JSONArray input=rule.getJSONArray("offsets");float[] values=new float[input.length()];
        for(int i=0;i<values.length;i++)values[i]=(float)input.getDouble(i);
        return values;
    }

    static int cardSize(int style) {
        // Original beans: Small=0, Middle=1, Big=2, Dragonfly=3.
        // Seedling/RPK adaptive layouts use the original small sky treatment.
        return style==10?1:style==11?2:style==12?3:0;
    }

    static int period(WeatherWidgetData data,long time) {
        TimeZone zone=TimeZone.getDefault();
        if(data!=null&&!data.zoneId.isEmpty()){
            TimeZone requested=TimeZone.getTimeZone(data.zoneId);
            if(!"GMT".equals(requested.getID())||"GMT".equals(data.zoneId))zone=requested;
        }
        Calendar local=Calendar.getInstance(zone);local.setTimeInMillis(time);
        int rise=data==null?-1:data.sunriseMinute,set=data==null?-1:data.sunsetMinute;
        if(rise<0||set<0){rise=360;set=1080;}
        return period(local.get(Calendar.HOUR_OF_DAY)*60+local.get(Calendar.MINUTE),rise,set);
    }

    static int period(int minute,int rise,int set) {
        // com.oplus.weather.main.utils.Period.getPeriod(int,int,int).
        if(rise==set)return NOON;
        if(set<rise)return minute<set||minute>=rise?NOON:NIGHT;
        if(set-rise<=120)return minute<rise||minute>=set?NIGHT:NOON;
        if(minute>=rise-30&&minute<rise)return DAWN;
        int morning=rise+Math.round((set-rise)*.1f),noon=rise+Math.round((set-rise)*.5f);
        int nightfall=set-Math.round((set-rise)*.1f);
        if(minute>=rise&&minute<morning)return MORNING;
        if(minute>=morning&&minute<noon)return NOON;
        if(minute>=noon&&minute<nightfall)return AFTERNOON;
        if(minute>=nightfall&&minute<set)return NIGHTFALL;
        return minute>=set&&minute<set+30?EVENING:NIGHT;
    }

    static boolean unusual(WeatherWidgetData data,JSONObject details,int period) {
        // Original UnusualWeatherUtils only enables cloudy sunrise/sunset with
        // humidity 60..80, PM2.5 20..100 (CN), wind FORCE 1..3. Wind speed and AQI
        // cannot substitute for those measurements. No cross-city effect cache.
        if(data==null||data.weatherType!=1||details==null
                ||(period!=MORNING&&period!=NIGHTFALL)||data.sunriseMinute<0
                ||data.sunsetMinute<=data.sunriseMinute)return false;
        int humidity=integer(details.optString("humidity",""),true);
        int wind=integer(details.optString("windPower",""),false);
        String country=details.optString("countryCode","");
        int pm25=integer(details.optString("pm25",""),false);
        if(country.isEmpty()||humidity<60||humidity>80||wind<1||wind>3)return false;
        return !"CN".equalsIgnoreCase(country)||(pm25>=20&&pm25<=100);
    }

    private static int integer(String input,boolean percentage) {
        String value=input.trim();if(percentage&&value.endsWith("%"))value=value.substring(0,value.length()-1).trim();
        try{return Integer.parseInt(value);}catch(NumberFormatException ignored){return -1;}
    }
}
