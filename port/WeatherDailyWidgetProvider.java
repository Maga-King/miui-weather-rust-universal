package dev.weather.oneplus;

/** Original daily card exposed through the standard Android widget picker. */
public final class WeatherDailyWidgetProvider extends WeatherWidgetProvider {
    @Override protected int style() { return WeatherCardWidgetRenderer.DAILY; }
}
