package dev.weather.oneplus;

/** Original hourly card exposed through the standard Android widget picker. */
public final class WeatherHourlyWidgetProvider extends WeatherWidgetProvider {
    @Override protected int style() { return WeatherCardWidgetRenderer.HOURLY; }
}
