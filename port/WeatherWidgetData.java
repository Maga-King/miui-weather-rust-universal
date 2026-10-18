package dev.weather.oneplus;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Real, already formatted weather values. No defaults masquerade as observations. */
public final class WeatherWidgetData {
    public final String cityId, cityName, temperature, high, low, description, unit, zoneId;
    public final int weatherType, aqi, sunriseMinute, sunsetMinute;
    /** Provider observation/publication time, 0 when the source does not supply it. */
    public final long observedAtMs, rainStartMs;
    public final String rainDescription;
    private final double[] rainfall;

    public WeatherWidgetData(JSONObject data) throws JSONException {
        cityId = required(data, "cityId");
        cityName = required(data, "cityName");
        temperature = number(data, "temperature", false);
        high = number(data, "high", true);
        low = number(data, "low", true);
        unit = required(data, "unit");
        if (!unit.equals("C") && !unit.equals("F")) throw new JSONException("unit must be explicit C or F");
        description = data.optString("description", "");
        zoneId = data.optString("zoneId", "");
        weatherType = data.optInt("weatherType", -1);
        aqi = data.optInt("aqi", -1);
        sunriseMinute = minute(data.optInt("sunriseMinute", -1));
        sunsetMinute = minute(data.optInt("sunsetMinute", -1));
        observedAtMs = Math.max(0, data.optLong("observedAtMs", 0));
        rainStartMs = Math.max(0, data.optLong("rainStartMs", 0));
        rainDescription = data.optString("rainDescription", "");
        JSONArray rain = data.optJSONArray("rainfall");
        if (rain != null && rain.length() == 20 && rainStartMs > 0) {
            rainfall = new double[20];
            for (int i = 0; i < 20; i++) {
                double value = rain.getDouble(i);
                if (!Double.isFinite(value) || value < 0) throw new JSONException("invalid rainfall");
                rainfall[i] = value;
            }
        } else rainfall = null;
    }

    private static int minute(int value) { return value >= 0 && value < 1440 ? value : -1; }
    private static String required(JSONObject data, String key) throws JSONException {
        String value = data.getString(key).trim();
        if (value.isEmpty() || value.equals("null")) throw new JSONException("missing " + key);
        return value;
    }
    private static String number(JSONObject data, String key, boolean optional) throws JSONException {
        if (optional && (!data.has(key) || data.isNull(key) || data.optString(key).isEmpty())) return "";
        String value = required(data, key);
        if (!value.matches("-?\\d{1,3}(?:\\.\\d)?")) throw new JSONException("invalid " + key);
        return value;
    }
    public double[] rainfall() { return rainfall == null ? null : rainfall.clone(); }
    public boolean hasRain(long now) {
        // Original MinuteRainData.isShow(): the two-hour forecast expires for display after 15 minutes.
        return rainfall != null && !rainDescription.isEmpty() && now >= rainStartMs && now - rainStartMs <= 900000L;
    }
    public JSONObject toJson() throws JSONException {
        JSONObject out = new JSONObject().put("cityId", cityId).put("cityName", cityName)
            .put("temperature", temperature).put("high", high).put("low", low).put("unit", unit)
            .put("description", description).put("weatherType", weatherType).put("aqi", aqi)
            .put("sunriseMinute", sunriseMinute).put("sunsetMinute", sunsetMinute)
            .put("zoneId", zoneId).put("observedAtMs", observedAtMs)
            .put("rainStartMs", rainStartMs).put("rainDescription", rainDescription);
        if (rainfall != null) {
            JSONArray rain = new JSONArray();
            for (double value : rainfall) rain.put(value);
            out.put("rainfall", rain);
        }
        return out;
    }
}
