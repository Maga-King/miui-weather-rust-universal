package dev.weather.oneplus;

/** Original dual card exposed through the standard Android widget picker. */
public final class WeatherDualCityWidgetProvider extends WeatherWidgetProvider {
    @Override protected int style() { return WeatherCardWidgetRenderer.DUAL; }
}
