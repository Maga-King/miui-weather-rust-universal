package dev.weather.oneplus;

import java.text.Normalizer;
import java.util.Locale;

/** Conservative labels from provider fields; never invents a nearby place. */
public final class AndroidLocationLabels {
    private AndroidLocationLabels() {}

    /** The default prefers a street. The opt-in rule prefers real premises/feature names. */
    public static String choose(String city, String adminArea, String locality,
            String subAdminArea, String subLocality, String street, String premises,
            String featureName, String houseNumber, boolean colorOsRules) {
        String[] places = {city, adminArea, locality, subAdminArea};
        String[] candidates = colorOsRules
                ? new String[]{premises, featureName, street, subLocality}
                : new String[]{street, subLocality};
        for (String candidate : candidates) {
            String label = text(candidate);
            if (label.isEmpty() || houseNumber(label, houseNumber)) continue;
            boolean administrative = false;
            for (String place : places) {
                if (samePlace(label, place)) { administrative = true; break; }
            }
            // Reject a repeated city before selecting, so a real street later in
            // the candidate list is not lost when a feature is just the city.
            if (!administrative) return label;
        }
        return "";
    }

    /** Use at a provider/data boundary that already has a city and street field. */
    public static String distinctStreet(String city, String street) {
        String label = text(street);
        return samePlace(city, label) ? "" : label;
    }

    public static boolean samePlace(String first, String second) {
        String a = normalized(first), b = normalized(second);
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return true;
        // A single city suffix is a safe provider spelling difference. District,
        // county, town and street suffixes can distinguish real different places.
        return a.equals(cityBase(b)) || b.equals(cityBase(a));
    }

    private static String cityBase(String value) {
        return value.endsWith("市") && value.length() > 1
                ? value.substring(0, value.length() - 1) : value;
    }

    private static boolean houseNumber(String label, String suppliedNumber) {
        String value = normalized(label);
        if (!normalized(suppliedNumber).isEmpty() && value.equals(normalized(suppliedNumber))) return true;
        // Geocoder featureName often contains only the house/unit number.
        // Keep names containing letters or a named road, e.g. "Building 3" or "3号楼总部".
        return value.matches("(?:no\\.?\\s*)?[#\\p{N}\\s/\\-]+[a-z]?(?:号|號|号楼|號樓|栋|棟|室)?");
    }

    private static String normalized(String value) {
        return Normalizer.normalize(text(value), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private static String text(String value) { return value == null ? "" : value.trim(); }
}
