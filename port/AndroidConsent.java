package dev.weather.oneplus;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Local Android replacement for Weather's Xiaomi Security Center consent UI. */
public final class AndroidConsent {
    public static final String CHANNEL = "com.android.content.intent.method.channel";
    public static final Object UNHANDLED = new Object();
    private static final String CTA = "miui.intent.action.CTA_DECLARE";
    private static final String PERMISSION_EDITOR = "miui.intent.action.APP_PERM_EDITOR";
    private static final String ORIGINAL_PACKAGE = "com.miui.weather2";
    private static final String TAG = "WeatherConsent";
    private static final int SETTINGS_REQUEST = 0x5743;

    public interface EventSink {
        /** Encode this channel's outbound call with JSONMethodCodec. */
        void invokeMethod(String channel, String method, Object arguments);
    }

    private final Activity activity;
    private final EventSink events;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<ConsentRequest> queue = new ArrayDeque<>();
    private ConsentRequest current;
    private Dialog dialog;
    private Integer settingsResultCode;
    private boolean destroyed;

    private static final class ConsentRequest {
        final int code;
        final String purpose, description, userAgreement, privacyPolicy;
        final List<String> optionalDescriptions;
        final boolean usesNetwork;

        ConsentRequest(int code, Object extras) {
            this.code = code;
            purpose = string(extra(extras, "string_extra_map", "main_purpose"));
            description = string(extra(extras, "string_extra_map", "agree_desc"));
            userAgreement = string(extra(extras, "string_extra_map", "user_agreement"));
            privacyPolicy = string(extra(extras, "string_extra_map", "privacy_policy"));
            optionalDescriptions = strings(extra(extras, "string_array_extra_map", "optional_perm_desc"));
            usesNetwork = Boolean.TRUE.equals(extra(extras, "bool_extra_map", "use_network"));
        }
    }

    public AndroidConsent(Activity activity, EventSink events) {
        this.activity = activity;
        this.events = events;
    }

    /** Acknowledges dispatch; consent is reported only after an actual dialog choice. */
    public Object handleCall(String channel, String method, Object arguments) {
        if (!CHANNEL.equals(channel) || destroyed) return UNHANDLED;
        boolean forResult = "startActivityForResult".equals(method);
        if (!forResult && !"startActivity".equals(method)) return UNHANDLED;
        String action = string(value(arguments, "action"));
        Object extras = value(arguments, "extras");
        Object requestCode = value(arguments, "request_code");
        if (CTA.equals(action)) {
            if (!forResult || !(requestCode instanceof Number) || !isMap(extras)) {
                Log.w(TAG, "Malformed CTA request; no consent was recorded");
                return UNHANDLED;
            }
            ConsentRequest request = new ConsentRequest(((Number) requestCode).intValue(), extras);
            if (request.purpose == null || request.description == null
                    || !isWebUrl(request.userAgreement) || !isWebUrl(request.privacyPolicy)) {
                Log.w(TAG, "CTA is missing its explanation or policy links; no consent was recorded");
                return UNHANDLED;
            }
            main.post(() -> enqueue(request));
            return null;
        }
        if (PERMISSION_EDITOR.equals(action)
                || Settings.ACTION_APPLICATION_DETAILS_SETTINGS.equals(action)) {
            String target = string(extra(extras, "string_extra_map", "extra_pkgname"));
            if (target != null && !ORIGINAL_PACKAGE.equals(target)
                    && !activity.getPackageName().equals(target)) return UNHANDLED;
            if (forResult && !(requestCode instanceof Number)) return UNHANDLED;
            Integer code = forResult ? ((Number) requestCode).intValue() : null;
            main.post(() -> openApplicationSettings(code));
            return null;
        }
        // Other intents are left to explicit host handlers, never launched blindly.
        return UNHANDLED;
    }

    private void enqueue(ConsentRequest request) {
        if (destroyed) return;
        if (current != null && current.code == request.code) return;
        for (ConsentRequest pending : queue) if (pending.code == request.code) return;
        queue.add(request);
        showNext();
    }

