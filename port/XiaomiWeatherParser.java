package dev.weather.oneplus;

import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Original Xiaomi v3 JSON response -> the original widget's V6 weather-code contract. */
final class XiaomiWeatherParser {
    private XiaomiWeatherParser() {}
    // Weather 17 WeatherType.V7V6_MAP; unknown codes remain unknown rather than becoming sunny.
    private static final int[] V7 = {0,1,2,18,53,3,7,13,14,6,33,26,21,54,4,55,29,30,56,5,15,8,57,32,27,22,16,49,20,9,28,23,19,58,31,17,10,24,11,25,12,301};
    private static final int[] V6 = {0,1,2,3,24,8,11,14,17,12,17,16,10,24,7,24,23,21,24,22,16,10,3,3,15,9,15,3,19,9,13,6,25,3,18,13,7,5,5,4,4,11};
    // Original Weather 17 resource arrays, indexed by V6 code.
    private static final String[] ZH = {"晴","多云","阴","雾","特大暴雨","大暴雨","暴雨","雷阵雨","阵雨","大雨","中雨","小雨","雨夹雪","暴雪","阵雪","大雪","中雪","小雪","强沙尘暴","沙尘暴","沙尘","扬沙","冰雹","浮尘","霾","冻雨"};
    private static final String[] ZH_TW = {"晴","多雲","陰","霧","特大暴雨","大暴雨","暴雨","雷陣雨","陣雨","大雨","中雨","小雨","雨夾帶雪","暴雪","陣雪","大雪","中雪","小雪","強沙塵暴","沙塵暴","沙塵","揚沙","冰雹","浮塵","霾","凍雨"};
    private static final String[] EN = {"Clear","Cloudy","Overcast","Mist","Heavy rainstorm","Downpour","Rainstorm","Thunderstorm","Showers","Heavy rain","Moderate rain","Light rain","Sleet","Blizzard","Snow showers","Heavy snow","Moderate snow","Light snow","Strong sandstorm","Sandstorm","Light sandstorm","Dust storm","Hail","Dust","Haze","Freezing rain"};

    static WeatherWidgetData parse(JSONObject response, String cityId, String cityName,
            String displayUnit, String locale) throws JSONException {
        if (!displayUnit.equals("C") && !displayUnit.equals("F")) throw new JSONException("Invalid display unit");
        JSONObject current = response.getJSONObject("current");
        if (!successful(current)) throw new JSONException("Current weather unavailable");
        JSONObject temperature = current.getJSONObject("temperature");
        if (!successful(temperature)) throw new JSONException("Temperature unavailable");
        String sourceUnit = unit(temperature.getString("unit"));
        String value = convert(temperature.getString("value"), sourceUnit, displayUnit);
        OffsetDateTime observed = date(current.optString("pubTime", ""));
        int v7 = current.optInt("weather", -1), v6 = weatherType(v7);
        JSONObject out = new JSONObject().put("cityId", cityId).put("cityName", cityName)
                .put("temperature", value).put("unit", displayUnit).put("weatherType", v6)
                .put("description", description(v6, locale))
                .put("observedAtMs", observed == null ? 0 : observed.toInstant().toEpochMilli())
                .put("zoneId", observed == null ? "" : observed.getOffset().getTotalSeconds()==0
                        ? "UTC" : "GMT" + observed.getOffset().getId());

        JSONObject aqi = response.optJSONObject("aqi");
        if (aqi != null && successful(aqi)) {
            int index=aqi.optInt("aqi", -1);
            if (index>=0) out.put("aqi", index);
        }
        JSONObject daily=response.optJSONObject("forecastDaily");
        if (daily != null && successful(daily) && observed != null) {
            JSONArray sun=values(daily.optJSONObject("sunRiseSet"));
            int day=forecastIndex(daily, sun, observed.toLocalDate());
            if (day>=0) {
                JSONObject temps=daily.optJSONObject("temperature");
                JSONObject range=item(values(temps), day);
                if (range != null) {
                    try {
                        String forecastUnit=unit(temps.getString("unit"));
                        out.put("high",convert(range.getString("from"),forecastUnit,displayUnit));
                        out.put("low",convert(range.getString("to"),forecastUnit,displayUnit));
                    } catch (JSONException ignored) { out.remove("high"); out.remove("low"); }
                }
                JSONObject pair=item(sun, day);
                if (pair != null) {
                    OffsetDateTime rise=date(pair.optString("from", "")), set=date(pair.optString("to", ""));
                    if (rise != null && rise.toLocalDate().equals(observed.toLocalDate()))
                        out.put("sunriseMinute",rise.getHour()*60+rise.getMinute());
                    if (set != null && set.toLocalDate().equals(observed.toLocalDate()))
                        out.put("sunsetMinute",set.getHour()*60+set.getMinute());
                }
            }
        }
        addRain(out,response.optJSONObject("minutely"));
        return new WeatherWidgetData(out);
    }

