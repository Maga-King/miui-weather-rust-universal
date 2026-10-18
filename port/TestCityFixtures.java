package dev.weather.oneplus;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Synthetic Xiaomi-v3 schema for the original parser; never a network observation. */
public final class TestCityFixtures {
    public static final String CITY_ID = "testcity:shin-nippori";
    public static final String CITY_NAME = "新日暮里（测试）";
    public static final String SOURCE = "本地测试数据（非实况）";
    public static final String[] PRESETS = {"clear_day", "clear_night", "overcast", "rain_start",
            "rain_stop", "thunderstorm", "snow", "haze"};
    private TestCityFixtures() {}

    public static boolean isTestKey(String key) { return CITY_ID.equals(key); }
    /** Reserved keys must never fall through to real HTTP, even if misspelled. */
    public static boolean isReservedKey(String key) { return key != null && key.startsWith("testcity:"); }

    public static JSONObject preset(String name) throws JSONException {
        if (!Arrays.asList(PRESETS).contains(name)) throw new JSONException("Unknown test preset");
        int code = name.equals("overcast") ? 2 : name.startsWith("rain_") ? 7
                : name.equals("thunderstorm") ? 4 : name.equals("snow") ? 15 : name.equals("haze") ? 53 : 0;
        int temp = name.equals("snow") ? -8 : name.equals("thunderstorm") ? 28 : 24;
        return new JSONObject().put("schemaVersion", 1).put("preset", name).put("temperatureC", temp)
                .put("highC", temp + 4).put("lowC", temp - 5).put("weatherV7", code)
                .put("phase", name.equals("clear_night") ? "night" : "day")
                .put("aqi", name.equals("haze") ? 225 : 35)
                .put("rain", name.equals("rain_start") ? "start" : name.equals("rain_stop") ? "stop"
                        : name.equals("thunderstorm") ? "heavy" : "dry");
    }

    public static JSONObject validate(JSONObject input) throws JSONException {
        JSONObject p = new JSONObject(input.toString());
        if (bounded(p, "schemaVersion", 1, 1) != 1) throw new JSONException("Unsupported test profile version");
        int temperature = bounded(p, "temperatureC", -99, 70);
        int high = bounded(p, "highC", -99, 70), low = bounded(p, "lowC", -99, 70);
        if (low > temperature || high < temperature || low > high) throw new JSONException("Invalid test temperature range");
        bounded(p, "aqi", 0, 500);
        int code = bounded(p, "weatherV7", 0, 301);
        if (!Arrays.asList(0,1,2,18,53,3,7,13,14,6,33,26,21,54,4,55,29,30,56,5,15,8,57,32,27,22,16,49,20,9,28,23,19,58,31,17,10,24,11,25,12,301).contains(code))
            throw new JSONException("Unsupported original V7 condition");
        if (!Arrays.asList("day", "night").contains(p.getString("phase"))) throw new JSONException("Invalid test phase");
        if (!Arrays.asList("dry", "start", "stop", "heavy").contains(p.getString("rain"))) throw new JSONException("Invalid test rain");
        return p;
    }

    private static int bounded(JSONObject p, String key, int low, int high) throws JSONException {
        Object value = p.get(key);
        if (!(value instanceof Number) || ((Number)value).doubleValue() != ((Number)value).intValue())
            throw new JSONException("Expected integer: " + key);
        int result = ((Number)value).intValue();
        if (result < low || result > high) throw new JSONException("Out of range: " + key);
        return result;
    }

