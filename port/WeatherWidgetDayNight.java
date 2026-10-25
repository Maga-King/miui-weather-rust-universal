package dev.weather.oneplus;

import java.util.Calendar;
import java.util.TimeZone;

/** Xiaomi weather-icon day/night only; sky phases and network timing are independent. */
final class WeatherWidgetDayNight {
    private WeatherWidgetDayNight() {}

    static boolean night(WeatherWidgetData data, long time) {
        TimeZone zone = TimeZone.getDefault();
        if (data != null && !data.zoneId.isEmpty()) {
            TimeZone requested = TimeZone.getTimeZone(data.zoneId);
            // TimeZone silently returns GMT for an unrecognized ID. Prefer the
            // device's local time in that case, rather than falsely assuming UTC.
            if (!"GMT".equals(requested.getID()) || "GMT".equals(data.zoneId)) zone = requested;
        }
        Calendar local = Calendar.getInstance(zone);
        local.setTimeInMillis(time);
        int minute = local.get(Calendar.HOUR_OF_DAY) * 60 + local.get(Calendar.MINUTE);
        int rise = data == null ? -1 : data.sunriseMinute;
        int setting = data == null ? -1 : data.sunsetMinute;
        // Without a usable pair, keep the conventional 06:00–18:00 appearance.
        // This never fabricates sunrise/sunset values in the weather observation.
        if (rise < 0 || setting <= rise) { rise = 360; setting = 1080; }
        return minute < rise || minute >= setting;
    }
}
