package dev.weather.oneplus;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Intent;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;
import miuix.appcompat.widget.Button;
import miuix.appcompat.widget.Spinner;
import miuix.slidingwidget.widget.SlidingButton;
import miuix.androidbasewidget.widget.SeekBar;

/** Launcher reconfiguration and in-app management share the per-instance editor. */
public final class WeatherWidgetConfigureActivity extends WeatherWidgetSettingsActivity {
    private int widgetId;
    private Spinner first,second,panel;
    private SlidingButton sizeEditing;
    private SeekBar widthSlider,heightSlider;
    private final ArrayList<String> firstIds=new ArrayList<>(),secondIds=new ArrayList<>();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);setResult(RESULT_CANCELED);
        widgetId=getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,AppWidgetManager.INVALID_APPWIDGET_ID);
        AppWidgetProviderInfo info=widgetInfo();if(info==null){finish();return;}
        setTitle(chinese?"小组件设置":"Widget settings");
        LinearLayout content=page();note(content,info.loadLabel(getPackageManager()));
        String provider=info.provider.getClassName();boolean dual=provider.endsWith("WeatherDualCityWidgetProvider");
        JSONArray choices=WeatherWidgetPreferences.cities(this);
        String initial=dual?(chinese?"跟随定位城市":"Follow current location"):(chinese?"跟随首页城市":"Follow first city");
        first=citySpinner(content,dual?(chinese?"左侧城市":"Left city"):(chinese?"显示城市":"City"),choices,
                WeatherWidgetPreferences.cityId(this,widgetId),firstIds,initial);
        if(dual)second=citySpinner(content,chinese?"右侧城市":"Right city",choices,
                WeatherWidgetPreferences.secondCityId(this,widgetId),secondIds,chinese?"请选择城市":"Choose a city");
        boolean small=provider.endsWith("WeatherSmallWidgetProvider"),custom=provider.endsWith("WeatherCustomWidgetProvider");
        if(small||custom)panel=choice(content,chinese?"显示内容":"Display content",panelLabels(chinese,small),WeatherWidgetPreferences.panel(this,widgetId));
        if(state!=null) {
            select(first,firstIds,state.getString("first",""));
            if(second!=null)select(second,secondIds,state.getString("second",""));
            if(panel!=null)panel.setSelection(Math.max(0,Math.min(3,state.getInt("panel",0))));
        }
        sizeControls(content,state);
        note(content,dual?(chinese?"左侧默认跟随定位城市；定位城市不可用时显示首页城市。右侧城市单独选择。":"The left side follows your located city, falling back to the first city. Choose the right city separately.")
                :(chinese?"默认跟随首页第一个城市，也可以为这个小组件指定其他城市。":"Follow your first city, or choose a different city for this widget."));
        if(choices.length()==0)note(content,chinese?"暂无已添加的城市，请先在天气首页添加城市。":"Add a city in Weather first.");
        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);
        Button cancel=new Button(this);cancel.setText(chinese?"取消":"Cancel");cancel.setOnClickListener(v->finish());
        Button save=new Button(this);save.setText(chinese?"保存":"Save");save.setOnClickListener(v->save());
        LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(0,-2,1);left.setMarginEnd(dp(6));
        LinearLayout.LayoutParams right=new LinearLayout.LayoutParams(0,-2,1);right.setMarginStart(dp(6));
        actions.addView(cancel,left);actions.addView(save,right);content.addView(actions);
    }

    private AppWidgetProviderInfo widgetInfo() {
        if(widgetId==AppWidgetManager.INVALID_APPWIDGET_ID)return null;
        AppWidgetProviderInfo info=AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId);
        return info!=null&&getPackageName().equals(info.provider.getPackageName())?info:null;
    }

    private void save() {
        if(widgetInfo()==null){finish();return;}
        String city=firstIds.get(first.getSelectedItemPosition());
        String other=second==null?WeatherWidgetPreferences.secondCityId(this,widgetId):secondIds.get(second.getSelectedItemPosition());
        boolean cityChanged=!city.equals(WeatherWidgetPreferences.cityId(this,widgetId))
                ||!other.equals(WeatherWidgetPreferences.secondCityId(this,widgetId));
        if(!WeatherWidgetPreferences.save(this,widgetId,city,other,panel==null?WeatherWidgetPreferences.panel(this,widgetId):panel.getSelectedItemPosition(),
                sizeEditing.isChecked(),widthSlider.getProgress()+WeatherWidgetGeometry.MIN,heightSlider.getProgress()+WeatherWidgetGeometry.MIN)) {
            Toast.makeText(this,chinese?"无法保存，请重试":"Could not save. Try again.",Toast.LENGTH_SHORT).show();return;
        }
        WeatherWidgetProvider.updateAll(this);
        // Selecting another city needs its weather; geometry alone only repaints the cache.
        if(cityChanged)WeatherRefresh.requestManual(this);else WeatherRefresh.requestAutomatic(this);
        setResult(RESULT_OK,new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,widgetId));finish();
    }

    static String[] panelLabels(boolean chinese,boolean small) {
        return small?(chinese?new String[]{"空气质量","湿度","温度区间","体感温度"}:new String[]{"Air quality","Humidity","Temperature range","Feels like"})
                :(chinese?new String[]{"当日气象","24小时预报","多日预报","日出日落"}:new String[]{"Today's weather","24-hour forecast","Multi-day forecast","Sunrise and sunset"});
    }

    private Spinner citySpinner(LinearLayout parent,String title,JSONArray cities,String saved,ArrayList<String> ids,String initial) {
        ArrayList<String> names=new ArrayList<>();ids.add("");names.add(initial);int selected=0;
        for(int i=0;i<cities.length();i++) {
            JSONObject city=cities.optJSONObject(i);String id=city.optString("cityId");
            ids.add(id);names.add(city.optString("cityName",id)+(city.optBoolean("located")?(chinese?"（定位）":" (located)"):""));
            if(id.equals(saved))selected=ids.size()-1;
        }
        if(!saved.isEmpty()&&selected==0){ids.add(saved);names.add(chinese?"已选城市（暂不可用）":"Selected city (unavailable)");selected=ids.size()-1;}
        return choice(parent,title,names.toArray(new String[0]),selected);
    }

    private Spinner choice(LinearLayout parent,String title,String[] values,int selected) {
        LinearLayout row=card(parent);TextView label=text(title,16,false);label.setPadding(dp(2),dp(4),0,0);row.addView(label);
        Spinner spinner=new Spinner(this);spinner.setPrompt(title);spinner.setMinimumHeight(dp(48));
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);spinner.setSelection(selected);row.addView(spinner,new LinearLayout.LayoutParams(-1,-2));return spinner;
    }

    private void select(Spinner spinner,ArrayList<String> ids,String value){int at=ids.indexOf(value);if(at>=0)spinner.setSelection(at);}

    private void sizeControls(LinearLayout parent,Bundle state) {
        boolean enabled=state==null?WeatherWidgetPreferences.sizeEditing(this,widgetId):state.getBoolean("size_editing");
        int width=state==null?WeatherWidgetPreferences.savedWidthPercent(this,widgetId):state.getInt("width_percent",100);
        int height=state==null?WeatherWidgetPreferences.savedHeightPercent(this,widgetId):state.getInt("height_percent",100);
        LinearLayout row=card(parent);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.addView(text(chinese?"自定义小组件尺寸":"Custom widget size",17,false),new LinearLayout.LayoutParams(0,dp(52),1));
        sizeEditing=new SlidingButton(this);sizeEditing.setContentDescription(chinese?"自定义小组件尺寸":"Custom widget size");
        sizeEditing.setChecked(enabled);row.addView(sizeEditing,new LinearLayout.LayoutParams(-2,-2));
        widthSlider=sizeSlider(parent,chinese?"宽度":"Width",width);
        heightSlider=sizeSlider(parent,chinese?"高度":"Height",height);
        Runnable update=()->{boolean edit=sizeEditing.isChecked();widthSlider.setEnabled(edit);heightSlider.setEnabled(edit);
            widthSlider.setAlpha(edit?1f:.4f);heightSlider.setAlpha(edit?1f:.4f);};
        sizeEditing.setOnCheckedChangeListener((button,checked)->update.run());
        row.setOnClickListener(v->sizeEditing.setChecked(!sizeEditing.isChecked()));update.run();
        Button reset=new Button(this);reset.setText(chinese?"恢复 100%":"Reset to 100%");
        reset.setOnClickListener(v->{widthSlider.setProgress(100-WeatherWidgetGeometry.MIN);heightSlider.setProgress(100-WeatherWidgetGeometry.MIN);});
        parent.addView(reset,new LinearLayout.LayoutParams(-1,-2));
        note(parent,chinese?"只调整这个小组件的可见卡片；关闭后恢复原尺寸。100% 为当前默认，50%–150% 可分别调整宽高。放大超出桌面分配区域时会被裁切，请用桌面拖动边框增加格数。"
                :"Changes only this card. Off restores the default. Adjust width and height from 50% to 150%; enlargement is clipped by the launcher allocation. Resize the widget on your home screen for more space.");
    }

    private SeekBar sizeSlider(LinearLayout parent,String title,int percent) {
        LinearLayout row=card(parent);TextView label=text(title,16,false);row.addView(label);
        SeekBar slider=new SeekBar(this,null);slider.setMax(WeatherWidgetGeometry.MAX-WeatherWidgetGeometry.MIN);
        slider.setProgress(WeatherWidgetGeometry.clamp(percent)-WeatherWidgetGeometry.MIN);
        slider.setContentDescription(title);row.addView(slider,new LinearLayout.LayoutParams(-1,dp(48)));
        label.setText(title+" · "+(slider.getProgress()+WeatherWidgetGeometry.MIN)+"%");
        slider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(android.widget.SeekBar view,int progress,boolean fromUser){label.setText(title+" · "+(progress+WeatherWidgetGeometry.MIN)+"%");}
            public void onStartTrackingTouch(android.widget.SeekBar view){}
            public void onStopTrackingTouch(android.widget.SeekBar view){}
        });return slider;
    }

    @Override public void onSaveInstanceState(Bundle state) {
        if(first!=null)state.putString("first",firstIds.get(first.getSelectedItemPosition()));
        if(second!=null)state.putString("second",secondIds.get(second.getSelectedItemPosition()));
        if(panel!=null)state.putInt("panel",panel.getSelectedItemPosition());
        if(sizeEditing!=null)state.putBoolean("size_editing",sizeEditing.isChecked());
        if(widthSlider!=null)state.putInt("width_percent",widthSlider.getProgress()+WeatherWidgetGeometry.MIN);
        if(heightSlider!=null)state.putInt("height_percent",heightSlider.getProgress()+WeatherWidgetGeometry.MIN);
        super.onSaveInstanceState(state);
    }
}
