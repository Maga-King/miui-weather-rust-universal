package dev.weather.oneplus;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.time.OffsetDateTime;
import org.json.JSONArray;
import org.json.JSONObject;

/** Pre-engine initialization, restricted to the separate test APK's private database. */
public final class TestCityDatabaseSeed {
    private TestCityDatabaseSeed() {}
    public static void prepare(Context context, JSONObject profile, long nowMs) throws Exception {
        if (!"dev.weather.test".equals(context.getPackageName())) throw new SecurityException("Test package only");
        File root = new File(context.getApplicationInfo().dataDir).getCanonicalFile();
        File file = context.getDatabasePath("weather.db").getCanonicalFile();
        if (!file.toPath().startsWith(root.toPath()) || !file.getName().equals("weather.db"))
            throw new SecurityException("Database must remain in the test APK private directory");
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IllegalStateException("Cannot create test database directory");
        JSONObject fixture = TestCityFixtures.weather(profile, nowMs);
        String schema;
        try (InputStream in = context.getAssets().open("test-city/schema.sql");
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[8192]; int n;
            while ((n = in.read(bytes)) != -1) out.write(bytes, 0, n);
            schema = out.toString("UTF-8");
        }
        try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null)) {
            db.beginTransaction();
            try {
                for (String sql : schema.split(";")) if (!sql.trim().isEmpty())
                    db.execSQL(sql.replace("CREATE TABLE ", "CREATE TABLE IF NOT EXISTS "));
                // Only this package's experimental DB is reset; there is no normal
                // weather connection, cross-package path, backup, or device adb write.
                for (String table : new String[]{"selectedcity", "raw", "weather", "aqiinfo", "alertinfo", "background", "information"})
                    db.delete(table, null, null);
                ContentValues city = new ContentValues();
                city.put("posID", TestCityFixtures.CITY_ID); city.put("flag", 0); city.put("position", 0);
                city.put("name", TestCityFixtures.CITY_NAME); city.put("street_name", "");
                city.put("longtitude", "0"); city.put("latitude", "0"); city.put("belongings", TestCityFixtures.SOURCE);
                city.put("extra", TestCityFixtures.CITY_ID); city.put("locale", "zh_CN");
                db.insertOrThrow("selectedcity", null, city);
                ContentValues raw = new ContentValues();
                raw.put("city_id", TestCityFixtures.CITY_ID); raw.put("locale", "zh_CN");
                raw.put("insert_time", "" + nowMs); raw.put("data1", fixture.toString());
                raw.put("minute_rain_data", fixture.getJSONObject("minutely").toString()); raw.put("typhoon_data", "");
                db.insertOrThrow("raw", null, raw);
                WeatherWidgetData parsed = XiaomiWeatherParser.parse(fixture, TestCityFixtures.CITY_ID,
                        TestCityFixtures.CITY_NAME, "C", "zh_CN");
                JSONObject daily = fixture.getJSONObject("forecastDaily");
                JSONArray sun = daily.getJSONObject("sunRiseSet").getJSONArray("value");
                for (int day = 0; day < 8; day++) {
                    ContentValues row = new ContentValues();
                    for (String field : new String[]{"begins","ends","ebbTide","_id","pressures","risingTide","water","winds","data1"}) row.put(field, "");
                    row.put("publish_time", "" + nowMs); row.put("city_id", TestCityFixtures.CITY_ID);
                    row.put("city_name", TestCityFixtures.CITY_NAME); row.put("description", TestCityFixtures.SOURCE + " · " + parsed.description);
                    row.put("temperature", parsed.temperature + "℃"); row.put("temperature_range", parsed.low + "℃~" + parsed.high + "℃");
                    row.put("aqilevel", "" + parsed.aqi); row.put("locale", "zh_CN"); row.put("weather_type", parsed.weatherType);
                    row.put("humidity", "65"); row.put("feel_temperature", parsed.temperature + "℃"); row.put("ultraviolet", "3");
                    row.put("wind_direction", "180"); row.put("wind_power", "12"); row.put("wind", "测试南风");
                    row.put("next_hour_rain_probability", profile.getString("rain").equals("dry") ? "0" : "80");
                    row.put("sunrise", "" + Math.floorMod(OffsetDateTime.parse(sun.getJSONObject(day).getString("from")).toInstant().toEpochMilli(), 86400000L));
                    row.put("sunset", "" + Math.floorMod(OffsetDateTime.parse(sun.getJSONObject(day).getString("to")).toInstant().toEpochMilli(), 86400000L));
                    row.put("day", day); row.put("pressure", "1013"); row.put("pressure_unit", "hPa");
                    row.put("timestamp", "" + (nowMs + day * 86400000L)); row.put("tmphighs", parsed.high); row.put("tmplows", parsed.low);
                    row.put("weathernamesfrom", parsed.description); row.put("weathernamesto", parsed.description); row.put("forecast_type", "" + parsed.weatherType);
                    db.insertOrThrow("weather", null, row);
                }
                ContentValues air = new ContentValues();
                for (String field : new String[]{"_id","title","desc","co","o3"}) air.put(field, "");
                air.put("city_id", TestCityFixtures.CITY_ID); air.put("city", TestCityFixtures.CITY_NAME);
                air.put("aqi", "" + parsed.aqi); air.put("pm25", "12"); air.put("pm10", "20");
                air.put("so2", "3"); air.put("no2", "10"); air.put("spot", TestCityFixtures.SOURCE);
                air.put("src", TestCityFixtures.SOURCE); air.put("pub_time", "" + nowMs);
                db.insertOrThrow("aqiinfo", null, air);
                db.setVersion(23); db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        }
    }
}