    private void showNext() {
        if (destroyed || current != null || queue.isEmpty()) return;
        current = queue.remove();
        if (activity.isFinishing() || activity.isDestroyed()) {
            finishConsent(false);
            return;
        }
        boolean dark = (activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int background = Color.parseColor(dark ? "#202227" : "#FFFFFF");
        int foreground = Color.parseColor(dark ? "#F4F5F8" : "#202631");
        int secondary = Color.parseColor(dark ? "#B9BFCB" : "#606B7B");
        int accent = Color.parseColor(dark ? "#AACBFF" : "#2868D7");
        int soft = Color.parseColor(dark ? "#2B3039" : "#F0F4FA");
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(24), dp(24), dp(24), dp(20));
        card.setBackground(rounded(background, 28));
        card.setClipToOutline(true);

        TextView appName = label("小米天气", 12, secondary);
        appName.setLetterSpacing(0.06f);
        card.addView(appName);
        TextView title = label("联网与隐私", 23, foreground);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp(7);
        titleParams.bottomMargin = dp(16);
        card.addView(title, titleParams);

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(4));
        StringBuilder message = new StringBuilder(current.purpose);
        if (current.usesNetwork) message.append("\n天气需要联网获取预报与更新信息。");
        for (String explanation : current.optionalDescriptions) {
            if (!explanation.isEmpty()) message.append("\n").append(explanation);
        }
        message.append("\n\n").append(current.description);
        TextView body = label(message.toString(), 14, secondary);
        body.setLineSpacing(dp(4), 1f);
        content.addView(body);

        TextView permissionsNote = label(
                "这里只确认联网与隐私条款。定位、通知等权限会在使用相关功能时，由系统单独询问。", 13, secondary);
        permissionsNote.setLineSpacing(dp(3), 1f);
        permissionsNote.setPadding(dp(14), dp(12), dp(14), dp(12));
        permissionsNote.setBackground(rounded(soft, 14));
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noteParams.topMargin = dp(16);
        content.addView(permissionsNote, noteParams);

