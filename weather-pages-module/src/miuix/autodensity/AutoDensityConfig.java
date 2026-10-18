package miuix.autodensity;
import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
/** Keep the host's public Android display metrics; MIUI global density mutation is unavailable. */
public final class AutoDensityConfig {
    private static final AutoDensityConfig INSTANCE = new AutoDensityConfig();
    public static AutoDensityConfig init(Application app) { return INSTANCE; }
    public static AutoDensityConfig init(Application app, boolean updateSystem) { return INSTANCE; }
    public static void updateDensity(Context context) { }
    public static boolean updateDensityByConfig(Context context, Configuration config) { return false; }
    public static void forceUpdateDensity(Context context) { }
    public static boolean shouldUpdateSystemResource() { return false; }
    public static boolean isShouldAdaptAutoDensity(Application app) { return false; }
    public static Configuration updateDensityOverrideConfiguration(Context context, Configuration config) { return config; }
    public static Context createAutoDensityContextWrapper(Context context) { return context; }
    public static Context createAutoDensityContextWrapper(Context context, int density) { return context; }
    public static Context createAutoDensityContextWrapper(Context context, int density, int display) { return context; }
    public static Context createAutoDensityContextWrapperWithBaseDp(Context context, int dp) { return context; }
    public static void enableUpdateInConfigChangeFragment(boolean value) { }
    public static void setForceDeviceScale(float value) { }
    public static void setForcePPI(int value) { }
    public static void setUpdateSystemRes(boolean value) { }
    public static void setUseDeprecatedDensityLogic(boolean value) { }
    public static void setUseStableDensityLogic(boolean value) { }
}
