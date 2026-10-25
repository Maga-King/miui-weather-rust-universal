package dev.weather.oneplus;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import miuix.slidingwidget.widget.SlidingButton;
import miuix.androidbasewidget.widget.SeekBar;

/** MIUIX appearance options reached from the original Flutter settings list. */
public final class WeatherAppearanceActivity extends WeatherWidgetSettingsActivity {
    private boolean restarting;
    private SeekBar weightSlider;
    private TextView weightValue;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);setTitle(chinese?"字体与动画":"Display options");
        LinearLayout content=page();
        note(content,chinese?"字体":"Font");
        toggle(content,chinese?"使用 MiSans":"Use MiSans",chinese?"关闭后跟随系统字体":"Turn off to use your system font",WeatherFonts.miSansEnabled(this),
                enabled->{WeatherFonts.setMiSansEnabled(this,enabled);WeatherFonts.apply(this);});
        toggle(content,chinese?"跟随系统字体粗细":"Follow system font weight",chinese?"关闭后可自行调整字体粗细":"Turn off to adjust font weight below",WeatherFonts.followsSystemWeight(this),
                enabled->{WeatherFonts.setFollowsSystemWeight(this,enabled);updateWeightControls();WeatherFonts.apply(this);});
        weightControls(content);
        note(content,chinese?"字体更改将在返回天气时应用。":"Font changes apply when you return to Weather.");
    }

    private void weightControls(LinearLayout parent) {
        LinearLayout row=card(parent);
        row.addView(text(chinese?"字体粗细":"Font weight",17,false));
        weightValue=text("",14,true);row.addView(weightValue,new LinearLayout.LayoutParams(-1,-2));
        weightSlider=new SeekBar(this,null);weightSlider.setMax(100);
        weightSlider.setContentDescription(chinese?"字体粗细":"Font weight");
        weightSlider.setProgress(WeatherFonts.weightProgress(this));
        row.addView(weightSlider,new LinearLayout.LayoutParams(-1,dp(56)));
        TextView preview=text(chinese?"晴朗 26°  空气优\n天气预报 Weather 123":"Sunny 26°\nWeather forecast 123",21,false);
        preview.setPadding(0,dp(8),0,dp(8));row.addView(preview,new LinearLayout.LayoutParams(-1,-2));
        TextView reset=text(chinese?"恢复标准粗细":"Reset weight",14,true);
        reset.setMinimumHeight(dp(44));reset.setOnClickListener(v->{
            try{WeatherFonts.setWeightProgress(this,50);weightSlider.setProgress(50);updateWeightControls();WeatherFonts.apply(this);}
            catch(RuntimeException error){weightError(error);}
        });row.addView(reset,new LinearLayout.LayoutParams(-1,-2));
        weightSlider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(android.widget.SeekBar view,int progress,boolean fromUser) {
                if(!fromUser)return;
                try{WeatherFonts.setWeightProgress(WeatherAppearanceActivity.this,progress);updateWeightControls();WeatherFonts.apply(WeatherAppearanceActivity.this);}
                catch(RuntimeException error){weightError(error);}
            }
            public void onStartTrackingTouch(android.widget.SeekBar view){}
            public void onStopTrackingTouch(android.widget.SeekBar view){}
        });
        updateWeightControls();
    }

    private void updateWeightControls() {
        if(weightSlider==null)return;
        boolean follow=WeatherFonts.followsSystemWeight(this);weightSlider.setEnabled(!follow);weightSlider.setAlpha(follow?.45f:1f);
        weightValue.setText(follow?(chinese?"跟随系统":"Following system")
                :(50+WeatherFonts.weightProgress(this))+"%"+(WeatherFonts.weightProgress(this)==50?(chinese?" · 标准":" · Default"):""));
    }
    private void weightError(RuntimeException error) {
        Toast.makeText(this,chinese?"无法保存字体粗细，请重试":"Could not save font weight. Try again.",Toast.LENGTH_LONG).show();
        android.util.Log.e("WeatherAppearance","Could not save font weight",error);
    }

    private interface Change {void changed(boolean enabled);}
    private void toggle(LinearLayout parent,String title,String summary,boolean enabled,Change change) {
        LinearLayout row=card(parent);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(92));
        LinearLayout labels=new LinearLayout(this);labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title,17,false),new LinearLayout.LayoutParams(-1,-2));
        TextView detail=text(summary,13,true);detail.setPadding(0,dp(5),dp(10),0);
        labels.addView(detail,new LinearLayout.LayoutParams(-1,-2));row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        SlidingButton button=new SlidingButton(this);button.setContentDescription(title);button.setChecked(enabled);
        row.addView(button,new LinearLayout.LayoutParams(-2,-2));row.setOnClickListener(v->button.setChecked(!button.isChecked()));
        button.setOnCheckedChangeListener((view,checked)->{
            try{change.changed(checked);}catch(RuntimeException error){
                button.setOnCheckedChangeListener(null);button.setChecked(!checked);
                Toast.makeText(this,chinese?"无法应用，请重新打开设置重试":"Could not apply. Reopen settings to retry.",Toast.LENGTH_LONG).show();
                android.util.Log.e("WeatherAppearance","Could not apply display option",error);
            }
        });
    }

    @Override public void finish() {
        if(!restarting&&WeatherFonts.engineNeedsRestart(this)) {
            restarting=true;
            startActivity(new Intent(this,WeatherFontRestartActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            // Flutter owns its registered text faces for the isolate lifetime.
            // Restart only our app process, after the separate restart activity is visible.
            new Handler(Looper.getMainLooper()).postDelayed(()->android.os.Process.killProcess(android.os.Process.myPid()),200);
        }
        super.finish();
    }
}
