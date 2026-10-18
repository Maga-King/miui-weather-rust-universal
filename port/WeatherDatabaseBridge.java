package dev.weather.oneplus;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.util.Base64;
import android.util.Log;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import org.json.JSONObject;

/** Read-only access to the original Dart SQLite database. Never creates or updates it. */
public final class WeatherDatabaseBridge {
    private WeatherDatabaseBridge() {}

    /** False only when the database is absent or a successful query confirms no city. */
    public static boolean hasSelectedCity(Context context) {
        File file = context.getDatabasePath("weather.db");
        if (!file.isFile()) return false;
        try (SQLiteDatabase db = open(file);
             Cursor rows = db.rawQuery("SELECT 1 FROM selectedcity LIMIT 1", null)) {
            return rows.moveToFirst();
        } catch (SQLiteException error) {
            // A busy/migrating database must not unexpectedly redirect the user to search.
            Log.w("WeatherDatabase", "Cannot check selected city: " + error.getClass().getSimpleName());
            return true;
        }
    }

    /** First city uses the app's position ordering; HTTP locationKey is extra, not posID. */
    static City firstCity(Context context) {
        File file = context.getDatabasePath("weather.db");
        if (!file.isFile()) return null;
        try (SQLiteDatabase db = open(file);
             Cursor row = db.rawQuery("SELECT posID,name,extra,latitude,longtitude,flag,locale "
                     + "FROM selectedcity ORDER BY position ASC, flag DESC LIMIT 1", null)) {
            if (!row.moveToFirst()) return null;
            Locale locale = context.getResources().getConfiguration().getLocales().get(0);
            return new City(text(row, 0), text(row, 1), text(row, 2), text(row, 3),
                    text(row, 4), row.getInt(5) == 1, locale.toString(), temperatureUnit(context));
        }
    }

    /**
     * Returns a real saved provider observation for the first selected city, or null.
     * Import through WeatherWidgetCache.publishCached; this is NOT a network success.
     */
    public static WeatherWidgetData readCached(Context context) {
        try {
            City city = firstCity(context);
            if (city == null || !city.valid()) return null;
            try (SQLiteDatabase db = open(context.getDatabasePath("weather.db"));
                 Cursor row = db.rawQuery("SELECT data1,minute_rain_data FROM raw WHERE city_id=? LIMIT 1",
                         new String[]{city.id})) {
                if (!row.moveToFirst() || row.isNull(0)) return null;
                JSONObject data = new JSONObject(row.getString(0));
                if (!data.has("minutely") && !row.isNull(1) && !row.getString(1).isEmpty()) {
                    try { data.put("minutely", new JSONObject(row.getString(1))); }
                    catch (Exception ignored) { /* Invalid optional rain cannot invalidate weather. */ }
                }
                WeatherWidgetData observation = XiaomiWeatherParser.parse(data, city.id, city.name,
                        city.unit, city.locale);
                City current = firstCity(context);
                return city.sameSelection(current) ? observation : null;
            }
        } catch (Exception error) {
            Log.w("WeatherDatabase", "Cannot read saved observation: " + error.getClass().getSimpleName());
            return null;
        }
    }

    private static SQLiteDatabase open(File file) {
        return SQLiteDatabase.openDatabase(file.getAbsolutePath(), null,
                SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
    }
    private static String text(Cursor row, int index) {
        return row.isNull(index) ? "" : row.getString(index).trim();
    }

    /** Same storage naming and StandardMessageCodec booleans as AndroidPreferences. */
    static String temperatureUnit(Context context) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    "com.miui.weather2.unit".getBytes(StandardCharsets.UTF_8));
            String name = "prefs_" + Base64.encodeToString(digest,
                    Base64.NO_WRAP | Base64.URL_SAFE | Base64.NO_PADDING);
            SharedPreferences prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE);
            String encoded = prefs.getString("use_temperature_unit", null);
            if (encoded != null) {
                byte[] value = Base64.decode(encoded, Base64.NO_WRAP);
                if (value.length == 1 && value[0] == 1) return "C";
                if (value.length == 1 && value[0] == 2) return "F";
                throw new IllegalStateException("Invalid temperature preference");
            }
            // Original domestic build's default (Dart 0x38bc98); no inferred SQL unit value.
            return "C";
        } catch (IllegalStateException error) { throw error; }
        catch (Exception error) { throw new IllegalStateException("Cannot read temperature preference", error); }
    }

    static final class City {
        final String id, name, locationKey, latitude, longitude, locale, unit;
        final boolean located;
        City(String id, String name, String key, String lat, String lon,
                boolean located, String locale, String unit) {
            this.id=id; this.name=name; locationKey=key; latitude=lat; longitude=lon;
            this.located=located; this.locale=locale; this.unit=unit;
        }
        boolean valid() {
            if (id.isEmpty() || name.isEmpty() || locationKey.isEmpty()) return false;
            try {
                double lat=Double.parseDouble(latitude), lon=Double.parseDouble(longitude);
                return Double.isFinite(lat) && Double.isFinite(lon)
                        && lat>=-90 && lat<=90 && lon>=-180 && lon<=180;
            } catch (NumberFormatException error) { return false; }
        }
        boolean sameSelection(City other) {
            return other != null && id.equals(other.id) && locationKey.equals(other.locationKey)
                    && latitude.equals(other.latitude) && longitude.equals(other.longitude)
                    && located==other.located && name.equals(other.name)
                    && unit.equals(other.unit) && locale.equals(other.locale);
        }
    }
}
