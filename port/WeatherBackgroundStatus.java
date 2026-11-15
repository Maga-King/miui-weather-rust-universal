package dev.weather.oneplus;

import android.app.ActivityManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
import java.util.List;

/** Reads this package's actual scheduling and system grants without starting a worker. */
final class WeatherBackgroundStatus {
    Boolean batteryExempt, powerSave, backgroundRestricted, notificationsAllowed;
    int standbyBucket=-1, refreshJobs, reminderJobs;
    boolean jobsAvailable, networkAllowed, alertChannelBlocked;
    boolean alertChannelExists, localBackgroundNeeded, dailyEnabled, alertEnabled, abruptEnabled;
    boolean nightUpdates, notificationQuietHours, sdkRegistered, sdkTransportBlocked, refreshInProgress;
    boolean remoteDaily, remoteAlert, remoteAbrupt;
    long nextRefreshDue, lastAttempt, lastSuccess, lastReminderCheck, lastPushReceipt;
    String lastError="";

    static WeatherBackgroundStatus read(Context context) {
        WeatherBackgroundStatus out=new WeatherBackgroundStatus();
        out.networkAllowed=WeatherRefresh.networkAllowed(context);
        out.dailyEnabled=WeatherAlerts.dailyEnabled(context);
        out.alertEnabled=WeatherAlerts.alertEnabled(context);
        out.abruptEnabled=WeatherAlerts.abruptEnabled(context);
        out.localBackgroundNeeded=WeatherRefresh.backgroundNeeded(context);
        out.refreshInProgress=WeatherRefresh.IN_FLIGHT.get();
        out.nightUpdates=WeatherRefresh.nightUpdateAllowed(context);
        out.notificationQuietHours=AndroidWeatherNotifications.quietHoursEnabled(context);
        try {out.notificationsAllowed=AndroidWeatherNotifications.notificationsAllowed(context);}
        catch(RuntimeException ignored) {}
        try {
            NotificationManager notifications=context.getSystemService(NotificationManager.class);
            NotificationChannel channel=notifications==null?null:notifications.getNotificationChannel(AndroidWeatherNotifications.DEFAULT_CHANNEL);
            out.alertChannelExists=channel!=null;
            out.alertChannelBlocked=channel!=null&&channel.getImportance()==NotificationManager.IMPORTANCE_NONE;
        }catch(RuntimeException ignored) {}
        try {
            PowerManager power=context.getSystemService(PowerManager.class);
            if(power!=null) {
                out.batteryExempt=power.isIgnoringBatteryOptimizations(context.getPackageName());
                out.powerSave=power.isPowerSaveMode();
            }
        }catch(RuntimeException ignored) {}
        try {
            ActivityManager activity=context.getSystemService(ActivityManager.class);
            if(activity!=null)out.backgroundRestricted=activity.isBackgroundRestricted();
        }catch(RuntimeException ignored) {}
        try {
            UsageStatsManager usage=context.getSystemService(UsageStatsManager.class);
            if(usage!=null)out.standbyBucket=usage.getAppStandbyBucket();
        }catch(RuntimeException ignored) {}
        try {
            JobScheduler jobs=context.getSystemService(JobScheduler.class);
            List<JobInfo> pending=jobs==null?null:jobs.getAllPendingJobs();
            out.jobsAvailable=pending!=null;
            if(pending!=null)for(JobInfo job:pending) {
                if(!context.getPackageName().equals(job.getService().getPackageName()))continue;
                String service=job.getService().getClassName();
                if(service.equals(WeatherRefreshJobService.class.getName())) {
                    out.refreshJobs++;
                    long due=job.getExtras().getLong("due_ms",0);
                    if(due>0&&(out.nextRefreshDue==0||due<out.nextRefreshDue))out.nextRefreshDue=due;
                }else if(service.equals(WeatherAlertJobService.class.getName()))out.reminderJobs++;
            }
        }catch(RuntimeException ignored) {}
        SharedPreferences refresh=WeatherRefresh.state(context);
        out.lastAttempt=refresh.getLong("last_attempt_ms",0);
        out.lastSuccess=refresh.getLong("last_success_ms",0);
        out.lastError=refresh.getString("last_error","");
        SharedPreferences reminders=WeatherAlerts.state(context);
        out.lastReminderCheck=reminders.getLong("last_background_check_ms",0);
        out.remoteDaily=reminders.getBoolean("remote_daily",false);
        out.remoteAlert=reminders.getBoolean("remote_alert",false);
        out.remoteAbrupt=reminders.getBoolean("remote_abrupt",false);
        // Only read verification booleans and callback times; never display identifiers or secrets.
        SharedPreferences push=context.getSharedPreferences("weather_mipush_bridge_v1",Context.MODE_PRIVATE);
        out.sdkRegistered=push.getBoolean("verified",false);
        out.sdkTransportBlocked=push.getBoolean("transport_blocked",false);
        out.lastPushReceipt=Math.max(push.getLong("last_pass_through_at",0),push.getLong("last_sdk_notification_at",0));
        return out;
    }

    static Intent applicationSettings(String packageName) {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.fromParts("package",packageName,null));
    }
    static Intent notificationSettings(String packageName) {
        return new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName);
    }
    static Intent batterySettings() {return new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);}

    static String refreshError(String reason,boolean chinese) {
        if(reason==null||reason.isEmpty())return "";
        switch(reason) {
            case "network_consent_required":return chinese?"尚未允许联网":"Network consent required";
            case "city_selection_required":return chinese?"请先添加城市":"Add a city first";
            case "selected_city_incomplete":return chinese?"城市信息暂不完整":"City information is incomplete";
            case "selected_city_changed":return chinese?"城市已更换，等待下次更新":"City changed; waiting for next update";
            case "weather_network_error":return chinese?"网络连接失败，将自动重试":"Network failed; a retry is scheduled";
            case "source_timeout":return chinese?"天气服务响应超时，将自动重试":"Weather service timed out; a retry is scheduled";
            case "cache_write_failed":return chinese?"暂时无法保存天气数据":"Weather data could not be saved";
            case "automatic_deferred":return chinese?"等待刷新间隔或夜间暂停结束":"Waiting for the refresh interval or quiet hours";
            case "source_not_configured":return chinese?"天气来源尚未配置":"Weather source not configured";
            default:
                if(reason.startsWith("weather_http_"))return chinese?"天气服务暂时不可用":"Weather service temporarily unavailable";
                return chinese?"上次更新未完成":"Last update did not complete";
        }
    }

    static String standbyLabel(int bucket,boolean chinese) {
        switch(bucket) {
            case UsageStatsManager.STANDBY_BUCKET_ACTIVE:return chinese?"活跃":"Active";
            case UsageStatsManager.STANDBY_BUCKET_WORKING_SET:return chinese?"常用":"Working set";
            case UsageStatsManager.STANDBY_BUCKET_FREQUENT:return chinese?"一般":"Frequent";
            case UsageStatsManager.STANDBY_BUCKET_RARE:return chinese?"很少使用":"Rare";
            case UsageStatsManager.STANDBY_BUCKET_RESTRICTED:return chinese?"受限制":"Restricted";
            default:return chinese?"系统未提供":"Unavailable";
        }
    }
}
