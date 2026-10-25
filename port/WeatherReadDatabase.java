package dev.weather.oneplus;

import android.content.ContentProviderClient;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.os.RemoteException;

/** Read through the separate database process, never a second SQLite in Dart's process. */
final class WeatherReadDatabase implements AutoCloseable {
    private final ContentProviderClient client;
    private final Uri uri;

    static WeatherReadDatabase open(Context context) {
        return new WeatherReadDatabase(context);
    }

    private WeatherReadDatabase(Context context) {
        uri = Uri.parse("content://" + context.getPackageName() + ".weatherdb/query");
        client = context.getContentResolver().acquireContentProviderClient(uri);
        if (client == null) throw new SQLiteException("Weather database reader unavailable");
    }

    Cursor rawQuery(String sql, String[] args) {
        try {
            Cursor result = client.query(uri, null, sql, args, null);
            if (result == null) throw new SQLiteException("Weather database reader returned no cursor");
            return result;
        } catch (RemoteException error) {
            SQLiteException failure = new SQLiteException("Weather database reader interrupted");
            failure.initCause(error);
            throw failure;
        }
    }

    @Override public void close() { client.close(); }
}
