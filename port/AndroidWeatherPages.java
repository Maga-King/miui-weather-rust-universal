package dev.weather.oneplus;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

/** URL navigation supplied by the original weather UI; never starts a browser app. */
public final class AndroidWeatherPages {
    public static final Object UNHANDLED = new Object();
    // Weather17's own fallback when the Mi Video package is absent (Z1.C0373l).
    public static final String WEATHER_VIDEO_FALLBACK = "https://www.weather.com.cn/video/";
    private final Activity activity;

    public AndroidWeatherPages(Activity activity) { this.activity = activity; }

    public Object handleCall(String channel, String method, Object arguments) {
        if (!"weather_method_channel".equals(channel)) return UNHANDLED;
        if ("start_rain_map_activity".equals(method) || "start_rain_map_activity_os4".equals(method)
                || "start_aqi_map_activity".equals(method) || "start_aqi_map_activity_os4".equals(method)) {
            try { WeatherPagesRuntime.open(activity, method, arguments); }
            catch (Exception error) { throw new IllegalStateException("Cannot open original weather page", error); }
            return null;
        }
        if ("start_weather_forecast_activity".equals(method)) {
            String supplied = arguments instanceof String ? (String) arguments : "";
            String url = webUrl(supplied);
            if (url == null && isWeatherVideo(supplied)) url = WEATHER_VIDEO_FALLBACK;
            if (url == null) return Integer.valueOf(-1);
            openUrl(url, "天气预报");
            // The original Dart expects int?, and a negative value opens Mi Market.
            return Integer.valueOf(0);
        }
        return UNHANDLED;
    }

    /** Host intent adapter may call this before resolving an ACTION_VIEW externally. */
    public boolean openIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return false;
        String supplied = intent.getDataString();
        String url = webUrl(supplied);
        if (url == null) url = webUrl(intent.getStringExtra("browser_fallback_url"));
        if (url == null && isWeatherVideo(supplied)) url = WEATHER_VIDEO_FALLBACK;
        if (url == null) return false;
        openUrl(url, isWeatherVideo(supplied) ? "天气预报" : "");
        return true;
    }

    public void openUrl(String url, String title) {
        String valid = webUrl(url);
        if (valid == null) throw new IllegalArgumentException("Expected HTTP(S) page URL");
        Intent intent = new Intent(activity, WeatherWebActivity.class);
        intent.putExtra(WeatherWebActivity.EXTRA_URL, valid);
        intent.putExtra(WeatherWebActivity.EXTRA_TITLE, title);
        activity.startActivity(intent);
        Log.i("WeatherPages", "Opened internal page host=" + Uri.parse(valid).getHost());
    }

    static String webUrl(String value) {
        if (value == null || value.length() > 32768) return null;
        Uri uri = Uri.parse(value.trim());
        String scheme = uri.getScheme();
        if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) return null;
        if (uri.getHost() == null || uri.getHost().length() == 0) return null;
        return uri.toString();
    }

    static boolean isWeatherVideo(String value) {
        if (value == null) return false;
        Uri uri = Uri.parse(value);
        if (!("mivideo".equalsIgnoreCase(uri.getScheme()) && "video".equals(uri.getHost()))) return false;
        return "/album".equals(uri.getPath()) && "specialweather".equals(uri.getQueryParameter("id"));
    }
}
