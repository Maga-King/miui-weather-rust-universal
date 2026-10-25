package dev.weather.oneplus;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Foreground, single-fix LocationManager adapter for the original Dart channels. */
public final class AndroidLocation {
    public interface Result {
        void success(Object value);
        void error(String code, String message);
    }

    private static final String TAG = "WeatherLocation";
    private static final long FIX_TIMEOUT_MS = 15000;
    private static final long GEOCODER_TIMEOUT_MS = 2500;
    private static final long MAX_FIX_AGE_NANOS = 120000000000L;
    private final Activity activity;
    private final AndroidPermissions permissions;
    private final LocationManager manager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Query> queries = new HashSet<>();
    private boolean resumed;
    private boolean destroyed;

    public AndroidLocation(Activity activity, AndroidPermissions permissions) {
        this.activity = activity;
        this.permissions = permissions;
        manager = activity.getSystemService(LocationManager.class);
    }

    /**
     * Returns true only for a supported channel/method. Reply is delivered once,
     * asynchronously on the main thread, with the caller's original method codec.
     * Foreground location operations use the standard permission queue when the
     * original Dart path omitted a grant request. Checks/reverse geocoding do not.
     */
    public boolean handle(String channel, String method, Object arguments, Result result) {
        if ("weather_method_channel".equals(channel) && "get_xms_core_version".equals(method)) {
            // No XMS backend is installed or impersonated by this ordinary APK.
            if (!destroyed) main.post(() -> { if (!destroyed) result.success(0); });
            return true;
        }
        boolean reverse = "weather_method_channel".equals(channel)
                && "query_xms_core_reverse_geography".equals(method);
        boolean weather = "weather_location".equals(channel)
                && "location_amp_nlp".equals(method);
        boolean xms = "xms_location".equals(channel)
                && ("locationFromNlp".equals(method) || "locationFromAmap".equals(method)
                || "locationFromGms".equals(method));
        if (!weather && !xms && !reverse) return false;
        main.post(() -> {
            if (destroyed) return;
            if (!reverse && resumed && !permissions.hasForegroundLocation()) {
                Log.i(TAG, "Foreground location is waiting for an Android permission grant");
                permissions.requestForegroundLocation(granted -> {
                    if (destroyed) return;
                    if (!granted) result.error("PERMISSION_DENIED", "Location permission has not been granted");
                    else beginQuery(xms, false, arguments, result);
                });
            } else beginQuery(xms || reverse, reverse, arguments, result);
        });
        return true;
    }

    private void beginQuery(boolean stringResult, boolean reverse, Object arguments, Result result) {
        Query query = new Query(stringResult, result);
        queries.add(query);
        if (reverse) query.reverse(arguments); else query.start();
    }

    public void onResume() {
        resumed = true;
    }

    /** Foreground host stops tracking when its Activity leaves the foreground. */
    public void onPause() {
        resumed = false;
        for (Query query : new ArrayList<>(queries)) {
            query.fail("CANCELLED", "The weather screen is no longer in the foreground");
        }
    }

    /** Call before destroying the native reply bridge; cancels without late replies. */
    public void onDestroy() {
        destroyed = true;
        resumed = false;
        for (Query query : new ArrayList<>(queries)) query.cleanup();
    }

    private boolean hasPermission(String permission) {
        return activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private final class Query implements LocationListener {
        private final boolean stringResult;
        private final Result result;
        private final Runnable fixTimeout = () -> fail("TIMEOUT", "No recent location fix was available");
        private final Runnable geocoderTimeout = () -> succeed(null);
        private final Set<String> providers = new LinkedHashSet<>();
        private Location fix;
        private boolean finished;

        Query(boolean stringResult, Result result) {
            this.stringResult = stringResult;
            this.result = result;
        }

        void start() {
            if (!resumed || activity.isFinishing() || activity.isDestroyed()) {
                fail("APP_NOT_FOREGROUND", "Open the weather screen to request a location");
                return;
            }
            boolean fine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION);
            boolean coarse = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION);
            if (!fine && !coarse) {
                fail("PERMISSION_DENIED", "Location permission has not been granted");
                return;
            }
            if (manager == null || !manager.isLocationEnabled()) {
                fail("LOCATION_DISABLED", "System location is turned off");
                return;
            }
            try {
                List<String> enabled = manager.getProviders(true);
                // Prefer the device's own fused/network service. GPS is optional
                // and requires precise location; no Xiaomi or GMS service is used.
                for (String provider : new String[]{"fused", LocationManager.NETWORK_PROVIDER,
                        LocationManager.GPS_PROVIDER}) {
                    if (!enabled.contains(provider)) continue;
                    if (LocationManager.GPS_PROVIDER.equals(provider) && !fine) continue;
                    try {
                        manager.requestLocationUpdates(provider, 0L, 0f, this, Looper.getMainLooper());
                        providers.add(provider);
                    } catch (SecurityException denied) {
                        Log.i(TAG, "Location provider unavailable with the current grant: " + provider);
                    } catch (IllegalArgumentException unavailable) {
                        Log.i(TAG, "Location provider disappeared: " + provider);
                    }
                }
            } catch (RuntimeException error) {
                Log.w(TAG, "LocationManager request failed", error);
                fail("LOCATION_UNAVAILABLE", "The system location service is unavailable");
                return;
            }
            if (providers.isEmpty()) {
                fail("NO_PROVIDER", "No enabled location provider supports the current permission");
                return;
            }
            main.postDelayed(fixTimeout, FIX_TIMEOUT_MS);
        }

