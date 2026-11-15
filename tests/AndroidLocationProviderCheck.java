package dev.weather.oneplus;

import android.app.Activity;
import android.location.Location;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Consumer;

/** Provider/permission differences that must not turn a location tap into an immediate failure. */
public final class AndroidLocationProviderCheck {
    private static int checks;
    private static Activity activity;
    private static AndroidPermissions permissions;
    private static AndroidLocation bridge;

    private static final class Reply implements AndroidLocation.Result {
        int count;
        Object value;
        String code;
        public void success(Object value) { count++; this.value = value; }
        public void error(String code, String message) { count++; this.code = code; }
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static void setup(int sdk, String... providers) {
        Build.VERSION.SDK_INT = sdk;
        activity = new Activity();
        activity.manager.available = Arrays.asList(providers);
        permissions = new AndroidPermissions(activity);
        bridge = new AndroidLocation(activity, permissions);
        bridge.onResume();
    }

    private static Reply begin() {
        Reply reply = new Reply();
        check(bridge.handle("weather_location", "location_amp_nlp", null, reply), "original coordinate channel");
        Handler.flush();
        return reply;
    }

    private static Location fix(String provider, long ageNanos, double latitude, float accuracy) {
        Location fix = new Location(provider);
        fix.setLatitude(latitude);
        fix.setLongitude(120);
        fix.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos() - ageNanos);
        fix.setAccuracy(accuracy);
        return fix;
    }

    private static boolean succeeded(Reply reply) {
        return reply.count == 1 && reply.value instanceof Map && reply.code == null;
    }

    private static void finish() { bridge.onDestroy(); Handler.flush(); }

