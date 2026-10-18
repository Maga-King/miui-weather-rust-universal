package dev.weather.oneplus;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Strict parsing of real Xiaomi forecast dates and original alerts[] fields. */
final class WeatherAlertData {
    static final long DAY=86_400_000L;
    static final class Event {
        final String type, level, title, text, published;
        final long when;
        Event(String type,String level,String title,String text,String published,long when) {
            this.type=type;this.level=level;this.title=title;this.text=text;this.published=published;this.when=when;
        }
        String identity() { return type+"\n"+level+"\n"+when+"\n"+title; }
    }
    static Event alert(JSONObject value,long now) {
        if (value==null) return null;
        String title=text(value,"title"),detail=text(value,"detail"),type=text(value,"type");
        String published=text(value,"pubTime");long when=time(published);
        // Old cached warnings and malformed/future records must not become new notifications.
        if (title.isEmpty()||detail.isEmpty()||type.isEmpty()||when<=0||when>now+300_000||now-when>DAY) return null;
        return new Event(type,text(value,"level"),title,detail,published,when);
    }
    static List<Event> alerts(JSONObject response,long now) {
        List<Event> out=new ArrayList<>();JSONArray values=response.optJSONArray("alerts");
        if (values!=null) for(int i=0;i<values.length();i++) {
            Event value=alert(values.optJSONObject(i),now);if(value!=null)out.add(value);
        }
        return out;
    }
    /** Date comes from explicit sunrise dates, or the forecast issue-date offset. */
    static String forecast(JSONObject response,LocalDate date,String displayUnit,String locale,long now) {
        try {
            JSONObject daily=response.optJSONObject("forecastDaily");
            if(!ok(daily))return null;
            OffsetDateTime issued=OffsetDateTime.parse(daily.getString("pubTime"));
            long age=now-issued.toInstant().toEpochMilli();
            if(age< -300_000||age>DAY)return null;
            JSONArray suns=values(daily.optJSONObject("sunRiseSet"));int index=-1;
            if(suns!=null) for(int i=0;i<suns.length();i++) {
                JSONObject pair=suns.optJSONObject(i);
                if(pair!=null && date.equals(OffsetDateTime.parse(pair.getString("from")).toLocalDate())) {index=i;break;}
            }
            if(index<0) {
                // If explicit dates exist, a missing day is not permission to guess an index.
                if(suns!=null && suns.length()>0)return null;
                long offset=ChronoUnit.DAYS.between(issued.toLocalDate(),date);
                if(offset<0||offset>14)return null;index=(int)offset;
            }
            JSONObject temp=daily.optJSONObject("temperature");JSONArray temperatures=values(temp);
            JSONObject range=temperatures==null?null:temperatures.optJSONObject(index);
            if(range==null||!(displayUnit.equals("C")||displayUnit.equals("F")))return null;
            String source=unit(temp.optString("unit"));if(source==null)return null;
            double high=number(range.getString("from")),low=number(range.getString("to"));
            if(high<low)return null;
            if(!source.equals(displayUnit)) {
                high=displayUnit.equals("F")?high*9/5+32:(high-32)*5/9;
                low=displayUnit.equals("F")?low*9/5+32:(low-32)*5/9;
            }
            String description="";JSONArray weathers=values(daily.optJSONObject("weather"));
            JSONObject weather=weathers==null?null:weathers.optJSONObject(index);
            if(weather!=null) {
                String day=XiaomiWeatherParser.description(XiaomiWeatherParser.weatherType(weather.optInt("from",-1)),locale);
                String night=XiaomiWeatherParser.description(XiaomiWeatherParser.weatherType(weather.optInt("to",-1)),locale);
                description=day+(night.isEmpty()||night.equals(day)?"":(day.isEmpty()?"":" / ")+night);
            }
            return (description.isEmpty()?"":description+"  ")+Math.round(low)+"–"+Math.round(high)+"°"+displayUnit;
        } catch(Exception ignored) { return null; }
    }
    static long time(String raw) {try{return OffsetDateTime.parse(raw).toInstant().toEpochMilli();}catch(Exception ignored){return 0;}}
    private static String text(JSONObject o,String key){return o.optString(key,"").trim();}
    private static boolean ok(JSONObject o){return o!=null&&(!o.has("status")||o.optInt("status",-1)==0);}
    private static JSONArray values(JSONObject o){return ok(o)?o.optJSONArray("value"):null;}
    private static double number(String s) {double n=Double.parseDouble(s);if(!Double.isFinite(n)||n< -250||n>500)throw new IllegalArgumentException();return n;}
    private static String unit(String s){return s.equals("℃")||s.equals("°C")||s.equals("C")?"C":s.equals("℉")||s.equals("°F")||s.equals("F")?"F":null;}
}
