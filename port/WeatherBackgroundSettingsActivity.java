package dev.weather.oneplus;

import android.content.Intent;
import android.widget.LinearLayout;
import android.widget.Toast;
import java.text.DateFormat;
import java.util.Date;

/** User-facing MIUIX diagnostics and standard Android settings for this app only. */
public final class WeatherBackgroundSettingsActivity extends WeatherWidgetSettingsActivity {
    @Override protected void onResume() {
        super.onResume();
        setTitle(chinese?"后台更新与通知":"Background updates and notifications");
        LinearLayout content=page();
        WeatherBackgroundStatus status=WeatherBackgroundStatus.read(this);
        note(content,chinese?"允许后台运行后，天气可以在未打开时联网更新和发送提醒。各系统的省电策略仍可能推迟更新。"
                :"Allow background activity so Weather can refresh and send reminders while closed. Your system may still defer background work.");
        link(content,chinese?"后台运行与自启动":"Background activity and auto-start",
                chinese?"打开本应用详情，在电池或启动管理中允许后台活动；设置名称因系统而异"
                :"Open this app's details and allow background activity in battery or launch management",
                ()->openSettings(WeatherBackgroundStatus.applicationSettings(getPackageName())));
        link(content,chinese?"通知设置":"Notification settings",
                Boolean.TRUE.equals(status.notificationsAllowed)&&!status.alertChannelBlocked?(chinese?"已允许发送天气提醒":"Weather notifications allowed")
                    :(chinese?"检查通知权限和天气提醒分类":"Check notification permission and the weather channel"),
                ()->openSettings(WeatherBackgroundStatus.notificationSettings(getPackageName())));
        link(content,chinese?"电池优化设置":"Battery optimization settings",
                chinese?"在系统列表中选择“"+getApplicationInfo().loadLabel(getPackageManager())+"”，按需调整电池优化限制"
                :"Choose Weather in the system list to adjust battery optimization",
                ()->openSettings(WeatherBackgroundStatus.batterySettings()));
        link(content,chinese?"重新检查后台任务":"Check background jobs",
                chinese?"按现有刷新间隔和夜间规则检查，不改变通知开关":"Uses the existing refresh interval and quiet hours",
                ()->{
                    WeatherAlerts.ensureScheduled(this);WeatherRefresh.ensureScheduled(this);
                    Toast.makeText(this,chinese?"已检查后台任务，执行时间由系统安排":"Checked; Android controls execution time",Toast.LENGTH_SHORT).show();
                    recreate();
                });
        note(content,chinese?"检查会补排缺失的后台任务。执行时间仍由系统决定，已错过的早晚提醒不会因此立即补发。"
                :"Checking restores missing scheduled work. Android still controls execution time; missed daily reminders are not sent immediately.");
        LinearLayout system=card(content);
        system.addView(text(chinese?"系统状态":"System status",17,false));
        note(system,(chinese?"应用通知：":"Notifications: ")+yesNo(status.notificationsAllowed)+"\n"
                +(chinese?"天气提醒分类：":"Weather channel: ")+(status.alertChannelBlocked?(chinese?"已关闭":"Blocked")
                    :status.alertChannelExists?(chinese?"已开启":"Enabled"):(chinese?"首次提醒时创建":"Created with first reminder"))+"\n"
                +(chinese?"电池优化豁免：":"Battery optimization exempt: ")+yesNo(status.batteryExempt)+"\n"
                +(chinese?"系统省电模式：":"Battery saver: ")+yesNo(status.powerSave)+"\n"
                +(chinese?"系统限制后台：":"Background restricted: ")+yesNo(status.backgroundRestricted)+"\n"
                +(chinese?"应用待机状态：":"App standby: ")+WeatherBackgroundStatus.standbyLabel(status.standbyBucket,chinese));
        LinearLayout work=card(content);work.addView(text(chinese?"天气更新":"Weather updates",17,false));
        note(work,(chinese?"联网授权：":"Network consent: ")+yesNo(status.networkAllowed)+"\n"
                +(chinese?"需要后台更新：":"Background data needed: ")+yesNo(status.localBackgroundNeeded)+"\n"
                +(chinese?"自动联网间隔：":"Refresh interval: ")+WeatherRefresh.intervalMinutes(this)+(chinese?" 分钟":" minutes")+"\n"
                +(chinese?"夜间自动更新：":"Night updates: ")+(status.nightUpdates?(chinese?"已开启":"Enabled"):(chinese?"23:00–次日 04:00 暂停":"Paused 23:00–04:00"))+"\n"
                +(chinese?"系统中的更新任务：":"Scheduled refresh jobs: ")+(status.jobsAvailable?Integer.toString(status.refreshJobs):unavailable())+"\n"
                +(chinese?"正在更新：":"Refresh running: ")+yesNo(status.refreshInProgress)+"\n"
                +(chinese?"计划更新时间：":"Planned refresh: ")+time(status.nextRefreshDue)+"\n"
                +(chinese?"最近联网尝试：":"Last fetch attempt: ")+time(status.lastAttempt)+"\n"
                +(chinese?"最近联网成功：":"Last fetch success: ")+time(status.lastSuccess));
        if(status.lastError!=null&&!status.lastError.isEmpty())note(work,(chinese?"上次更新状态：":"Last refresh status: ")+WeatherBackgroundStatus.refreshError(status.lastError,chinese));
        LinearLayout notifications=card(content);notifications.addView(text(chinese?"提醒状态":"Reminder status",17,false));
        note(notifications,(chinese?"早晚天气提醒：":"Daily reminders: ")+yesNo(status.dailyEnabled)+"\n"
                +(chinese?"天气预警：":"Weather alerts: ")+yesNo(status.alertEnabled)+"\n"
                +(chinese?"天气突变提醒：":"Abrupt-weather reminders: ")+yesNo(status.abruptEnabled)+"\n"
                +(chinese?"夜间免打扰：":"Notification quiet hours: ")+(status.notificationQuietHours?(chinese?"23:00–次日 07:00":"23:00–07:00"):(chinese?"已关闭":"Off"))+"\n"
                +(chinese?"系统中的早晚提醒任务：":"Scheduled daily jobs: ")+(status.jobsAvailable?Integer.toString(status.reminderJobs):unavailable())+"\n"
                +(chinese?"最近后台提醒检查：":"Last background reminder check: ")+time(status.lastReminderCheck));
        note(notifications,chinese?"早晚提醒和预警可读取后台天气数据发送。天气突变提醒依赖服务端消息；远程送达未验证时，打开该开关不代表可以收到。"
                :"Daily reminders and alerts can use background weather data. Abrupt-weather reminders require a server message; enabling the option does not confirm remote delivery.");
        LinearLayout push=card(content);push.addView(text(chinese?"远程推送":"Remote push",17,false));
        note(push,(chinese?"小米推送注册：":"MiPush registration: ")+(status.sdkRegistered?(chinese?"SDK 注册已验证":"SDK registration verified"):(chinese?"尚未验证成功":"Not verified"))+"\n"
                +(chinese?"本地推送传输已停用：":"Local push transport disabled: ")+yesNo(status.sdkTransportBlocked)+"\n"
                +(chinese?"早晚提醒远程送达：":"Remote daily delivery: ")+verified(status.remoteDaily)+"\n"
                +(chinese?"预警远程送达：":"Remote alert delivery: ")+verified(status.remoteAlert)+"\n"
                +(chinese?"突变提醒远程送达：":"Remote abrupt delivery: ")+verified(status.remoteAbrupt)+"\n"
                +(chinese?"最近真实推送回调：":"Last received push callback: ")+time(status.lastPushReceipt));
        note(content,chinese?"从最近任务中划掉应用与“强行停止”不同。若系统划卡后禁止后台活动，可允许自启动、放宽本应用电池限制或锁定任务卡片；系统设置中的“强行停止”后，需要重新打开应用。"
                :"Removing the recent-task card differs from Force stop. If your system blocks background activity after removal, allow auto-start, adjust this app's battery limits or lock its task card. Open Weather again after Force stop.");
    }

    private void openSettings(Intent intent) {
        try {
            if(intent.resolveActivity(getPackageManager())!=null) {startActivity(intent);return;}
            Intent fallback=WeatherBackgroundStatus.applicationSettings(getPackageName());
            if(fallback.resolveActivity(getPackageManager())!=null) {startActivity(fallback);return;}
        }catch(RuntimeException ignored) {}
        Toast.makeText(this,chinese?"系统没有提供此入口，请在系统设置中打开本应用详情":"Open Weather's app details in system settings",Toast.LENGTH_LONG).show();
    }
    private String yesNo(Boolean value) {return value==null?unavailable():value?(chinese?"是":"Yes"):(chinese?"否":"No");}
    private String verified(boolean value) {return value?(chinese?"已验证":"Verified"):(chinese?"尚未验证":"Not verified");}
    private String unavailable() {return chinese?"系统未提供":"Unavailable";}
    private String time(long timestamp) {
        if(timestamp<=0)return chinese?"暂无记录":"No record";
        return DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.MEDIUM).format(new Date(timestamp));
    }
}
