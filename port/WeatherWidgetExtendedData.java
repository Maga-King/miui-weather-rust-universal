package dev.weather.oneplus;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.util.Log;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Iterator;
import org.json.JSONArray;
import org.json.JSONObject;

/** Additional widget views over existing SQLite/provider caches. Never fetches weather. */
public final class WeatherWidgetExtendedData {
    private WeatherWidgetExtendedData() {}

    public static JSONObject read(Context context, WeatherWidgetData selected) {
        return snapshot(context, selected).details(selected);
    }

    /** One update batch owns this snapshot; it never survives into the next refresh. */
    static final class Snapshot {
        final JSONArray cities = new JSONArray();
        final Map<String, WeatherWidgetData> observations = new LinkedHashMap<>();
        final Map<String, JSONObject> raw = new LinkedHashMap<>();
        private final Map<String, JSONObject> parsed = new LinkedHashMap<>();
        String first = "", located = "", locale = "";
        final long now = System.currentTimeMillis();

        String defaultCity(boolean preferLocated) {
            return preferLocated && !located.isEmpty() ? located : first;
        }
        WeatherWidgetData city(String id) { return observations.get(id); }
        JSONObject details(WeatherWidgetData selected) {
            JSONObject out = empty();
            try {
                out.put("cities", cities);
                if (selected == null || !raw.containsKey(selected.cityId)) return out;
                String key = selected.cityId + "|" + selected.unit;
                JSONObject value = parsed.get(key);
                if (value == null) {
                    value = parse(raw.get(selected.cityId), selected.unit, locale, now);
                    parsed.put(key, value);
                }
                // The envelope is per instance (panel/secondCityId must not leak).
                // Forecast arrays are read-only inputs to the renderers.
                for (Iterator<String> keys = value.keys(); keys.hasNext();) {
                    String field = keys.next();
                    if (!"cities".equals(field)) out.put(field, value.get(field));
                }
            } catch (Exception error) {
                Log.w("WeatherWidget", "Cached detail unavailable: " + error.getClass().getSimpleName());
            }
            return out;
        }
    }

    static Snapshot snapshot(Context context, WeatherWidgetData selected) {
        Snapshot snapshot = new Snapshot();
        try {
            String unit = WeatherDatabaseBridge.temperatureUnit(context);
            Locale locale = context.getResources().getConfiguration().getLocales().get(0);
            snapshot.locale = locale.toString();
            if (context.getDatabasePath("weather.db").isFile()) {
                try (WeatherReadDatabase db = WeatherReadDatabase.open(context);
                     Cursor rows = db.rawQuery("SELECT posID,name,extra,latitude,longtitude,flag "
                             + "FROM selectedcity ORDER BY position ASC,flag DESC", null)) {
                    while (rows.moveToNext()) {
                        String id = text(rows, 0), name = text(rows, 1);
                        if (!id.isEmpty() && snapshot.first.isEmpty()) snapshot.first = id;
                        if (!id.isEmpty() && rows.getInt(5) == 1 && snapshot.located.isEmpty()) snapshot.located = id;
                        if (id.isEmpty() || name.isEmpty()) continue;
                        JSONObject raw = null;
                        try (Cursor data = db.rawQuery("SELECT data1,minute_rain_data FROM raw WHERE city_id=? LIMIT 1",
                                new String[]{id})) {
                            if (data.moveToFirst() && !data.isNull(0)) {
                                try {
                                    raw = new JSONObject(data.getString(0));
                                    if (!raw.has("minutely") && !data.isNull(1) && !data.getString(1).isEmpty()) {
                                        try { raw.put("minutely", new JSONObject(data.getString(1))); }
                                        catch (Exception ignored) { /* Optional rain is independent. */ }
                                    }
                                } catch (Exception ignored) { /* This city's cache is unusable. */ }
                            }
                        }
                        // The background refresh already retains its raw network result for alerts.
                        String identity = id + "\n" + text(rows, 2) + "\n" + text(rows, 3)
                                + "\n" + text(rows, 4) + "\n" + (rows.getInt(5) == 1);
                        JSONObject network = cachedNetwork(context, identity);
                        if (network != null && (raw == null || observed(network) > observed(raw))) raw = network;
                        // Per-city background responses are already identity/unit checked by the cache.
                        // Parse this same raw object below, keeping observation and detail fields atomic.
                        WeatherDatabaseBridge.City saved = new WeatherDatabaseBridge.City(id, name,
                                text(rows, 2), text(rows, 3), text(rows, 4), rows.getInt(5) == 1,
                                locale.toString(), unit);
                        JSONObject widgetRaw = WeatherWidgetCityCache.raw(context, saved);
                        if (widgetRaw != null && (raw == null || observed(widgetRaw) >= observed(raw)))
                            raw = widgetRaw;
                        WeatherWidgetData city = null;
                        if (raw != null) {
                            try { city = XiaomiWeatherParser.parse(raw, id, name, unit, locale.toString()); }
                            catch (Exception ignored) { /* Do not invent an observation for a saved city. */ }
                        }
                        if (selected != null && selected.cityId.equals(id)) {
                            if (city == null || (selected.unit.equals(unit) && selected.observedAtMs > city.observedAtMs))
                                city = selected;
                        }
                        if (raw != null) snapshot.raw.put(id, raw);
                        if (city != null) {
                            snapshot.cities.put(city.toJson());
                            snapshot.observations.put(id, city);
                        }
                    }
                }
            }
        } catch (Exception error) {
            Log.w("WeatherWidget", "Extended cached weather unavailable: " + error.getClass().getSimpleName());
        }
        return snapshot;
    }