        LinearLayout links = new LinearLayout(activity);
        links.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams linksParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        linksParams.topMargin = dp(8);
        content.addView(links, linksParams);
        addLink(links, "用户协议", current.userAgreement, accent);
        addLink(links, "隐私政策", current.privacyPolicy, accent);
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsParams.topMargin = dp(12);
        card.addView(actions, actionsParams);
        Button decline = actionButton("不同意", foreground, soft);
        decline.setOnClickListener(view -> finishConsent(false));
        LinearLayout.LayoutParams declineParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        declineParams.setMarginEnd(dp(10));
        actions.addView(decline, declineParams);
        Button accept = actionButton("同意并继续",
                Color.parseColor(dark ? "#102A4F" : "#FFFFFF"), accent);
        accept.setOnClickListener(view -> finishConsent(true));
        actions.addView(accept, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(card);
        dialog.setOnCancelListener(ignored -> finishConsent(false));
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams layout = window.getAttributes();
            layout.width = Math.min(dp(420), activity.getResources().getDisplayMetrics().widthPixels - dp(40));
            layout.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            layout.gravity = Gravity.CENTER;
            layout.dimAmount = 0.44f;
            window.setAttributes(layout);
        }
        Log.i(TAG, "Showing original CTA information, request=" + current.code);
    }

    private void addLink(LinearLayout parent, String title, String url, int accent) {
        TextView link = label(title + "  ↗", 14, accent);
        link.setGravity(Gravity.CENTER);
        link.setMinHeight(dp(48));
        link.setPadding(dp(4), dp(12), dp(4), dp(12));
        link.setBackground(ripple(Color.TRANSPARENT, 12));
        link.setFocusable(true);
        link.setOnClickListener(view -> {
            try {
                activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addCategory(Intent.CATEGORY_BROWSABLE));
            } catch (RuntimeException failure) {
                Log.w(TAG, "Cannot open policy page", failure);
                Toast.makeText(activity, "无法打开协议页面，请检查浏览器是否可用", Toast.LENGTH_LONG).show();
            }
        });
        parent.addView(link, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        return view;
    }

    private Button actionButton(String text, int color, int background) {
        Button button = new Button(activity);
        button.setText(text);
        button.setTextSize(15);
        button.setTextColor(color);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setMinHeight(dp(50));
        button.setMinimumHeight(dp(50));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setSingleLine(false);
        button.setMaxLines(2);
        button.setPadding(dp(8), dp(12), dp(8), dp(12));
        button.setStateListAnimator(null);
        button.setBackground(ripple(background, 15));
        return button;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        return background;
    }

    private RippleDrawable ripple(int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x247080A0),
                rounded(color, radius), rounded(Color.WHITE, radius));
    }

    private void finishConsent(boolean accepted) {
        if (destroyed || current == null) return;
        ConsentRequest completed = current;
        current = null;
        Dialog completedDialog = dialog;
        dialog = null;
        if (completedDialog != null) {
            completedDialog.setOnCancelListener(null);
            completedDialog.dismiss();
        }
        // Xiaomi CTA explicitly uses 1 for accepted. Android RESULT_OK (-1) is refusal here.
        sendResult(completed.code, accepted ? 1 : 0);
        main.post(this::showNext);
    }

    private void openApplicationSettings(Integer originalCode) {
        if (destroyed) return;
        if (settingsResultCode != null) {
            if (originalCode != null) sendResult(originalCode, Activity.RESULT_CANCELED);
            return;
        }
        Intent settings = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", activity.getPackageName(), null));
        try {
            if (originalCode == null) activity.startActivity(settings);
            else {
                settingsResultCode = originalCode;
                activity.startActivityForResult(settings, SETTINGS_REQUEST);
            }
        } catch (RuntimeException failure) {
            settingsResultCode = null;
            Log.w(TAG, "Cannot open standard Android application settings", failure);
            if (originalCode != null) sendResult(originalCode, Activity.RESULT_CANCELED);
        }
    }

    /** Forward Activity.onActivityResult; opening settings never implies a grant. */
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != SETTINGS_REQUEST) return false;
        Integer originalCode = settingsResultCode;
        settingsResultCode = null;
        if (!destroyed && originalCode != null) sendResult(originalCode, resultCode);
        return true;
    }

    public void onDestroy() {
        destroyed = true;
        queue.clear();
        current = null;
        settingsResultCode = null;
        if (dialog != null) {
            dialog.setOnCancelListener(null);
            dialog.dismiss();
            dialog = null;
        }
    }

    private void sendResult(int code, int result) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("request_code", code);
        arguments.put("result_code", result);
        arguments.put("result_data", Collections.emptyMap());
        events.invokeMethod(CHANNEL, "onActivityResult", arguments);
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static boolean isMap(Object object) {
        return object instanceof Map || object instanceof JSONObject;
    }

    private static Object value(Object object, String name) {
        Object found = object instanceof Map ? ((Map<?, ?>) object).get(name)
                : object instanceof JSONObject ? ((JSONObject) object).opt(name) : null;
        return found == JSONObject.NULL ? null : found;
    }

    private static Object extra(Object extras, String group, String name) {
        Object found = value(value(extras, group), name);
        return found != null ? found : value(extras, name);
    }

    private static String string(Object value) {
        return value instanceof String && !((String) value).isEmpty() ? (String) value : null;
    }

    private static List<String> strings(Object object) {
        List<String> values = new ArrayList<>();
        if (object instanceof List) {
            for (Object value : (List<?>) object) if (value instanceof String) values.add((String) value);
        } else if (object instanceof JSONArray) {
            JSONArray array = (JSONArray) object;
            for (int i = 0; i < array.length(); i++) {
                Object value = array.opt(i);
                if (value instanceof String) values.add((String) value);
            }
        }
        return values;
    }

    private static boolean isWebUrl(String value) {
        if (value == null) return false;
        Uri uri = Uri.parse(value);
        return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null && !uri.getHost().isEmpty();
    }
}