    private static int forecastIndex(JSONObject daily, JSONArray suns, LocalDate date) {
        if (suns != null) for (int i=0;i<suns.length();i++) {
            JSONObject pair=item(suns,i);
            OffsetDateTime rise=pair==null?null:date(pair.optString("from", ""));
            if (rise!=null && rise.toLocalDate().equals(date)) return i;
        }
        OffsetDateTime issued=date(daily.optString("pubTime", ""));
        if (issued==null) return -1;
        long offset=ChronoUnit.DAYS.between(issued.toLocalDate(),date);
        return offset>=0 && offset<=30 ? (int)offset : -1;
    }
    private static void addRain(JSONObject out, JSONObject minutely) throws JSONException {
        JSONObject rain=minutely==null?null:minutely.optJSONObject("precipitation");
        if (rain==null || !successful(rain) || !rain.optBoolean("isShow",false)) return;
        OffsetDateTime issued=date(rain.optString("pubTime", ""));
        JSONArray values=rain.optJSONArray("value");
        String text=rain.optString("description", "");
        // Original Weather 17 Z1.R0.a0 averages consecutive groups into 20 widget bars.
        // This endpoint's confirmed 120 minute horizon matches the renderer's fixed two hours.
        if (issued==null || values==null || values.length()!=120 || text.isEmpty()) return;
        JSONArray checked=new JSONArray();
        int group=values.length()/20;
        for (int i=0;i<20;i++) {
            double sum=0;
            for (int j=0;j<group;j++) {
                double value=values.optDouble(i*group+j,Double.NaN);
                if (!Double.isFinite(value) || value<0) return;
                sum+=value;
            }
            checked.put(sum/group);
        }
        out.put("rainfall",checked).put("rainStartMs",issued.toInstant().toEpochMilli())
                .put("rainDescription",text);
    }
    private static JSONObject item(JSONArray array,int index) {
        return array!=null && index>=0 && index<array.length()?array.optJSONObject(index):null;
    }
    private static JSONArray values(JSONObject object) {
        return object!=null && successful(object)?object.optJSONArray("value"):null;
    }
    private static boolean successful(JSONObject object) { return !object.has("status") || object.optInt("status",-1)==0; }
    private static OffsetDateTime date(String value) {
        try { return OffsetDateTime.parse(value); } catch (Exception ignored) { return null; }
    }
    private static String unit(String value) throws JSONException {
        if (value.equals("℃") || value.equals("°C") || value.equals("C")) return "C";
        if (value.equals("℉") || value.equals("°F") || value.equals("F")) return "F";
        throw new JSONException("Unknown provider temperature unit");
    }
    private static String convert(String raw,String from,String to) throws JSONException {
        try {
            double value=Double.parseDouble(raw);
            if (!Double.isFinite(value) || value < -250 || value > 500) throw new NumberFormatException();
            if (!from.equals(to)) value=to.equals("F")?value*9d/5d+32d:(value-32d)*5d/9d;
            return Long.toString(Math.round(value));
        } catch (NumberFormatException error) { throw new JSONException("Invalid provider temperature"); }
    }
    static int weatherType(int code) {
        for (int i=0;i<V7.length;i++) if (V7[i]==code) return V6[i];
        return -1;
    }
    static String description(int type,String locale) {
        if (type<0 || type>=ZH.length) return "";
        boolean chinese=locale!=null && locale.startsWith("zh");
        boolean traditional=chinese && (locale.contains("TW") || locale.contains("HK") || locale.contains("Hant"));
        return (traditional?ZH_TW:chinese?ZH:EN)[type];
    }
}
