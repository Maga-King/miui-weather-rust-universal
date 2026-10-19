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

/** Launcher reconfiguration and in-app management share the per-instance editor. */
public final class WeatherWidgetConfigureActivity extends WeatherWidgetSettingsActivity {
    private int widgetId;
    private Spinner first,second,panel;
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
        if(!WeatherWidgetPreferences.save(this,widgetId,city,other,panel==null?WeatherWidgetPreferences.panel(this,widgetId):panel.getSelectedItemPosition())) {
            Toast.makeText(this,chinese?"无法保存，请重试":"Could not save. Try again.",Toast.LENGTH_SHORT).show();return;
        }
        WeatherWidgetProvider.updateAll(this);WeatherRefresh.requestManual(this);
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

    @Override public void onSaveInstanceState(Bundle state) {
        if(first!=null)state.putString("first",firstIds.get(first.getSelectedItemPosition()));
        if(second!=null)state.putString("second",secondIds.get(second.getSelectedItemPosition()));
        if(panel!=null)state.putInt("panel",panel.getSelectedItemPosition());super.onSaveInstanceState(state);
    }
}
