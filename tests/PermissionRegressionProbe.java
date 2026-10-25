package dev.weather.oneplus;

import android.Manifest;
import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Disposable, ordinary-UID Activity exercising the production permission bridge. */
public final class PermissionRegressionProbe extends Activity {
    private AndroidPermissions permissions;
    private AndroidLocation location;
    private final JSONArray events=new JSONArray();
    private final Map<String,Object> state=new LinkedHashMap<>();
    private void save(){
        try {
            state.put("events",events);
            state.put("fine",checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION));
            state.put("coarse",checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION));
            state.put("background",checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION));
            Files.write(new File(getFilesDir(),"result.json").toPath(),new JSONObject(state).toString().getBytes(StandardCharsets.UTF_8));
        }catch(Exception error){throw new RuntimeException(error);}
    }
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        TextView text=new TextView(this);text.setText("Weather permission regression probe");setContentView(text);
        permissions=new AndroidPermissions(this,(channel,method,args)->{
            try {
                events.put(new JSONObject().put("channel",channel).put("method",method)
                        .put("arguments",args instanceof String ? new JSONObject((String)args) : JSONObject.wrap(args))
                        .put("stringResult",args instanceof String));
                save();
            }catch(Exception error){throw new RuntimeException(error);}
        });
        location=new AndroidLocation(this,permissions);
        String scenario=getIntent().getStringExtra("scenario");
        if("backend".equals(scenario)){
            // The backend is invoked after onResume, without any Dart grant request.
            getWindow().getDecorView().post(()->{
                state.put("accepted",location.handle("weather_location","location_amp_nlp",null,new AndroidLocation.Result(){
                    public void success(Object value){
                        try {events.put(new JSONObject().put("kind","location").put("value",JSONObject.wrap(value)));save();}
                        catch(Exception error){throw new RuntimeException(error);}
                    }
                    public void error(String code,String message){
                        try {events.put(new JSONObject().put("kind","location").put("errorCode",code));save();}
                        catch(Exception error){throw new RuntimeException(error);}
                    }
                }));save();
            });
            save();return;
        }
        String requested="background".equals(scenario) ? Manifest.permission.ACCESS_BACKGROUND_LOCATION
                : Manifest.permission.ACCESS_FINE_LOCATION;
        if("legacy".equals(scenario))state.put("accepted",permissions.handleCall("weather_method_channel",
                "request_permission",Arrays.asList(requested,"Human-readable rationale")));
        else {
            Map<String,Object> request=new LinkedHashMap<>();
            request.put("permissions",Arrays.asList(requested));request.put("request_code",1005);
            state.put("accepted",permissions.handleCall(AndroidPermissions.CHANNEL,"request_permissions",request));
        }
        // The request is queued from onCreate before onResume.
        save();
    }
    @Override protected void onResume(){super.onResume();if(permissions!=null)permissions.onResume();if(location!=null)location.onResume();save();}
    @Override protected void onPause(){if(permissions!=null)permissions.onPause();if(location!=null)location.onPause();super.onPause();}
    @Override protected void onDestroy(){if(permissions!=null)permissions.onDestroy();if(location!=null)location.onDestroy();super.onDestroy();}
    @Override public void onRequestPermissionsResult(int code,String[] names,int[] grants){
        if(!permissions.onRequestPermissionsResult(code,names,grants))super.onRequestPermissionsResult(code,names,grants);
    }
    @Override protected void onActivityResult(int request,int result,android.content.Intent data){
        if(!permissions.onActivityResult(request,result,data))super.onActivityResult(request,result,data);
    }
}
