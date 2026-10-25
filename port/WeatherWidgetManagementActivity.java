package dev.weather.oneplus;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Intent;
import android.widget.LinearLayout;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.view.View;
import android.widget.Toast;
import miuix.appcompat.widget.Spinner;
import java.util.ArrayList;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;

/** Enumerates actual launcher instances without creating or removing widgets. */
public final class WeatherWidgetManagementActivity extends WeatherWidgetSettingsActivity {
    @Override public void onResume() {
        super.onResume();setTitle(chinese?"管理小组件":"Manage widgets");
        LinearLayout list=page();refreshInterval(list);
        AppWidgetManager manager=AppWidgetManager.getInstance(this);
        ArrayList<Integer> instances=new ArrayList<>();
        for(Class<?> provider:WeatherWidgetCatalog.PROVIDERS) {
            int[] ids=manager.getAppWidgetIds(new ComponentName(this,provider));
            Arrays.sort(ids);for(int id:ids)instances.add(id);
        }
        note(list,chinese?"桌面已添加 "+instances.size()+" 个小组件":"Widgets on your home screen: "+instances.size());
        JSONArray cities=WeatherWidgetPreferences.cities(this);
        for(int id:instances) {
            AppWidgetProviderInfo info=manager.getAppWidgetInfo(id);if(info==null)continue;
            String name=info.loadLabel(getPackageManager());int[] same=manager.getAppWidgetIds(info.provider);Arrays.sort(same);
            if(same.length>1)name+=" · "+(Arrays.binarySearch(same,id)+1);
            String provider=info.provider.getClassName();boolean dual=provider.endsWith("WeatherDualCityWidgetProvider");
            String summary=cityLabel(cities,WeatherWidgetPreferences.cityId(this,id),dual
                    ?(chinese?"跟随定位城市":"Follow current location"):(chinese?"跟随首页城市":"Follow first city"));
            if(dual)summary+=" / "+cityLabel(cities,WeatherWidgetPreferences.secondCityId(this,id),chinese?"未选择第二城市":"Second city not selected");
            if(provider.endsWith("WeatherSmallWidgetProvider")||provider.endsWith("WeatherCustomWidgetProvider"))
                summary+=" · "+WeatherWidgetConfigureActivity.panelLabels(chinese,provider.endsWith("WeatherSmallWidgetProvider"))[WeatherWidgetPreferences.panel(this,id)];
            link(list,name,summary,()->startActivity(new Intent(this,WeatherWidgetConfigureActivity.class)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id)));
        }
        note(list,instances.isEmpty()?(chinese?"长按桌面空白处，选择小组件并添加天气。添加后即可在这里分别设置。":"Add a weather widget from your home screen, then configure it here.")
                :(chinese?"每个小组件单独设置城市和内容。需要其他城市时，请先在天气首页添加。":"Each widget keeps its own city and content. Add more cities in Weather first."));
    }

    private void refreshInterval(LinearLayout parent) {
        final int[] minutes={10,20,30,45,60,120};
        String[] labels=chinese?new String[]{"10 分钟","20 分钟","30 分钟","45 分钟","1 小时","2 小时"}
                :new String[]{"10 minutes","20 minutes","30 minutes","45 minutes","1 hour","2 hours"};
        LinearLayout row=card(parent);
        row.addView(text(chinese?"自动联网刷新间隔":"Automatic refresh interval",16,false));
        Spinner choice=new Spinner(this);choice.setMinimumHeight(dp(48));
        choice.setContentDescription(chinese?"自动联网刷新间隔":"Automatic refresh interval");
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);choice.setAdapter(adapter);
        int selected=1;for(int i=0;i<minutes.length;i++)if(minutes[i]==WeatherRefresh.intervalMinutes(this))selected=i;
        choice.setSelection(selected);row.addView(choice,new LinearLayout.LayoutParams(-1,-2));
        choice.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(AdapterView<?> view,View item,int position,long id) {
                if(minutes[position]==WeatherRefresh.intervalMinutes(WeatherWidgetManagementActivity.this))return;
                try{WeatherRefresh.setIntervalMinutes(WeatherWidgetManagementActivity.this,minutes[position]);}
                catch(RuntimeException error){
                    Toast.makeText(WeatherWidgetManagementActivity.this,chinese?"无法保存，请重试":"Could not save. Try again.",Toast.LENGTH_LONG).show();
                    android.util.Log.e("WeatherRefresh","Cannot apply refresh interval",error);
                }
            }
            @Override public void onNothingSelected(AdapterView<?> view){}
        });
        note(parent,chinese?"所有小组件共用此间隔。关闭夜间自动更新时，23:00–次日 04:00 暂停联网；系统省电策略可能推迟刷新。"
                :"Applies to all widgets. With night updates off, automatic refresh pauses from 23:00 to 04:00. Battery saving may delay updates.");
    }

    private String cityLabel(JSONArray cities,String id,String automatic) {
        if(id.isEmpty())return automatic;
        for(int i=0;i<cities.length();i++) {
            JSONObject city=cities.optJSONObject(i);if(id.equals(city.optString("cityId")))return city.optString("cityName",id);
        }
        return chinese?"已选城市暂不可用":"Selected city unavailable";
    }
}
