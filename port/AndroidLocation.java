package dev.weather.oneplus;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Foreground, single-fix LocationManager adapter for the original Dart channels. */
public final class AndroidLocation {
    public interface Result {
        void success(Object value);
        void error(String code, String message);
    }

    private static final String TAG = "WeatherLocation";
    private static final long FIX_TIMEOUT_MS = 15000;
    // Keep the original rich caller within its 20-second request deadline.
    private static final long RICH_FIX_TIMEOUT_MS = 10000;
    private static final long GEOCODER_TIMEOUT_MS = 2500;
    private static final long MAX_FIX_AGE_NANOS = 120000000000L;
    // One process-wide worker bounds resources even if a device's old Geocoder
    // ignores interruption. A busy backend falls back to the real coordinates.
    private static final ThreadPoolExecutor GEOCODER_WORKER = new ThreadPoolExecutor(
            1, 1, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(1), runnable -> {
                Thread thread = new Thread(runnable, "WeatherGeocoder");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
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
            if (!reverse && !permissions.hasForegroundLocation()) {
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
        private final Runnable fixTimeout = this::fixExpired;
        private final Runnable geocoderTimeout = () -> succeed(null);
        private final Set<String> providers = new LinkedHashSet<>();
        private final Map<String, CancellationSignal> currentRequests = new LinkedHashMap<>();
        private Location fix;
        private Future<?> geocoderTask;
        private WeakReference<Query> geocoderTarget;
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
            if (manager == null) {
                fail("LOCATION_UNAVAILABLE", "The system location service is unavailable");
                return;
            }
            Set<String> candidates = new LinkedHashSet<>();
            try {
                if (!manager.isLocationEnabled()) {
                    fail("LOCATION_DISABLED", "System location is turned off");
                    return;
                }
                List<String> available;
                try {
                    available = manager.getAllProviders();
                } catch (RuntimeException unavailable) {
                    Log.w(TAG, "Provider enumeration failed; checking enabled providers", unavailable);
                    available = manager.getProviders(true);
                }
                // The platform fused provider is optional, and was hidden from
                // enumeration before Android 12. Use only providers the OS exposes.
                for (String provider : new String[]{"fused", LocationManager.NETWORK_PROVIDER,
                        LocationManager.GPS_PROVIDER})
                    if (available.contains(provider)) candidates.add(provider);
                candidates.addAll(available);
            } catch (RuntimeException error) {
                Log.w(TAG, "LocationManager state is unavailable", error);
                fail("LOCATION_UNAVAILABLE", "The system location service is unavailable");
                return;
            }
            Log.i(TAG, "System providers=" + candidates + ", fine=" + fine + ", coarse=" + coarse);
            Location recent = null;
            for (String provider : new ArrayList<>(candidates)) {
                if (!providerEnabled(provider)) { candidates.remove(provider); continue; }
                try {
                    Location cached = manager.getLastKnownLocation(provider);
                    if (usableFix(cached) && (recent == null
                            || cached.getElapsedRealtimeNanos() > recent.getElapsedRealtimeNanos()
                            || (cached.getElapsedRealtimeNanos() == recent.getElapsedRealtimeNanos()
                            && cached.getAccuracy() < recent.getAccuracy()))) recent = cached;
                } catch (SecurityException denied) {
                    Log.i(TAG, "Cached provider unavailable with the current grant: " + provider);
                } catch (RuntimeException unavailable) {
                    // A broken cache/backend must not stop another provider's live fix.
                    Log.w(TAG, "Cached provider unavailable: " + provider, unavailable);
                }
            }
            if (recent != null) { onLocationChanged(recent); return; }
            main.postDelayed(fixTimeout, stringResult ? RICH_FIX_TIMEOUT_MS : FIX_TIMEOUT_MS);
            for (String provider : candidates) {
                // Passive can contribute a recent cache but cannot initiate a fix.
                if (!LocationManager.PASSIVE_PROVIDER.equals(provider)) requestFix(provider);
            }
            if (providers.isEmpty()) {
                fail("NO_PROVIDER", "No enabled location provider supports the current permission");
            }
        }

        boolean providerEnabled(String provider) {
            try {
                return manager.isProviderEnabled(provider);
            } catch (SecurityException denied) {
                Log.i(TAG, "Location provider unavailable with the current grant: " + provider);
            } catch (RuntimeException unavailable) {
                Log.w(TAG, "Location provider state unavailable: " + provider, unavailable);
            }
            return false;
        }

        void requestFix(String provider) {
            if (finished || fix != null) return;
            if (Build.VERSION.SDK_INT >= 30) {
                CancellationSignal cancellation = new CancellationSignal();
                currentRequests.put(provider, cancellation);
                providers.add(provider);
                try {
                    // A single-fix request can reuse a system fix and activates the
                    // provider when needed. Android enforces/redacts the actual grant,
                    // including approximate GPS on Android 12+.
                    manager.getCurrentLocation(provider, cancellation, command -> main.post(command), location -> {
                        currentRequests.remove(provider);
                        if (finished || fix != null || !providers.contains(provider)) return;
                        onLocationChanged(location);
                        if (!finished && fix == null) {
                            providers.remove(provider);
                            requestUpdates(provider);
                            if (providers.isEmpty())
                                fail("NO_PROVIDER", "No enabled location provider could return a fix");
                        }
                    });
                    return;
                } catch (RuntimeException unavailable) {
                    currentRequests.remove(provider);
                    providers.remove(provider);
                    cancelCurrent(cancellation);
                    Log.w(TAG, "Single-fix request unavailable; using location updates: " + provider, unavailable);
                }
            }
            requestUpdates(provider);
        }

        void requestUpdates(String provider) {
            if (finished || fix != null || !providerEnabled(provider)) return;
            try {
                manager.requestLocationUpdates(provider, 0L, 0f, this, Looper.getMainLooper());
                providers.add(provider);
            } catch (SecurityException denied) {
                Log.i(TAG, "Location provider unavailable with the current grant: " + provider);
            } catch (RuntimeException unavailable) {
                Log.w(TAG, "Location updates unavailable: " + provider, unavailable);
            }
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
            if (finished || fix != null) return;
            if (!hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                    && !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                fail("PERMISSION_DENIED", "Location permission was revoked");
                return;
            }
            try {
                if (!manager.isLocationEnabled()) {
                    fail("LOCATION_DISABLED", "System location is turned off");
                    return;
                }
            } catch (RuntimeException unavailable) {
                fail("LOCATION_UNAVAILABLE", "The system location service is unavailable");
                return;
            }
            if (!usableFix(location)) return;
            fix = new Location(location);
            stopUpdates();
            main.removeCallbacks(fixTimeout);
            Log.i(TAG, "Received system location from " + location.getProvider()
                    + ", accuracy=" + location.getAccuracy());
            // The Dart lat/lng-only parser ignores address fields on this channel.
            // Its old-XMS caller requests reverse geography separately; its
            // international caller resolves the city using the weather server.
            if (!stringResult) {
                succeed(null);
                return;
            }
            geocode();
        }

        void geocode() { geocodeAndroid(); }

        void geocodeAndroid() {
            try {
                if (!Geocoder.isPresent()) { succeed(null); return; }
                main.postDelayed(geocoderTimeout, GEOCODER_TIMEOUT_MS);
                geocoderTarget = new WeakReference<>(this);
                Context context = activity.getApplicationContext();
                if (Build.VERSION.SDK_INT >= 33) {
                    new Geocoder(context, Locale.getDefault()).getFromLocation(
                            fix.getLatitude(), fix.getLongitude(), 1,
                            new AddressReply(main, geocoderTarget));
                } else {
                    geocoderTask = GEOCODER_WORKER.submit(new LegacyGeocodeTask(context,
                            fix.getLatitude(), fix.getLongitude(), main, geocoderTarget));
                }
            } catch (RejectedExecutionException busy) {
                Log.i(TAG, "Reverse geocoder is busy; returning real coordinates");
                succeed(null);
            } catch (RuntimeException error) {
                Log.i(TAG, "Reverse geocoder unavailable; returning real coordinates");
                succeed(null);
            }
        }

        @Override public void onProviderDisabled(String provider) {
            providers.remove(provider);
            CancellationSignal cancellation = currentRequests.remove(provider);
            cancelCurrent(cancellation);
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
                // The original XMS schema uses the district as mCityName. Dart
                // copies it over the weather server's selected district name in
                // zh_cn; locality belongs in mLocality, not in that override.
                String city = address == null ? "" : first(address.getSubLocality(),
                        first(address.getSubAdminArea(), address.getLocality()));
                String street = address == null ? "" : AndroidLocationLabels.choose(city,
                        address.getAdminArea(), address.getLocality(), address.getSubAdminArea(),
                        address.getSubLocality(), address.getThoroughfare(), address.getPremises(),
                        address.getFeatureName(), address.getSubThoroughfare(), false);
                value.put("mCityName", city);
                value.put("mStreetName", street);
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
            Log.i(TAG, "Location query failed: " + code + " (" + message + ")");
            cleanup();
            result.error(code, message);
        }

        void fixExpired() {
            if (finished || destroyed || fix != null) return;
            if (!permissions.hasForegroundLocation()) {
                fail("PERMISSION_DENIED", "Location permission was revoked");
                return;
            }
            try {
                if (!manager.isLocationEnabled()) {
                    fail("LOCATION_DISABLED", "System location is turned off");
                    return;
                }
            } catch (RuntimeException unavailable) {
                fail("LOCATION_UNAVAILABLE", "The system location service is unavailable");
                return;
            }
            fail("TIMEOUT", "No recent location fix was available");
        }

        void stopUpdates() {
            for (CancellationSignal cancellation : currentRequests.values()) cancelCurrent(cancellation);
            currentRequests.clear();
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
            if (geocoderTarget != null) geocoderTarget.clear();
            if (geocoderTask != null) {
                geocoderTask.cancel(true);
                if (geocoderTask instanceof Runnable) GEOCODER_WORKER.remove((Runnable) geocoderTask);
                geocoderTask = null;
            }
            stopUpdates();
            queries.remove(this);
        }
    }

    private static void cancelCurrent(CancellationSignal cancellation) {
        if (cancellation == null) return;
        try {
            cancellation.cancel();
        } catch (RuntimeException unavailable) {
            Log.w(TAG, "Could not cancel system single-fix request", unavailable);
        }
    }

    private static boolean usableFix(Location location) {
        if (location == null) return false;
        double latitude = location.getLatitude(), longitude = location.getLongitude();
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) return false;
        long timestamp = location.getElapsedRealtimeNanos();
        long now = SystemClock.elapsedRealtimeNanos();
        return timestamp > 0 && timestamp <= now && now - timestamp <= MAX_FIX_AGE_NANOS;
    }

