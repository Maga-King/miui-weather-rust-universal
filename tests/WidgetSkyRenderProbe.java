package dev.weather.oneplus;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;

/** Disposable ordinary-UID instrumentation; renders synthetic data without opening Weather. */
public final class WidgetSkyRenderProbe extends Instrumentation {
    private int comparisons, renders, assets;
    private Context context;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            context = getTargetContext();
            runChecks();
            String report = "PASS: " + comparisons + " pixel comparisons / " + renders
                + " renders; Xiaomi sky phases unchanged, icon-only changes, all Oxygen styles and " + assets + " sky assets";
            try (FileOutputStream file = new FileOutputStream(new File(context.getFilesDir(), "result.txt"))) {
                file.write(report.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            result.putString("stream", report); finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL: " + error); finish(0, result);
        }
    }
    private WeatherWidgetData fixture(int type, boolean missing) throws Exception {
        return new WeatherWidgetData(new JSONObject().put("cityId", "fixture").put("cityName", "Test City")
            .put("temperature", "16").put("high", "18").put("low", "14").put("unit", "C")
            .put("description", "Weather").put("weatherType", type).put("aqi", 30)
            .put("sunriseMinute", missing ? -1 : 372).put("sunsetMinute", missing ? -1 : 1036)
            .put("zoneId", "GMT+08:00"));
    }
    private long time(int minute) {
        return OffsetDateTime.parse("2026-10-22T00:00:00+08:00").toInstant().toEpochMilli() + minute * 60000L;
    }
    private JSONObject details(WeatherWidgetData data) throws Exception {
        JSONArray hourly = new JSONArray(), daily = new JSONArray();
        for (int i=0; i<8; i++) {
            hourly.put(new JSONObject().put("timeMs",time(1020+i*60)).put("temp","16").put("weatherType",i%2));
            daily.put(new JSONObject().put("timeMs",time(720)+i*86400000L).put("high","18").put("low","14").put("weatherType",1));
        }
        return new JSONObject().put("cities",new JSONArray().put(data.toJson())).put("hourly",hourly).put("daily",daily)
            .put("humidity","55%").put("wind","2 m/s").put("feelsLike","16").put("uvIndex","3");
    }
    private void compare(Bitmap before, Bitmap after, boolean iconOnly) {
        renders += 2; comparisons++;
        try {
            int w=before.getWidth(), h=before.getHeight();
            if (w!=after.getWidth() || h!=after.getHeight()) throw new AssertionError("Dimensions changed");
            int[] a=new int[w*h], b=new int[w*h];
            before.getPixels(a,0,w,0,0,w,h);after.getPixels(b,0,w,0,0,w,h);
            for(int y=0;y<h;y++)for(int x=0;x<w;x++) if(a[y*w+x]!=b[y*w+x]) {
                // The basic card's only changed drawing operation is its 26x26 icon at (111,14).
                if(!iconOnly || x<221 || x>275 || y<27 || y>81)
                    throw new AssertionError("Changed non-icon pixel " + x + "," + y);
            }
        } finally { before.recycle();after.recycle(); }
    }
    private void runChecks() throws Exception {
        WeatherWidgetRenderer after=new WeatherWidgetRenderer(context);
        BeforeWeatherWidgetRenderer before=new BeforeWeatherWidgetRenderer(context);
        int[] phases={0,210,270,330,390,450,530,640,760,880,950,1010,1045,1070,1090,1130};
        Set<Integer> visited=new HashSet<>();Set<Integer> clearHashes=new HashSet<>();
        for(int type:new int[]{0,1,2,3,11,17,24,25})for(int minute:phases) {
            WeatherWidgetData data=fixture(type,false);
            visited.add(WeatherWidgetRenderer.duration(data,time(minute)));
            Bitmap a=Bitmap.createBitmap(300,300,Bitmap.Config.ARGB_8888), b=Bitmap.createBitmap(300,300,Bitmap.Config.ARGB_8888);
            before.drawSky(new Canvas(a),data,time(minute),300,300);
            after.drawSky(new Canvas(b),data,time(minute),300,300);
            if(type==0) {int[] pixels=new int[90000];b.getPixels(pixels,0,300,0,0,300,300);clearHashes.add(java.util.Arrays.hashCode(pixels));}
            compare(a,b,false);
        }
        if(visited.size()!=16 || clearHashes.size()<10) throw new AssertionError("Sky phases collapsed: "+visited.size()+"/"+clearHashes.size());
        for(int type=0;type<=25;type++)for(int minute:new int[]{300,372,720,1036,1152,1380}) {
            WeatherWidgetData data=fixture(type,false);
            compare(before.render(data,time(minute),300,300),after.render(data,time(minute),300,300),true);
        }
        WeatherCardWidgetRenderer cards=new WeatherCardWidgetRenderer(context);
        BeforeWeatherCardWidgetRenderer oldCards=new BeforeWeatherCardWidgetRenderer(context);
        for(int style=1;style<8;style++)for(int type:new int[]{0,1,3,11,17})for(int minute:new int[]{300,390,720,1040,1152}) {
            WeatherWidgetData data=fixture(type,false);JSONObject more=details(data);
            int w=style==1||style==2||style==5||style==6?688:300;
            int h=style==1||style==3?120:style==6?688:300;
            compare(oldCards.render(style,data,more,time(minute),w,h),cards.render(style,data,more,time(minute),w,h),false);
        }
        // Missing sun times must still leave readable actual weather and a background.
        for(int style=0;style<8;style++) {
            WeatherWidgetData data=fixture(1,true);
            Bitmap image=style==0?after.render(data,time(1380),300,300):cards.render(style,data,details(data),time(1380),300,300);
            if(image.getPixel(150,150)==0)throw new AssertionError("Missing sun blanked widget "+style);
            image.recycle();renders++;
        }
        java.lang.reflect.Method background=WeatherOxygenWidgetRenderer.class.getDeclaredMethod("background",int.class,int.class);
        background.setAccessible(true);
        for(int type=0;type<=25;type++)for(int phase=0;phase<6;phase++) {
            String name=(String)background.invoke(null,type,phase);
            if(name!=null)try(InputStream input=context.getAssets().open("oxygen_widget/backgrounds/"+name)) {assets++;}
        }
        WeatherOxygenWidgetRenderer oxygen=new WeatherOxygenWidgetRenderer(context);
        for(int style=8;style<25;style++)for(int minute:new int[]{330,420,720,1030,1380}) {
            WeatherWidgetData data=fixture(1,false);
            Bitmap image=oxygen.render(style,data,details(data),time(minute),300,300);
            if(image.getWidth()!=300||image.getHeight()!=300)throw new AssertionError("Oxygen size changed");
            image.recycle();renders++;
        }
    }
}
