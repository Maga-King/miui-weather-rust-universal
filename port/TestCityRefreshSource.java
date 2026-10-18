package dev.weather.oneplus;

import android.content.Context;
import android.os.CancellationSignal;

/** Test data never increments real-network freshness or invokes a provider. */
public final class TestCityRefreshSource implements WeatherRefreshSource {
    public TestCityRefreshSource() {}
    @Override public WeatherWidgetData localWidget(Context context) throws Exception {
        return TestCityMode.widget(context, WeatherDatabaseBridge.temperatureUnit(context),
                context.getResources().getConfiguration().getLocales().get(0).toString(),
                System.currentTimeMillis());
    }
    @Override public Result fetch(Context context, WeatherWidgetData previous, CancellationSignal cancellation) {
        cancellation.throwIfCanceled();
        return Result.blocked("local_test_mode");
    }
}