        /** Original provider-query shape; Android Geocoder consumes the real WGS84 fix directly. */
        void reverse(Object arguments) {
            if (!resumed || activity.isFinishing() || activity.isDestroyed()) {
                fail("APP_NOT_FOREGROUND", "Open the weather screen to resolve a location");
                return;
            }
            if (!hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                    && !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                fail("PERMISSION_DENIED", "Location permission has not been granted");
                return;
            }
            try {
                Object values = arguments instanceof String ? new JSONObject((String) arguments) : arguments;
                Object rawUri = values instanceof JSONObject ? ((JSONObject) values).opt("uri")
                    : values instanceof Map ? ((Map<?, ?>) values).get("uri") : null;
                if (!(rawUri instanceof String)) throw new IllegalArgumentException("Missing query URI");
                Uri uri = Uri.parse((String) rawUri);
                if (!"content".equals(uri.getScheme()) || !"com.miui.weather2map".equals(uri.getAuthority()))
                    throw new IllegalArgumentException("Unexpected query provider");
                double latitude = Double.parseDouble(uri.getQueryParameter("latitude"));
                double longitude = Double.parseDouble(uri.getQueryParameter("longitude"));
                if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                        || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180)
                    throw new IllegalArgumentException("Invalid coordinates");
                fix = new Location("android_geocoder");
                fix.setLatitude(latitude); fix.setLongitude(longitude);
                // needTranslateCoordinate was an AMap requirement. Android Geocoder uses WGS84,
                // so do not distort the actual coordinates or pretend an AMap conversion occurred.
                geocode();
            } catch (Exception error) {
                fail("INVALID_LOCATION_QUERY", "The reverse-location query is invalid");
            }
        }

        @Override public void onLocationChanged(Location location) {
            if (finished || fix != null || location == null) return;
            if (!hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                    && !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                fail("PERMISSION_DENIED", "Location permission was revoked");
                return;
            }
            double latitude = location.getLatitude();
            double longitude = location.getLongitude();
            if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                    || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) return;
            long age = SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos();
            if (age < 0 || age > MAX_FIX_AGE_NANOS) return;
            fix = new Location(location);
            stopUpdates();
            main.removeCallbacks(fixTimeout);
            Log.i(TAG, "Received system location from " + location.getProvider()
                    + ", accuracy=" + location.getAccuracy());
            // Coordinates alone satisfy the weather_location contract. The richer
            // XMS-shaped result can also carry real address data from Android.
            if (!stringResult) {
                succeed(null);
                return;
            }
            geocode();
        }

        void geocode() {
            if (Build.VERSION.SDK_INT < 33 || !Geocoder.isPresent()) {
                succeed(null);
                return;
            }
            main.postDelayed(geocoderTimeout, GEOCODER_TIMEOUT_MS);
            try {
                new Geocoder(activity, Locale.getDefault()).getFromLocation(fix.getLatitude(), fix.getLongitude(), 1,
                        new Geocoder.GeocodeListener() {
                            @Override public void onGeocode(List<Address> addresses) {
                                Address address = addresses == null || addresses.isEmpty()
                                        ? null : addresses.get(0);
                                main.post(() -> succeed(address));
                            }
                            @Override public void onError(String message) {
                                main.post(() -> succeed(null));
                            }
                        });
            } catch (RuntimeException error) {
                Log.i(TAG, "Reverse geocoder unavailable; returning real coordinates");
                succeed(null);
            }
        }

        @Override public void onProviderDisabled(String provider) {
            providers.remove(provider);
            if (fix == null && providers.isEmpty()) {
                fail("LOCATION_DISABLED", "The active location providers were turned off");
            }
        }

        @Override public void onProviderEnabled(String provider) { }
        @Override public void onStatusChanged(String provider, int status, Bundle extras) { }

        void succeed(Address address) {
            if (finished || fix == null || destroyed) return;
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("mLatitude", fix.getLatitude());
            value.put("mLongitude", fix.getLongitude());
            if (stringResult) {
                value.put("mCityName", address == null ? "" : first(address.getLocality(), address.getSubAdminArea()));
                value.put("mStreetName", address == null ? "" : first(address.getThoroughfare(), address.getSubLocality()));
                value.put("mAdminArea", address == null ? "" : text(address.getAdminArea()));
                value.put("mLocality", address == null ? "" : text(address.getLocality()));
                value.put("mSubLocality", address == null ? "" : text(address.getSubLocality()));
                value.put("mCountryName", address == null ? "" : text(address.getCountryName()));
                value.put("mErrorCode", 0);
                value.put("mErrorInfo", "");
                value.put("mLocationType", text(fix.getProvider()));
                // Do not claim this was a GMS/AMap fix: provider is the real source.
            }
            cleanup();
            result.success(stringResult ? new JSONObject(value).toString() : value);
        }

        void fail(String code, String message) {
            if (finished || destroyed) return;
            cleanup();
            result.error(code, message);
        }

        void stopUpdates() {
            if (manager != null) {
                try {
                    manager.removeUpdates(this);
                } catch (RuntimeException error) {
                    Log.w(TAG, "Could not remove location listener", error);
                }
            }
            providers.clear();
        }

        void cleanup() {
            if (finished) return;
            finished = true;
            main.removeCallbacks(fixTimeout);
            main.removeCallbacks(geocoderTimeout);
            stopUpdates();
            queries.remove(this);
        }
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String first(String preferred, String fallback) {
        return preferred == null || preferred.isEmpty() ? text(fallback) : preferred;
    }
}
