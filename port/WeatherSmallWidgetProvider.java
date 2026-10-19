package dev.weather.oneplus;

/** Original small card exposed through the standard Android widget picker. */
public final class WeatherSmallWidgetProvider extends WeatherWidgetProvider {
    @Override protected int style() { return WeatherCardWidgetRenderer.SMALL; }
}
