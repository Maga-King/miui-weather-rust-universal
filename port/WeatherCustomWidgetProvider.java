package dev.weather.oneplus;

/** Original custom card exposed through the standard Android widget picker. */
public final class WeatherCustomWidgetProvider extends WeatherWidgetProvider {
    @Override protected int style() { return WeatherCardWidgetRenderer.CUSTOM; }
}
