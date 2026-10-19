package dev.weather.oneplus;

import android.content.Context;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

/** Official Xiaomi endpoint used by both original Weather 17 Java and Weather 18 Dart. */
public final class XiaomiWeatherRefreshSource implements WeatherRefreshSource {
    private static final String ENDPOINT="https://weatherapi.market.xiaomi.com/wtr-v3/weather/all";
    private static final int MAX_BYTES=2*1024*1024;
    public XiaomiWeatherRefreshSource() {}

    @Override public Result fetch(Context context, WeatherWidgetData previous,
            CancellationSignal cancellation) throws Exception {
        cancellation.throwIfCanceled();
        if (!WeatherRefresh.networkAllowed(context)) return Result.blocked("network_consent_required");
        WeatherDatabaseBridge.City city=WeatherDatabaseBridge.firstCity(context);
        if (city==null) return Result.blocked("city_selection_required");
        Result result=fetchCity(context,city,cancellation);
        if(result.networkVerified && !city.sameSelection(WeatherDatabaseBridge.firstCity(context)))
            return Result.failed("selected_city_changed",true);
        return result;
    }

    /** Widget requests use their real saved identity, never the primary notification city. */
    Result fetchCity(Context context, WeatherDatabaseBridge.City city,
            CancellationSignal cancellation) throws Exception {
        return fetchCity(context,city,cancellation,Long.MAX_VALUE);
    }
    Result fetchCity(Context context, WeatherDatabaseBridge.City city,
            CancellationSignal cancellation,long deadlineElapsed) throws Exception {
        cancellation.throwIfCanceled();
        if (!WeatherRefresh.networkAllowed(context)) return Result.blocked("network_consent_required");
        if (city==null) return Result.blocked("city_selection_required");
        if (!city.valid()) return Result.blocked("selected_city_incomplete");
        if (!city.sameSelection(WeatherDatabaseBridge.cityById(context,city.id)))
            return Result.failed("selected_city_changed",true);
        String query="latitude="+encode(city.latitude)+"&longitude="+encode(city.longitude)
                +"&isLocated="+city.located+"&locationKey="+encode(city.locationKey)
                +"&days=15&appKey=weather20151024&sign=zUFJoAR2ZVrDy1vF3D07"
                +"&appVersion=18.0.0.27-R&isGlobal=false&locale="+encode(city.locale);
        long remaining=deadlineElapsed-SystemClock.elapsedRealtime();
        if(remaining<1000)return Result.failed("widget_job_budget",true);
        HttpsURLConnection connection=(HttpsURLConnection)new URL(ENDPOINT+"?"+query).openConnection();
        // Reserve time for subsequent cities when a secondary endpoint stalls.
        connection.setConnectTimeout((int)Math.min(deadlineElapsed==Long.MAX_VALUE?10000:5000,remaining));
        connection.setReadTimeout((int)Math.min(deadlineElapsed==Long.MAX_VALUE?15000:8000,remaining));
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept","application/json");
        connection.setRequestProperty("Accept-Encoding","gzip");
        connection.setRequestProperty("Cache-Control","no-cache");
        connection.setRequestProperty("User-Agent","XiaomiWeatherPort/18.0.0.27 (Android)");
        cancellation.setOnCancelListener(connection::disconnect);
        try {
            cancellation.throwIfCanceled();
            int status=connection.getResponseCode();
            if (status!=200) return Result.failed("weather_http_"+status,status==408 || status==429 || status>=500);
            String json;
            try (InputStream body=connection.getInputStream();
                 InputStream input="gzip".equalsIgnoreCase(connection.getContentEncoding())?new GZIPInputStream(body):body;
                 ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[8192]; int size;
                while ((size=input.read(buffer))!=-1) {
                    cancellation.throwIfCanceled();
                    if(SystemClock.elapsedRealtime()>=deadlineElapsed)
                        return Result.failed("widget_job_budget",true);
                    if (bytes.size()+size>MAX_BYTES) return Result.failed("weather_response_too_large",false);
                    bytes.write(buffer,0,size);
                }
                json=new String(bytes.toByteArray(),StandardCharsets.UTF_8);
            }
            cancellation.throwIfCanceled();
            if (!WeatherRefresh.networkAllowed(context)) return Result.blocked("network_consent_required");
            WeatherDatabaseBridge.City selected=WeatherDatabaseBridge.cityById(context,city.id);
            if (!city.sameSelection(selected)) return Result.failed("selected_city_changed",true);
            JSONObject response=new JSONObject(json);
            WeatherWidgetData observation=XiaomiWeatherParser.parse(response,
                    city.id,city.name,city.unit,city.locale);
            cancellation.throwIfCanceled();
            return Result.networkSuccess(observation,response);
        } catch (IOException error) {
            if (cancellation.isCanceled()) throw new OperationCanceledException();
            return Result.failed("weather_network_error",true);
        } finally {
            cancellation.setOnCancelListener(null);
            connection.disconnect();
        }
    }
    private static String encode(String value) throws Exception { return URLEncoder.encode(value,"UTF-8"); }
}
