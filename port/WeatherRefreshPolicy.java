package dev.weather.oneplus;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Wall-clock policy only; Android decides when an eligible job actually runs. */
public final class WeatherRefreshPolicy {
    public static final long INTERVAL_MS = 30 * 60 * 1000L;
    public static final long BASE_BACKOFF_MS = 30 * 1000L;
    private WeatherRefreshPolicy() {}

    public static boolean quiet(long timeMs, ZoneId zone) {
        int hour = Instant.ofEpochMilli(timeMs).atZone(zone).getHour();
        return hour >= 23 || hour < 4;
    }

    /** Local calendar arithmetic preserves 04:00 across DST and offset changes. */
    public static long nextAutomaticTime(long nowMs, long earliestMs, ZoneId zone) {
        return nextAutomaticTime(nowMs, earliestMs, zone, false);
    }

    public static long nextAutomaticTime(long nowMs, long earliestMs, ZoneId zone, boolean nightUpdate) {
        long candidate = Math.max(nowMs, earliestMs);
        if (nightUpdate) return candidate;
        ZonedDateTime local = Instant.ofEpochMilli(candidate).atZone(zone);
        if (!quiet(candidate, zone)) return candidate;
        LocalDate morning = local.toLocalDate();
        if (local.getHour() >= 23) morning = morning.plusDays(1);
        return morning.atTime(4, 0).atZone(zone).toInstant().toEpochMilli();
    }

    public static long retryDelay(int failures) {
        int exponent = Math.max(1, Math.min(12, failures)) - 1;
        return Math.min(6 * INTERVAL_MS, BASE_BACKOFF_MS * (1L << exponent));
    }

    static long after(long nowMs, long delayMs) {
        long delay = Math.max(0, delayMs);
        return nowMs > Long.MAX_VALUE - delay ? Long.MAX_VALUE : nowMs + delay;
    }
}