    /** Weak replies cannot keep a destroyed Activity alive or reply after cancellation. */
    private static void postAddress(Handler main, WeakReference<Query> target, Address address) {
        main.post(() -> {
            Query query = target.get();
            if (query != null) query.succeed(address);
        });
    }

    private static final class AddressReply implements Geocoder.GeocodeListener {
        private final Handler main;
        private final WeakReference<Query> target;
        AddressReply(Handler main, WeakReference<Query> target) { this.main = main; this.target = target; }
        @Override public void onGeocode(List<Address> addresses) {
            postAddress(main, target, addresses == null || addresses.isEmpty() ? null : addresses.get(0));
        }
        @Override public void onError(String message) { postAddress(main, target, null); }
    }

    private static final class LegacyGeocodeTask implements Runnable {
        private final Context context;
        private final double latitude, longitude;
        private final Handler main;
        private final WeakReference<Query> target;
        LegacyGeocodeTask(Context context, double latitude, double longitude,
                Handler main, WeakReference<Query> target) {
            this.context = context; this.latitude = latitude; this.longitude = longitude;
            this.main = main; this.target = target;
        }
        @Override public void run() {
            Address address = null;
            try {
                // The legacy API may block; it must never run on the Activity thread.
                List<Address> addresses = new Geocoder(context, Locale.getDefault())
                        .getFromLocation(latitude, longitude, 1);
                if (addresses != null && !addresses.isEmpty()) address = addresses.get(0);
            } catch (Exception unavailable) {
                Log.i(TAG, "Reverse geocoder unavailable; returning real coordinates");
            }
            postAddress(main, target, address);
        }
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String first(String preferred, String fallback) {
        return preferred == null || preferred.trim().isEmpty() ? text(fallback).trim() : preferred.trim();
    }

}
