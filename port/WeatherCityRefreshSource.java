package dev.weather.oneplus;

import android.content.Context;
import android.os.CancellationSignal;

/** A refresh batch asks once for each independently selected real city. */
interface WeatherCityRefreshSource extends WeatherRefreshSource {
    Result fetchCity(Context context,WeatherDatabaseBridge.City city,CancellationSignal cancellation,long deadlineElapsed) throws Exception;
}
