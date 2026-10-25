package dev.weather.oneplus;

import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.util.Log;
import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * System SQLite reads the weather WAL only in :weather_db. The original Dart
 * engine uses its bundled SQLite in the main process. POSIX locks are per
 * process, so merely marking an in-process connection READONLY is insufficient
 * when the two libraries do not share SQLite's connection/lock registry.
 */
public final class WeatherDatabaseProvider extends ContentProvider {
    private SQLiteDatabase database;
    private static final Set<String> QUERIES = new HashSet<>(Arrays.asList(
        WeatherDatabaseBridge.HAS_CACHED_WEATHER_QUERY,
        "SELECT 1 FROM selectedcity LIMIT 1",
        "SELECT posID,name,extra,latitude,longtitude,flag,locale FROM selectedcity ORDER BY position ASC, flag DESC LIMIT 1",
        "SELECT posID,name,extra,latitude,longtitude,flag,locale FROM selectedcity ORDER BY position ASC, flag DESC",
        "SELECT posID,name,extra,latitude,longtitude,flag FROM selectedcity ORDER BY position ASC,flag DESC",
        "SELECT posID,name,flag FROM selectedcity ORDER BY position ASC,flag DESC",
        "SELECT data1,minute_rain_data FROM raw WHERE city_id=? LIMIT 1",
        "SELECT data1 FROM raw WHERE city_id=? LIMIT 1"
    ));

    @Override public boolean onCreate() {
        if (!Application.getProcessName().equals(getContext().getPackageName() + ":weather_db"))
            throw new IllegalStateException("Weather database reader requires its separate process");
        Log.i("WeatherDatabase", "Read-only SQLite isolated in " + Application.getProcessName());
        return true;
    }

    private synchronized SQLiteDatabase database() {
        if (database != null && database.isOpen()) return database;
        File file = getContext().getDatabasePath("weather.db");
        if (!file.isFile()) throw new SQLiteException("Weather database is not ready");
        // Do not let Android's default corruption handler delete the original
        // engine's database. Preserve it and let the existing callers defer.
        database = SQLiteDatabase.openDatabase(file.getAbsolutePath(), null,
            SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            db -> Log.e("WeatherDatabase", "Read-only database error; original files preserved"));
        return database;
    }

    @Override public Cursor query(Uri uri, String[] projection, String sql,
                                  String[] args, String sortOrder) {
        if (!"/query".equals(uri.getPath()) || !QUERIES.contains(sql)
                || projection != null || sortOrder != null)
            throw new IllegalArgumentException("Unsupported weather database read");
        int expected = sql.contains("?") ? 1 : 0;
        if ((args == null ? 0 : args.length) != expected)
            throw new IllegalArgumentException("Invalid weather database arguments");
        return database().rawQuery(sql, args);
    }

    @Override public String getType(Uri uri) { return "vnd.android.cursor.dir/vnd.weather.readonly"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read-only"); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException("Read-only"); }
    @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException("Read-only"); }
}