    public static void main(String[] args) {
        // Android 12+ allows coarse GPS and redacts it; no network/fused provider is required.
        for (int sdk : new int[]{31, 33, 36}) {
            setup(sdk, "gps"); activity.fine = false;
            Reply reply = begin();
            check(reply.count == 0 && activity.manager.currentAsked.equals(Arrays.asList("gps")), "coarse-only GPS starts on API " + sdk);
            activity.manager.emit(fix("gps", 0, 36, 2000));
            check(succeeded(reply), "system approximate fix works on API " + sdk);
            check(((Map<?, ?>) reply.value).size() == 2, "coordinate-only contract stays unchanged");
            finish();
        }

        for (int sdk : new int[]{28, 29}) {
            setup(sdk, "network", "gps"); activity.fine = false;
            activity.manager.denied.add("gps");
            Reply reply = begin();
            check(activity.manager.currentAsked.isEmpty(), "pre-API30 does not call unavailable single-fix API");
            check(activity.manager.updatesAsked.contains("network") && reply.count == 0, "legacy coarse network survives precise GPS rejection");
            activity.manager.emit(fix("network", 0, 36, 2000));
            check(succeeded(reply) && activity.manager.listeners.isEmpty(), "legacy success removes live listeners");
            finish();
        }

        setup(31, "fused"); activity.fine = false;
        Reply fused = begin(); activity.manager.emit(fix("fused", 0, 36, 2000));
        check(succeeded(fused), "platform fused-only device works without Google client"); finish();

        setup(36, "vendor_location");
        Reply vendor = begin(); activity.manager.emit(fix("vendor_location", 0, 36, 10));
        check(succeeded(vendor), "enumerated OEM provider remains usable"); finish();

        setup(31, "network", "gps", "passive");
        activity.manager.last.put("network", fix("network", 1000000000L, 35, 100));
        activity.manager.last.put("gps", fix("gps", 1000000000L, 36, 10));
        activity.manager.last.put("passive", fix("gps", 2000000000L, 34, 1));
        Reply cached = begin();
        check(succeeded(cached) && ((Number)((Map<?, ?>)cached.value).get("mLatitude")).doubleValue() == 36, "cache selects newest fix then best accuracy");
        check(activity.manager.currentAsked.isEmpty() && activity.manager.updatesAsked.isEmpty(), "recent cache avoids unnecessary sensors"); finish();

        setup(31, "passive"); activity.manager.last.put("passive", fix("gps", 1000000000L, 36, 10));
        check(succeeded(begin()), "recent passive cache can resolve weather"); finish();
        setup(31, "passive"); Reply passive = begin();
        check("NO_PROVIDER".equals(passive.code) && activity.manager.currentAsked.isEmpty(), "passive-only device cannot pretend to start a fix"); finish();

        setup(31, "network", "gps");
        activity.manager.last.put("network", fix("network", 121000000000L, 34, 10));
        activity.manager.last.put("gps", fix("gps", -1000000000L, 35, 10));
        Reply stale = begin();
        check(stale.count == 0 && activity.manager.currentAsked.size() == 2, "stale and future cache trigger live request");
        activity.manager.emit(fix("network", 0, 36, 10)); check(succeeded(stale), "fresh fix replaces invalid caches"); finish();

        setup(31, "network"); activity.manager.last.put("network", fix("network", 0, Double.NaN, 10));
        Reply invalid = begin(); activity.manager.emit(fix("network", 0, 91, 10));
        check(invalid.count == 0 && activity.manager.updatesAsked.contains("network"), "invalid current fix falls back to live updates");
        activity.manager.emit(fix("network", 0, 36, 10)); check(succeeded(invalid), "valid live fallback resolves after invalid current result"); finish();

        setup(31, "fused", "network", "gps"); activity.manager.currentFail.add("fused"); activity.manager.updateFail.add("fused");
        activity.manager.cacheFail.add("network");
        Reply broken = begin();
        check(broken.count == 0 && activity.manager.currentAsked.contains("gps"), "one broken backend/cache cannot abort another provider");
        activity.manager.emit(fix("gps", 0, 36, 10)); check(succeeded(broken), "working provider resolves despite other runtime failures"); finish();

        setup(31, "network"); activity.manager.currentFail.add("network");
        Reply oldApi = begin();
        check(oldApi.count == 0 && activity.manager.updatesAsked.contains("network"), "single-fix failure falls back to old updates API");
        activity.manager.emit(fix("network", 0, 36, 10)); check(succeeded(oldApi), "legacy fallback can return real fix"); finish();

        setup(31, "network", "gps"); activity.manager.checkFail.add("network");
        Reply badState = begin(); activity.manager.emit(fix("gps", 0, 36, 10));
        check(succeeded(badState), "provider state exception does not discard other providers"); finish();

        setup(31, "network"); activity.manager.enumerationFails = true;
        Reply listFallback = begin(); activity.manager.emit(fix("network", 0, 36, 10));
        check(succeeded(listFallback), "enabled enumeration fallback keeps system provider available"); finish();

        setup(31, "network"); Reply noFix = begin(); activity.manager.emitCurrent("network", null); Handler.flush();
        check(noFix.count == 0 && activity.manager.updatesAsked.contains("network"), "null current result waits for live updates");
        Handler.advance(15000); check(noFix.count == 1 && "TIMEOUT".equals(noFix.code), "no live fix has bounded deadline");
        activity.manager.emit(fix("network", 0, 36, 10)); check(noFix.count == 1, "deadline removes listeners and ignores late fix"); finish();

        setup(31, "network"); Reply richDeadline = new Reply();
        check(bridge.handle("xms_location", "locationFromGms", true, richDeadline), "GMS-shaped original channel stays supported");
        Handler.flush(); Handler.advance(10000);
        check(richDeadline.count == 1 && "TIMEOUT".equals(richDeadline.code), "rich caller reserves time within original 20-second deadline");
        check(activity.manager.signals.get("network").isCanceled(), "rich deadline cancels outstanding system fix"); finish();

        setup(31, "network", "gps"); Reply pending = begin();
        Consumer<Location> late = activity.manager.current.get("network");
        bridge.onPause();
        check(pending.count == 1 && "CANCELLED".equals(pending.code), "pause cancels pending single fix exactly once");
        check(activity.manager.signals.get("network").isCanceled() && activity.manager.signals.get("gps").isCanceled(), "pause cancels all system requests");
        late.accept(fix("network", 0, 36, 10)); Handler.advance(15000);
        check(pending.count == 1, "late current callback after pause cannot reply again"); finish();

        setup(31, "network"); Reply dead = begin(); late = activity.manager.current.get("network"); bridge.onDestroy();
        late.accept(fix("network", 0, 36, 10)); Handler.advance(15000);
        check(dead.count == 0 && activity.manager.signals.get("network").isCanceled(), "destroy cancels current fix without late reply");

        setup(31, "network"); activity.manager.enabled = false;
        activity.manager.last.put("network", fix("network", 0, 36, 10)); Reply off = begin();
        check(off.count == 1 && "LOCATION_DISABLED".equals(off.code) && off.value == null, "system switch off cannot return cached fix"); finish();

        setup(31, "network"); Reply switchedOff = begin(); activity.manager.enabled = false;
        activity.manager.emit(fix("network", 0, 36, 10));
        check(switchedOff.count == 1 && "LOCATION_DISABLED".equals(switchedOff.code), "switch revoked during request blocks late success"); finish();

        setup(31, "network"); Reply nullOff = begin(); activity.manager.enabled = false;
        activity.manager.emitCurrent("network", null); Handler.flush();
        check(nullOff.count == 1 && "LOCATION_DISABLED".equals(nullOff.code), "disabled service null result remains a location-switch error"); finish();

        setup(31, "network"); Reply offDeadline = begin(); activity.manager.enabled = false; Handler.advance(15000);
        check(offDeadline.count == 1 && "LOCATION_DISABLED".equals(offDeadline.code), "deadline checks switch when provider omits disabled callback"); finish();

        setup(31, "network"); activity.manager.stateFails = true; Reply absentService = begin();
        check(absentService.count == 1 && "LOCATION_UNAVAILABLE".equals(absentService.code), "system service failure is distinct from disabled location"); finish();

        setup(31, "network"); Reply revoked = begin(); activity.granted = false;
        activity.manager.emit(fix("network", 0, 36, 10));
        check(revoked.count == 1 && "PERMISSION_DENIED".equals(revoked.code), "revoked grant cannot return location"); finish();

        setup(31, "network"); Reply revokedDeadline = begin(); activity.granted = false; Handler.advance(15000);
        check(revokedDeadline.count == 1 && "PERMISSION_DENIED".equals(revokedDeadline.code), "deadline detects revoked permission without provider callback"); finish();

        setup(31, "network"); activity.granted = false; permissions.pending = true;
        bridge.onPause(); Reply waiting = begin();
        check(waiting.count == 0 && permissions.requests == 1 && activity.manager.currentAsked.isEmpty(), "request around runtime dialog queues permission instead of immediate foreground error");
        bridge.onResume(); permissions.complete(true); Handler.flush();
        activity.manager.emit(fix("network", 0, 36, 10));
        check(succeeded(waiting), "permission queue starts location after Activity resumes"); finish();

        setup(31, "network"); activity.granted = false; permissions.pending = true;
        Reply denied = begin(); permissions.complete(false); Handler.flush();
        check(denied.count == 1 && "PERMISSION_DENIED".equals(denied.code), "denied permission reply is bounded and truthful"); finish();

        setup(31, "network"); activity.granted = false; permissions.pending = true;
        Reply lost = begin(); bridge.onDestroy(); permissions.complete(true); Handler.flush();
        check(lost.count == 0 && activity.manager.currentAsked.isEmpty(), "destroy during permission queue suppresses location and reply");

        System.out.println("PASS: " + checks + " location provider checks; API 28-36, approximate GPS, recent cache, independent fallbacks and lifecycle");
    }
}
