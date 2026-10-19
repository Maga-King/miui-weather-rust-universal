package dev.weather.oneplus;

import android.app.Activity;
import android.util.Log;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Locale;

/** Keeps the original rain popup working when AMap cannot reverse-geocode this host. */
final class RainMapGeocodeFallback {
    private static final String TAG = "WeatherRainMap";
    private final Activity activity;
    private Object installedController;

    RainMapGeocodeFallback(Activity activity) { this.activity = activity; }

    void install() {
        try {
            Object fragment = fieldValue(activity, "com.miui.weather2map.fragment.FragmentMinuteRain");
            if (fragment == null) return;
            final Object controller = fieldValue(fragment, "com.miui.weather2map.tools.q");
            if (controller == null || controller == installedController) return;
            Object geocoder = fieldValue(controller, "com.amap.api.services.geocoder.GeocodeSearch");
            if (geocoder == null) return;
            Class<?> listener = Class.forName(
                    "com.amap.api.services.geocoder.GeocodeSearch$OnGeocodeSearchListener");
            Object proxy = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[]{listener},
                    (object, method, arguments) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            if (method.getName().equals("hashCode")) return System.identityHashCode(object);
                            if (method.getName().equals("equals")) return object == arguments[0];
                            return TAG;
                        }
                        if (method.getName().equals("onRegeocodeSearched")
                                && (arguments[0] == null || ((Integer) arguments[1]) != 1000)) {
                            showSelectedForecast(controller, (Integer) arguments[1]);
                            return null;
                        }
                        return method.invoke(controller, arguments);
                    });
            geocoder.getClass().getMethod("setOnGeocodeSearchListener", listener).invoke(geocoder, proxy);
            installedController = controller;
            Log.i(TAG, "Installed original rain map geocode fallback");
        } catch (ReflectiveOperationException error) {
            Log.w(TAG, "Cannot install rain map geocode fallback", error);
        }
    }

    private void showSelectedForecast(final Object controller, int status) {
        activity.runOnUiThread(() -> {
            if (activity.isFinishing() || activity.isDestroyed() || controller != installedController) return;
            try {
                // Read the current marker on the UI thread, so a late failed geocode
                // response always requests the latest tap, rather than its old query.
                Object marker = fieldValue(controller, "com.amap.api.maps.model.Marker");
                if (marker == null) return;
                Object point = marker.getClass().getMethod("getPosition").invoke(marker);
                if (point == null) return;
                double latitude = point.getClass().getField("latitude").getDouble(point);
                double longitude = point.getClass().getField("longitude").getDouble(point);
                String label = String.format(Locale.getDefault(), "%.3f, %.3f", latitude, longitude);
                Log.i(TAG, "Reverse geocode unavailable (" + status + "); requesting original selected-point forecast");
                // q.x -> FragmentMinuteRain.p0 performs the original Xiaomi request; q0
                // binds the response to the original BubbleLayout and intensity chart.
                Method changed = controller.getClass().getMethod("x", String.class, double.class, double.class);
                changed.invoke(controller, label, latitude, longitude);
            } catch (ReflectiveOperationException error) {
                Log.w(TAG, "Cannot request original selected-point forecast", error);
            }
        });
    }

    private static Object fieldValue(Object owner, String className) throws IllegalAccessException {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!field.getType().getName().startsWith("com.miui.weather2map.")
                        && !field.getType().getName().equals(className)) continue;
                field.setAccessible(true);
                Object value = field.get(owner);
                if (value != null && value.getClass().getName().equals(className)) return value;
            }
        }
        return null;
    }
}