    /**
     * Instant remains real render time. A deliberately synthetic city offset puts its
     * local clock at noon/midnight for stable phase tests; no stale/future observations.
     * Consumers which ignore ISO offsets need a test-only phase/clock adapter as well.
     */
    public static JSONObject weather(JSONObject profile, long nowMs) throws JSONException {
        JSONObject p = validate(profile);
        OffsetDateTime utc = Instant.ofEpochMilli(nowMs).atOffset(ZoneOffset.UTC);
        int targetHour = p.getString("phase").equals("day") ? 12 : 0;
        int offsetHours = Math.floorMod(targetHour - utc.getHour() + 12, 24) - 12;
        OffsetDateTime now = utc.withOffsetSameInstant(ZoneOffset.ofHours(offsetHours));
        String time = now.toString();
        int temp = p.getInt("temperatureC"), code = p.getInt("weatherV7"), aqi = p.getInt("aqi");
        JSONObject wind = new JSONObject().put("direction", measurement("°", "180"))
                .put("speed", measurement("km/h", "12"));
        JSONObject current = new JSONObject().put("pubTime", time).put("temperature", measurement("℃", "" + temp))
                .put("feelsLike", measurement("℃", "" + temp)).put("humidity", measurement("%", "65"))
                .put("pressure", measurement("hPa", "1013")).put("visibility", measurement("km", "10"))
                .put("uvIndex", "3").put("weather", "" + code).put("wind", wind);
        JSONArray sun = new JSONArray(), temperatures = new JSONArray(), conditions = new JSONArray();
        JSONArray winds = new JSONArray(), dailyAqi = new JSONArray(), probability = new JSONArray();
        JSONArray directions = new JSONArray(), speeds = new JSONArray();
        for (int i = 0; i < 15; i++) {
            OffsetDateTime date = now.plusDays(i);
            sun.put(pair(date.withHour(6).withMinute(0).withSecond(0).withNano(0).toString(),
                    date.withHour(18).withMinute(0).withSecond(0).withNano(0).toString()));
            temperatures.put(pair("" + p.getInt("highC"), "" + p.getInt("lowC")));
            conditions.put(pair("" + code, "" + code));
            directions.put(pair("180", "180")); speeds.put(pair("12", "12"));
            dailyAqi.put(aqi); probability.put(p.getString("rain").equals("dry") ? "0" : "80");
        }
        JSONObject daily = new JSONObject().put("status", 0).put("pubTime", time)
                .put("temperature", series(temperatures).put("unit", "℃"))
                .put("weather", series(conditions)).put("sunRiseSet", series(sun))
                .put("aqi", series(dailyAqi)).put("precipitationProbability", series(probability))
                .put("wind", new JSONObject().put("direction", series(directions).put("unit", "°"))
                        .put("speed", series(speeds).put("unit", "km/h")));
        JSONArray hourlyTemperature = new JSONArray(), hourlyWeather = new JSONArray(), hourlyAqi = new JSONArray();
        for (int i = 0; i < 24; i++) {
            hourlyTemperature.put(temp); hourlyWeather.put(code); hourlyAqi.put(aqi);
            winds.put(new JSONObject().put("datetime", now.plusHours(i).toString()).put("direction", "180").put("speed", "12"));
        }
        String hourlyTime = now.withMinute(0).withSecond(0).withNano(0).toString();
        JSONObject hourly = new JSONObject().put("status", 0).put("desc", SOURCE)
                .put("temperature", series(hourlyTemperature).put("unit", "℃").put("pubTime", hourlyTime))
                .put("weather", series(hourlyWeather).put("pubTime", hourlyTime))
                .put("aqi", series(hourlyAqi).put("pubTime", hourlyTime)).put("wind", series(winds));
        String rain = p.getString("rain");
        JSONArray minutes = new JSONArray();
        for (int i = 0; i < 120; i++) minutes.put(rain.equals("heavy") ? 8.0
                : rain.equals("start") && i >= 20 ? 1.2 : rain.equals("stop") && i < 30 ? 1.2 : 0.0);
        String rainText = SOURCE + (rain.equals("start") ? "：20分钟后开始降雨"
                : rain.equals("stop") ? "：30分钟后降雨停止" : rain.equals("heavy") ? "：持续强降雨" : "：未来两小时无降雨");
        JSONObject minutely = new JSONObject().put("precipitation", series(minutes).put("pubTime", time)
                .put("isShow", !rain.equals("dry")).put("description", rainText));
        JSONObject air = new JSONObject().put("status", 0).put("pubTime", time).put("aqi", "" + aqi)
                .put("pm25", "12").put("pm10", "20").put("o3", "30").put("no2", "10")
                .put("so2", "3").put("co", "0.3").put("primary", "").put("src", SOURCE)
                .put("suggest", SOURCE).put("brandInfo", brand());
        JSONObject yesterday = new JSONObject().put("status", 0).put("date", now.minusDays(1).toString())
                .put("tempMax", "" + p.getInt("highC")).put("tempMin", "" + p.getInt("lowC"))
                .put("weatherStart", "" + code).put("weatherEnd", "" + code).put("aqi", "" + aqi)
                .put("sunRise", now.minusDays(1).withHour(6).toString()).put("sunSet", now.minusDays(1).withHour(18).toString())
                .put("windDircStart", "180").put("windDircEnd", "180").put("windSpeedStart", "12").put("windSpeedEnd", "12");
        return new JSONObject().put("current", current).put("forecastDaily", daily).put("forecastHourly", hourly)
                .put("aqi", air).put("minutely", minutely).put("yesterday", yesterday)
                .put("indices", new JSONObject().put("status", 0).put("pubTime", time).put("indices", new JSONArray()
                        .put(new JSONObject().put("type", "uvIndex").put("value", "3"))
                        .put(new JSONObject().put("type", "humidity").put("value", "65"))
                        .put(new JSONObject().put("type", "feelsLike").put("value", "" + temp))
                        .put(new JSONObject().put("type", "pressure").put("value", "1013"))
                        .put(new JSONObject().put("type", "carWash").put("value", "1"))
                        .put(new JSONObject().put("type", "sports").put("value", "0"))))
                .put("alerts", new JSONArray()).put("preHour", new JSONArray()).put("brandInfo", brand())
                .put("url", new JSONObject().put("weathercn", "").put("caiyun", ""))
                .put("sourceMaps", new JSONObject()).put("updateTime", nowMs)
                .put("_testCity", new JSONObject().put("synthetic", true).put("cityId", CITY_ID)
                        .put("cityName", CITY_NAME).put("label", SOURCE).put("phase", p.getString("phase")));
    }
    private static JSONObject measurement(String unit, String value) throws JSONException {
        return new JSONObject().put("unit", unit).put("value", value);
    }
    private static JSONObject pair(String from, String to) throws JSONException {
        return new JSONObject().put("from", from).put("to", to);
    }
    private static JSONObject series(JSONArray values) throws JSONException {
        return new JSONObject().put("status", 0).put("value", values);
    }
    private static JSONObject brand() throws JSONException {
        return new JSONObject().put("brands", new JSONArray().put(new JSONObject().put("brandId", "local_test")
                .put("logo", "").put("url", "").put("names", new JSONObject()
                        .put("zh_CN", SOURCE).put("zh_TW", SOURCE).put("en_US", "LOCAL TEST DATA"))));
    }
}
