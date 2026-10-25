package dev.weather.oneplus;

import android.content.Context;
import android.content.ContextWrapper;
import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** Temporary test APK: ordinary application UID, no root, no user-weather writes. */
public final class OxygenWidgetRenderProbe extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        android.widget.TextView text=new android.widget.TextView(this);
        text.setText("正在检查一加天气组件预览…");setContentView(text);
        new Thread(()->{
            try {
                renderAll(getIntent().getStringExtra("targets"));
                runOnUiThread(()->{text.setText("组件绘制检查完成");finish();});
            } catch(Throwable error) {
                Log.e("OxygenWidgetProbe","FAIL",error);
                runOnUiThread(()->text.setText(error.toString()));
            }
        },"widget-render-probe").start();
    }
    private void renderAll(String targets) throws Exception {
        Context context=themeContext(false);
        File output = new File(getFilesDir(),"renders");
        if (!output.isDirectory() && !output.mkdirs()) throw new IllegalStateException("Output directory unavailable");
        long now = 1792555200000L; // Preview fixture only, never a provider observation.
        JSONObject fixture = new JSONObject().put("cityId", "preview-only").put("cityName", "示例城市")
            .put("temperature", "26").put("high", "28").put("low", "18").put("unit", "C")
            .put("description", "晴").put("weatherType", 0).put("aqi", 35)
            .put("sunriseMinute", 370).put("sunsetMinute", 1070).put("zoneId", "Asia/Shanghai")
            .put("observedAtMs", now);
        WeatherWidgetData data = new WeatherWidgetData(fixture);
        JSONArray hourly = new JSONArray(), daily = new JSONArray();
        for (int i=0; i<8; i++) {
            hourly.put(new JSONObject().put("timeMs", now+i*3600000L).put("temp", ""+(26-i/2)).put("weatherType", i%2));
            daily.put(new JSONObject().put("timeMs", now+i*86400000L).put("high", ""+(28-i%3))
                .put("low", ""+(18+i%2)).put("weatherType", i%2));
        }
        JSONObject details = new JSONObject().put("hourly", hourly).put("daily", daily).put("uvIndex", "3")
            .put("humidity", "55%").put("feelsLike", "27°").put("wind", "东风 2级");
        WeatherOxygenWidgetRenderer renderer = new WeatherOxygenWidgetRenderer(context);
        int count = 0;
        for (String target : targets.split(",")) {
            String[] values=target.split(":");
            int style=Integer.parseInt(values[0]), width=Integer.parseInt(values[1]), height=Integer.parseInt(values[2]);
            for (int missing=0; missing<2; missing++) {
                Bitmap image=renderer.render(style, missing==0?data:null, missing==0?details:null, now, width, height);
                if (image.getWidth()!=width || image.getHeight()!=height) throw new AssertionError("Wrong output size");
                if (style>=17 && style<=21) {
                    int left=width,top=height,right=-1,bottom=-1;
                    int[] pixels=new int[width*height];image.getPixels(pixels,0,width,0,0,width,height);
                    for(int y=0;y<height;y++)for(int x=0;x<width;x++)if((pixels[y*width+x]>>>24)>0) {
                        left=Math.min(left,x);right=Math.max(right,x);top=Math.min(top,y);bottom=Math.max(bottom,y);
                    }
                    if(right<left || left<4 || top<4 || right>=width-4 || bottom>=height-4)
                        throw new AssertionError("Compact card lost its transparent gutter: "+style);
                    if(Math.abs((right-left)-(bottom-top))>2)
                        throw new AssertionError("Compact card is no longer square: "+style);
                }
                Bitmap independent=new WeatherOxygenWidgetRenderer(context).render(style,
                        missing==0?data:null, missing==0?details:null, now, width, height);
                try {
                    if (!image.sameAs(independent)) throw new AssertionError("Paint history changed style "+style);
                } finally { independent.recycle(); }
                if (missing==0) try(FileOutputStream file=new FileOutputStream(new File(output,"style-"+style+"-"+width+"x"+height+".png"))) {
                    if(!image.compress(Bitmap.CompressFormat.PNG,100,file))throw new AssertionError("PNG encode failed");
                }
                image.recycle();count++;
            }
        }
        // Verify OOS's five actual background branches using the same fixture only.
        for(String variant:new String[]{"light-evening","dark-day","dark-evening","light-night","dark-night"}){
            boolean dark=variant.startsWith("dark");
            java.util.Calendar clock=java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
            clock.setTimeInMillis(now);int minute=variant.endsWith("night")?22*60:variant.endsWith("evening")?1050:12*60;
            clock.set(java.util.Calendar.HOUR_OF_DAY,minute/60);clock.set(java.util.Calendar.MINUTE,minute%60);
            Context themed=themeContext(dark);long time=clock.getTimeInMillis();
            Bitmap image=new WeatherOxygenWidgetRenderer(themed).render(24,data,details,time,450,450);
            Bitmap check=new WeatherOxygenWidgetRenderer(themed).render(24,data,details,time,450,450);
            if(!image.sameAs(check))throw new AssertionError("OOS theme output mismatch");check.recycle();
            try(FileOutputStream file=new FileOutputStream(new File(output,"oos-"+variant+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,file);}
            image.recycle();count++;
        }
        String result="PASS: "+count+" cases / "+(count*2)+" production renders; empty data, isolated paint state and OOS themes";
        try(FileOutputStream file=new FileOutputStream(new File(output,"result.txt"))) {
            file.write(result.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        Log.i("OxygenWidgetProbe",result);
    }
    private Context themeContext(boolean dark){
        android.content.res.Configuration config=new android.content.res.Configuration(getResources().getConfiguration());
        config.uiMode=(config.uiMode&~android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                |(dark?android.content.res.Configuration.UI_MODE_NIGHT_YES:android.content.res.Configuration.UI_MODE_NIGHT_NO);
        return new ContextWrapper(createConfigurationContext(config)){
            @Override public Context getApplicationContext(){return this;}
        };
    }
}