    private static JSONObject cachedNetwork(Context context, String identity) {
        try {
            SharedPreferences prefs = context.getSharedPreferences("weather_real_alerts_v1", Context.MODE_PRIVATE);
            if (!identity.equals(prefs.getString("snapshot_city", ""))) return null;
            String json = prefs.getString("snapshot", null);
            return json == null ? null : new JSONObject(json);
        } catch (Exception ignored) { return null; }
    }
    private static String text(Cursor cursor, int index) {
        return cursor.isNull(index) ? "" : cursor.getString(index).trim();
    }
    private static long observed(JSONObject raw) {
        JSONObject current = raw.optJSONObject("current");
        OffsetDateTime time = current == null ? null : date(current.optString("pubTime", ""));
        return time == null ? 0 : time.toInstant().toEpochMilli();
    }
    private static JSONObject empty() {
        JSONObject out = new JSONObject();
        try { out.put("cities", new JSONArray()).put("hourly", new JSONArray())
                .put("daily", new JSONArray()).put("humidity", "").put("wind", "").put("feelsLike", "").put("uvIndex", ""); }
        catch (Exception ignored) {}
        return out;
    }

    /** Pure parser: provider publication timestamps define forecast indices, never wall-clock guesses. */
    static JSONObject parse(JSONObject raw, String unit, String locale, long now) throws Exception {
        JSONObject out = empty();
        JSONArray hourly = new JSONArray(), daily = new JSONArray();
        JSONObject current = raw.optJSONObject("current");
        if (ok(current)) {
            out.put("humidity", measurement(current.optJSONObject("humidity")));
            // The provider publishes a scalar index, including a valid zero at night.
            // Missing/malformed values must not become a fabricated zero.
            String uv = current.optString("uvIndex", "").trim();
            try {
                double number = Double.parseDouble(uv);
                if (Double.isFinite(number) && number >= 0)
                    out.put("uvIndex", new java.math.BigDecimal(uv).stripTrailingZeros().toPlainString());
            } catch (NumberFormatException ignored) { /* Optional provider field. */ }
            JSONObject feels = current.optJSONObject("feelsLike");
            if (ok(feels)) out.put("feelsLike", temperature(feels.optString("value", ""), feels.optString("unit", ""), unit));
            JSONObject wind = current.optJSONObject("wind");
            if (ok(wind)) {
                String direction = measurement(wind.optJSONObject("direction"));
                String speed = measurement(wind.optJSONObject("speed"));
                out.put("wind", direction.isEmpty() ? speed : speed.isEmpty() ? direction : direction + " \u00b7 " + speed);
            }
        }
        JSONObject hours = raw.optJSONObject("forecastHourly");
        if (ok(hours)) {
            JSONObject temps = hours.optJSONObject("temperature"), weather = hours.optJSONObject("weather");
            JSONArray values = values(temps), codes = values(weather);
            OffsetDateTime start = temps == null ? null : date(temps.optString("pubTime", ""));
            OffsetDateTime codeStart = weather == null ? null : date(weather.optString("pubTime", ""));
            if (start != null && values != null) for (int i = 0; i < values.length(); i++) {
                OffsetDateTime time = start.plusHours(i);
                if (time.toInstant().toEpochMilli() < now) continue;
                String temp = temperature(values.optString(i, ""), temps.optString("unit", ""), unit);
                if (temp.isEmpty()) continue;
                int type = -1;
                if (codes != null && codeStart != null) {
                    long index = ChronoUnit.HOURS.between(codeStart, time);
                    if (index >= 0 && index < codes.length() && codeStart.plusHours(index).isEqual(time))
                        type = XiaomiWeatherParser.weatherType(codes.optInt((int)index, -1));
                }
                hourly.put(new JSONObject().put("timeMs", time.toInstant().toEpochMilli())
                        .put("temp", temp).put("weatherType", type));
            }
        }
        JSONObject days = raw.optJSONObject("forecastDaily");
        if (ok(days)) {
            JSONObject temps = days.optJSONObject("temperature");
            JSONArray ranges = values(temps), suns = values(days.optJSONObject("sunRiseSet"));
            JSONArray codes = values(days.optJSONObject("weather"));
            // Sunrise is an explicit provider timestamp for each calendar day, including offset.
            if (ranges != null && suns != null) for (int i = 0; i < ranges.length() && i < suns.length(); i++) {
                JSONObject sun = suns.optJSONObject(i), range = ranges.optJSONObject(i);
                OffsetDateTime time = sun == null ? null : date(sun.optString("from", ""));
                if (time == null || range == null || time.toLocalDate().isBefore(
                        java.time.Instant.ofEpochMilli(now).atOffset(time.getOffset()).toLocalDate())) continue;
                String high = temperature(range.optString("from", ""), temps.optString("unit", ""), unit);
                String low = temperature(range.optString("to", ""), temps.optString("unit", ""), unit);
                if (high.isEmpty() || low.isEmpty()) continue;
                JSONObject code = codes == null ? null : codes.optJSONObject(i);
                int type = XiaomiWeatherParser.weatherType(code == null ? -1 : code.optInt("from", -1));
                daily.put(new JSONObject().put("timeMs", time.toInstant().toEpochMilli())
                        .put("high", high).put("low", low).put("weatherType", type)
                        .put("description", XiaomiWeatherParser.description(type, locale)));
            }
        }
        return out.put("hourly", sorted(hourly)).put("daily", sorted(daily));
    }
    private static JSONArray sorted(JSONArray array) {
        java.util.ArrayList<JSONObject> rows = new java.util.ArrayList<>();
        for (int i = 0; i < array.length(); i++) rows.add(array.optJSONObject(i));
        rows.sort((left, right) -> Long.compare(left.optLong("timeMs"), right.optLong("timeMs")));
        JSONArray out = new JSONArray();
        for (JSONObject row : rows) out.put(row);
        return out;
    }
    private static boolean ok(JSONObject object) {
        return object != null && (!object.has("status") || object.optInt("status", -1) == 0);
    }
    private static JSONArray values(JSONObject object) { return ok(object) ? object.optJSONArray("value") : null; }
    private static OffsetDateTime date(String value) {
        try { return OffsetDateTime.parse(value); } catch (Exception ignored) { return null; }
    }
    private static String measurement(JSONObject object) {
        if (!ok(object)) return "";
        String value = object.optString("value", ""), unit = object.optString("unit", "");
        try { if (!Double.isFinite(Double.parseDouble(value))) return ""; }
        catch (Exception ignored) { return ""; }
        return value + (unit.isEmpty() || unit.equals("%") || unit.equals("\u00b0") ? "" : " ") + unit;
    }
    private static String temperature(String raw, String source, String target) {
        String from = source.equals("C") || source.equals("\u2103") || source.equals("\u00b0C") ? "C"
                : source.equals("F") || source.equals("\u2109") || source.equals("\u00b0F") ? "F" : "";
        if (from.isEmpty() || !(target.equals("C") || target.equals("F"))) return "";
        try {
            double number = Double.parseDouble(raw);
            if (!Double.isFinite(number) || number < -250 || number > 500) return "";
            if (!from.equals(target)) number = target.equals("F") ? number * 9d / 5d + 32d : (number - 32d) * 5d / 9d;
            return Long.toString(Math.round(number));
        } catch (Exception ignored) { return ""; }
    }
}
