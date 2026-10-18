package miui.os;
/** Configuration flags consumed by the China-edition weather module. */
public final class Build {
    public static boolean IS_INTERNATIONAL_BUILD = false;
    public static boolean IS_STABLE_VERSION = true;
    public static boolean IS_DEVELOPMENT_VERSION = false;
    public static boolean IS_TABLET = false;
    public static boolean IS_MIPAD = false;
    public static boolean IS_ALPHA_BUILD = false;
    public static boolean IS_MIUI_LITE_VERSION = false;
    public static boolean checkRegion(String region) { return "CN".equalsIgnoreCase(region); }
}
