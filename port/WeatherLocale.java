package dev.weather.oneplus;

import java.util.Locale;

/** Keep the original mainland-language gate while accepting Android's explicit Hans script. */
public final class WeatherLocale {
    private WeatherLocale() {}
    public static boolean matchesSimplifiedChinese(Locale current,Object expected) {
        if(current==null)return false;
        if(current.equals(expected))return true;
        return Locale.SIMPLIFIED_CHINESE.equals(expected)
                && "zh".equals(current.getLanguage()) && "CN".equals(current.getCountry())
                && (current.getScript().isEmpty()||"Hans".equals(current.getScript()));
    }
}
