package dev.weather.oneplus;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;

/** The original main screen, started only in the independent test APK. */
public final class TestWeatherActivity extends WeatherActivity {
    private static TestCityFixtureServer fixtureServer;
    @Override public void onCreate(Bundle state) {
        if (!"dev.weather.test".equals(getPackageName())) throw new SecurityException("Test APK only");
        try {
            if (!TestCityMode.enabled(this)) TestCityMode.enable(this, TestCityFixtures.preset("clear_day"));
            JSONObject profile = TestCityMode.profile(this);
            // Recreated activities can reuse the live engine; only the cold-process
            // entry may initialize SQLite before Dart opens its FFI connection.
            if (fixtureServer == null) {
                TestCityDatabaseSeed.prepare(this, profile, System.currentTimeMillis());
                fixtureServer = new TestCityFixtureServer(profile);
            }
        } catch (Exception error) {
            android.util.Log.e("TestCity", "Cannot prepare isolated test city", error);
            // The test library has no remote weather endpoints even on this failure.
            super.onCreate(state);
            new AlertDialog.Builder(this).setTitle("测试城市启动失败")
                    .setMessage(error.getClass().getSimpleName() + ": " + error.getMessage())
                    .setPositiveButton("退出", (d, w) -> finish()).show();
            return;
        }
        super.onCreate(state);
        Button controls = new Button(this); controls.setText("测试控制 · 非实况");
        controls.setAlpha(.88f); controls.setOnClickListener(v -> showControls());
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        int inset = (int)(getResources().getDisplayMetrics().density * 20);
        params.setMargins(inset, inset, inset, inset + (int)(getResources().getDisplayMetrics().density * 24));
        addContentView(controls, params);
    }
    private Spinner choices(LinearLayout box, String label, String[] options, String selected) {
        TextView title = new TextView(this); title.setText(label); box.addView(title);
        Spinner result = new Spinner(this);
        result.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, options));
        for (int i = 0; i < options.length; i++) if (options[i].equals(selected)) result.setSelection(i);
        box.addView(result); return result;
    }
    private EditText number(LinearLayout box, String label, int value) {
        TextView title = new TextView(this); title.setText(label); box.addView(title);
        EditText result = new EditText(this); result.setSingleLine(true);
        result.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        result.setText("" + value); box.addView(result); return result;
    }
    private void showControls() {
        try {
            JSONObject current = TestCityMode.profile(this);
            LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(30, 10, 30, 10);
            TextView note = new TextView(this); note.setText("新日暮里 · 本地测试数据（非实况）\n保存后重新打开原天气界面。普通版和原小米天气的数据均独立。"); box.addView(note);
            Spinner presets = choices(box, "预设", TestCityFixtures.PRESETS, current.optString("preset", "clear_day"));
            Button applyPreset = new Button(this); applyPreset.setText("应用所选预设并打开"); box.addView(applyPreset);
            EditText temp = number(box, "温度 ℃（-99～70）", current.getInt("temperatureC"));
            EditText high = number(box, "最高温 ℃", current.getInt("highC"));
            EditText low = number(box, "最低温 ℃", current.getInt("lowC"));
            EditText weather = number(box, "原天气 V7 类型（0晴、2阴、7小雨、4雷雨、15大雪、53霾）", current.getInt("weatherV7"));
            Spinner phase = choices(box, "昼夜", new String[]{"day", "night"}, current.getString("phase"));
            EditText aqi = number(box, "AQI（0～500）", current.getInt("aqi"));
            Spinner rain = choices(box, "两小时降雨（dry无雨/start将下雨/stop将停雨/heavy强雨）",
                    new String[]{"dry", "start", "stop", "heavy"}, current.getString("rain"));
            ScrollView scroll = new ScrollView(this); scroll.addView(box);
            AlertDialog dialog = new AlertDialog.Builder(this).setTitle("测试控制 · 新日暮里")
                    .setView(scroll).setNegativeButton("取消", null).setPositiveButton("保存自定义", null)
                    .setNeutralButton("退出测试版", (d, w) -> exitTest()).create();
            applyPreset.setOnClickListener(v -> {
                try { saveAndRestart(TestCityFixtures.preset((String)presets.getSelectedItem())); dialog.dismiss(); }
                catch (Exception error) { invalid(error); }
            });
            dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    JSONObject p = new JSONObject().put("schemaVersion", 1).put("preset", "custom")
                            .put("temperatureC", Integer.parseInt(temp.getText().toString().trim()))
                            .put("highC", Integer.parseInt(high.getText().toString().trim()))
                            .put("lowC", Integer.parseInt(low.getText().toString().trim()))
                            .put("weatherV7", Integer.parseInt(weather.getText().toString().trim()))
                            .put("phase", phase.getSelectedItem()).put("aqi", Integer.parseInt(aqi.getText().toString().trim()))
                            .put("rain", rain.getSelectedItem());
                    saveAndRestart(p); dialog.dismiss();
                } catch (Exception error) { invalid(error); }
            }));
            dialog.show();
        } catch (Exception error) { invalid(error); }
    }
    private void invalid(Exception error) { Toast.makeText(this, "测试设置无效：" + error.getMessage(), Toast.LENGTH_LONG).show(); }
    private void saveAndRestart(JSONObject profile) throws Exception {
        TestCityMode.enable(this, profile);
        startActivity(new Intent(this, TestCityRestartActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finishAndRemoveTask();
        new Handler(Looper.getMainLooper()).postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 150);
    }
    private void exitTest() {
        finishAndRemoveTask();
        new Handler(Looper.getMainLooper()).postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 150);
    }
}
