package dev.weather.oneplus;

import android.app.Activity;
import android.os.*;
import android.view.*;
import android.widget.*;
import android.util.Log;
import android.content.SharedPreferences;
import org.json.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class WeatherActivity extends Activity implements SurfaceHolder.Callback {
    private static boolean diagnosticsOpened;
    private final Handler main = new Handler(Looper.getMainLooper());
    private SurfaceView surface;
    private TextView status;
    private AndroidStartupSplash startupSplash;
    private boolean started;
    private AndroidTextInput textInput;
    private AndroidPermissions permissions;
    private AndroidPreferences preferences;
    private AndroidConsent consent;
    private AndroidLocation location;
    private AndroidPigeonPreferences pigeonPreferences;
    private AndroidPigeonPathProvider pigeonPaths;
    private MajesticSurface majestic;
    private AndroidPlatformServices platformServices;
    private AndroidWeatherPages weatherPages;
    private AndroidWeatherNotifications notifications;
    private AndroidMiPushBridge push;
    private AndroidMiPushBridge.Sender pushSender;
    private boolean networkConsent;
    private boolean requestedRouteOpened;
    private boolean destroyed;
    private boolean frameworkHandlesBack;
    private boolean backRegistered;
    private String currentRoute;
    private android.window.OnBackInvokedCallback backCallback;
    private final java.util.concurrent.ExecutorService cacheWorker=java.util.concurrent.Executors.newSingleThreadExecutor();
    private final Runnable cacheSync=this::syncSavedWeather;
    private WindowInsets lastInsets;
    private PrintWriter channelLog;
    private native String startEngine(String libraries,String extra,String data,android.content.res.AssetManager assets,String backend);
    private native void nativeSurface(Surface surface,int width,int height,double density);
    private native void nativeReply(long id,byte[] bytes);
    private native void nativeSend(String name,byte[] bytes);
    private native void nativeTouch(ByteBuffer packet,int length);
    private native void nativeInsets(int left,int top,int right,int bottom,int imeBottom,int gestureLeft,int gestureTop,int gestureRight,int gestureBottom,double touchSlop);
    private native void nativeDetach();
    private native void nativeAttach();
    @Override public void onCreate(Bundle state){
        startupSplash=AndroidStartupSplash.install(this,this::configureSystemBars);
        super.onCreate(state);
        configureSystemBars();
        try{channelLog=new PrintWriter(new OutputStreamWriter(new FileOutputStream(new File(getFilesDir(),"channels.log"),diagnosticsOpened),StandardCharsets.UTF_8),true);diagnosticsOpened=true;}
        catch(IOException error){Log.e("WeatherChannel","Cannot create diagnostics",error);}
        FrameLayout root=new FrameLayout(this);
        textInput=new AndroidTextInput(this,this::send);
        permissions=new AndroidPermissions(this,this::sendMethod);
        preferences=new AndroidPreferences(this);
        consent=new AndroidConsent(this,this::sendMethod);
        location=new AndroidLocation(this);
        pigeonPreferences=new AndroidPigeonPreferences(this);
        pigeonPaths=new AndroidPigeonPathProvider(this);
        platformServices=new AndroidPlatformServices(this,(channel,bytes)->nativeSend(channel,bytes));
        weatherPages=new AndroidWeatherPages(this);
        notifications=new AndroidWeatherNotifications(this);
        push=new AndroidMiPushBridge(this);
        pushSender=(name,bytes)->{if(started&&!destroyed)nativeSend(name,bytes);};
        AndroidMiPushBridge.attach(pushSender);
        networkConsent=WeatherRefresh.networkAllowed(this);
        if(Build.VERSION.SDK_INT>=33)backCallback=this::dispatchBack;
        surface=new SurfaceView(this){
            @Override public boolean onCheckIsTextEditor(){return textInput.client>=0;}
            @Override public android.view.inputmethod.InputConnection onCreateInputConnection(android.view.inputmethod.EditorInfo info){return textInput.connection(info);}
            @Override public boolean onTouchEvent(MotionEvent event){return forwardTouch(event);}
        };
        surface.setFocusable(true);surface.setFocusableInTouchMode(true);textInput.view=surface;root.addView(surface);
        status=new TextView(this);status.setPadding(20,80,20,20);status.setVisibility(View.GONE);root.addView(status);
        setContentView(root);surface.getHolder().addCallback(this);
        root.setOnApplyWindowInsetsListener((view,insets)->{lastInsets=insets;applyInsets();return insets;});
        try{
            System.loadLibrary("weatherhost");
            majestic=new MajesticSurface(this,root,surface);
            File extra=new File(getFilesDir(),"runtime");extra.mkdirs();
            long installed=getPackageManager().getPackageInfo(getPackageName(),0).lastUpdateTime;
            SharedPreferences extraction=getSharedPreferences("runtime_extraction",MODE_PRIVATE);
            boolean extracted=extraction.getLong("installed",-1)==installed;
            for(String name:getAssets().list("runtime")){
                File file=new File(extra,name);if(file.exists())file.setWritable(true);
                if(extracted&&file.isFile()){file.setReadOnly();continue;}
                try(InputStream in=getAssets().open("runtime/"+name);FileOutputStream out=new FileOutputStream(file)){
                    byte[] b=new byte[32768];int n;while((n=in.read(b))>=0)out.write(b,0,n);
                }file.setReadOnly();
            }
            extraction.edit().putLong("installed",installed).commit();
            String error=startEngine(getApplicationInfo().nativeLibraryDir,extra.getAbsolutePath(),getFilesDir().getAbsolutePath(),getAssets(),getIntent().getStringExtra("render_backend"));
            started=error==null;
            if(error!=null){status.setText(error);status.setVisibility(View.VISIBLE);startupSplash.onFirstFrame();}else{
                sendSystemSettings();
                send("flutter/localization","{\"method\":\"setLocale\",\"args\":[\"zh\",\"CN\",\"\",\"\"]}");
            }
        }catch(Throwable error){status.setText("天气启动失败，请重新打开。");status.setVisibility(View.VISIBLE);startupSplash.onFirstFrame();Log.e("WeatherHost","startup",error);}
    }
    void send(String channel,String text){nativeSend(channel,text.getBytes(StandardCharsets.UTF_8));}
    private void configureSystemBars(){
        Window window=getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS|WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        window.setStatusBarColor(android.graphics.Color.TRANSPARENT);
        window.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        if(Build.VERSION.SDK_INT>=29){window.setStatusBarContrastEnforced(false);window.setNavigationBarContrastEnforced(false);}
        if(Build.VERSION.SDK_INT>=30)window.setDecorFitsSystemWindows(false);
        else window.getDecorView().setSystemUiVisibility(window.getDecorView().getSystemUiVisibility()
            |View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        if(Build.VERSION.SDK_INT>=28){
            WindowManager.LayoutParams attributes=window.getAttributes();
            attributes.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(attributes);
        }
    }
    private void sendSystemSettings(){
        if(!started)return;
        android.content.res.Configuration config=getResources().getConfiguration();
        boolean dark=(config.uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES;
        try{
            JSONObject settings=new JSONObject()
                .put("platformBrightness",dark?"dark":"light")
                .put("textScaleFactor",(double)config.fontScale)
                .put("alwaysUse24HourFormat",android.text.format.DateFormat.is24HourFormat(this));
            send("flutter/settings",settings.toString());
            Log.i("WeatherHost","System theme synchronized: "+(dark?"dark":"light"));
        }catch(JSONException error){Log.e("WeatherHost","Cannot synchronize system settings",error);}
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration config){
        super.onConfigurationChanged(config);
        sendSystemSettings();
        if(started){surface.requestApplyInsets();applyInsets();}
    }
    void sendMethod(String channel,String method,Object args){
        if(channelLog!=null)channelLog.println("OUT "+channel+" :: "+method+" args="+String.valueOf(args));
        try{send(channel,new JSONObject().put("method",method).put("args",args==null?JSONObject.NULL:JSONObject.wrap(args)).toString());}
        catch(JSONException error){Log.e("WeatherChannel","Cannot encode platform event",error);}
    }
    public void onFirstFrame(){main.post(()->{if(!destroyed){status.setVisibility(View.GONE);startupSplash.onFirstFrame();}});}
    public void onPlatformMessage(String channel,byte[] data,long id){main.post(()->{if(!destroyed)handle(channel,data,id);else if(id!=0)nativeReply(id,null);});}
    private void handle(String channel,byte[] data,long id){
        if(channel.equals("flutter/isolate")){Log.i("WeatherChannel","Dart isolate is running");return;}
        if(channel.equals("flutter/accessibility")){
            try{
                Object message=Codec.read(Codec.buffer(data));
                if(message instanceof Map){
                    Map<?,?> event=(Map<?,?>)message;
                    if("announce".equals(event.get("type"))&&event.get("data") instanceof Map){
                        Object text=((Map<?,?>)event.get("data")).get("message");
                        if(text instanceof String)surface.announceForAccessibility((String)text);
                    }
                }
            }catch(RuntimeException error){Log.w("WeatherChannel","Invalid accessibility event",error);}
            if(id!=0)nativeReply(id,Codec.encode(null,false));return;
        }
        byte[] pigeonReply=pigeonPreferences.handleMessage(channel,data);
        if(pigeonReply==null)pigeonReply=pigeonPaths.handleMessage(channel,data);
        if(pigeonReply!=null){if(channelLog!=null)channelLog.println("PIGEON "+channel);nativeReply(id,pigeonReply);return;}
        boolean json=data.length>0&&(data[0]=='{'||data[0]=='[');
        String method="";Object args=null;
        try{
            if(json){JSONObject request=new JSONObject(new String(data,StandardCharsets.UTF_8));method=request.optString("method");args=request.opt("args");}
            else if(data.length>0){ByteBuffer b=Codec.buffer(data);Object first=Codec.read(b);method=String.valueOf(first);if(b.hasRemaining())args=Codec.read(b);}
            Log.i("WeatherChannel",channel+" :: "+method+" args="+String.valueOf(args));
            if(channelLog!=null)channelLog.println(channel+" :: "+method+" args="+String.valueOf(args));
            if(location.handle(channel,method,args,new AndroidLocation.Result(){
                public void success(Object result){replyResult(channel,json,id,result);}
                public void error(String code,String message){replyError(json,id,code,message);}
            }))return;
            Object result=handleCall(channel,method,args);
            if(result==UNSUPPORTED){Log.w("WeatherChannel","UNIMPLEMENTED "+channel+" :: "+method);if(channelLog!=null)channelLog.println("UNIMPLEMENTED "+channel+" :: "+method);nativeReply(id,null);return;}
            replyResult(channel,json,id,result);
        }catch(AndroidWeatherNotifications.NotificationException error){replyError(json,id,error.code,error.getMessage());}
        catch(Throwable error){Log.e("WeatherChannel",channel+" :: "+method,error);if(channelLog!=null)error.printStackTrace(channelLog);nativeReply(id,null);}
    }
    private void replyResult(String channel,boolean json,long id,Object result){
        if(id==0)return;
        if(json){JSONArray response=new JSONArray();response.put(result==null?JSONObject.NULL:JSONObject.wrap(result));nativeReply(id,response.toString().getBytes(StandardCharsets.UTF_8));}
        else nativeReply(id,Codec.encode(result,!channel.equals("weather_channel")));
    }
    private void replyError(boolean json,long id,String code,String message){
        if(channelLog!=null)channelLog.println("ERROR "+code+" "+message);
        if(id==0)return;
        if(json){nativeReply(id,new JSONArray().put(code).put(message).put(JSONObject.NULL).toString().getBytes(StandardCharsets.UTF_8));}
        else{ByteArrayOutputStream b=new ByteArrayOutputStream();b.write(1);Codec.write(b,code);Codec.write(b,message);Codec.write(b,null);nativeReply(id,b.toByteArray());}
    }
    private static final Object UNSUPPORTED=new Object();
    private Object handleCall(String channel,String method,Object args)throws Exception{
        Object pushResult=push.handleCall(channel,method,args);
        if(pushResult!=AndroidMiPushBridge.UNHANDLED)return pushResult;
        Object notificationResult=notifications.handleCall(channel,method,args);
        if(notificationResult!=AndroidWeatherNotifications.UNHANDLED)return notificationResult;
        Object pageResult=weatherPages.handleCall(channel,method,args);
        if(pageResult!=AndroidWeatherPages.UNHANDLED)return pageResult;
        Object platformResult=platformServices.handleCall(channel,method,args);
        if(platformResult!=AndroidPlatformServices.UNHANDLED)return platformResult;
        Object deviceResult=AndroidDeviceProfile.handleCall(channel,method,args);
        if(deviceResult!=AndroidDeviceProfile.UNHANDLED)return deviceResult;
        Object permissionResult=permissions.handleCall(channel,method,args);
        if(permissionResult!=AndroidPermissions.UNHANDLED)return permissionResult;
        Object preferenceResult=preferences.handleCall(channel,method,args);
        if(preferenceResult!=AndroidPreferences.UNHANDLED)return preferenceResult;
        Object consentResult=consent.handleCall(channel,method,args);
        if(consentResult!=AndroidConsent.UNHANDLED)return consentResult;
        if(channel.equals("flutter/textinput"))return textInput.handle(method,args);
        if(channel.equals("flutter/keyboard"))return Collections.emptyMap();
        if(channel.equals("flutter/processtext")&&method.equals("ProcessText.queryTextActions"))return Collections.emptyMap();
        if(channel.equals("flutter/navigation")){
            if(method.equals("selectSingleEntryHistory"))return null;
            if(method.equals("routeInformationUpdated")){
                currentRoute=args instanceof JSONObject?((JSONObject)args).optString("uri",""):null;
                String route=getIntent().getStringExtra("open_route");
                if(!requestedRouteOpened&&("/searchCity".equals(route)||"/cityManagement".equals(route))){
                    requestedRouteOpened=true;main.post(()->sendMethod("flutter/navigation","pushRoute",route));
                }
                maybeOpenCityPicker();
                if("/home".equals(currentRoute)){
                    platformServices.dispatchWeatherIntent(getIntent());
                    notifications.dispatchClick(getIntent(),(name,bytes)->nativeSend(name,bytes));
                }
                main.removeCallbacks(cacheSync);main.postDelayed(cacheSync,1000);
                return null;
            }
        }
        if(channel.equals("dev.fluttercommunity.plus/package_info")&&method.equals("getAll")){
            android.content.pm.PackageInfo info=getPackageManager().getPackageInfo(getPackageName(),0);
            Map<String,Object> result=new LinkedHashMap<>();
            result.put("appName",getApplicationInfo().loadLabel(getPackageManager()).toString());
            result.put("packageName",getPackageName());result.put("version",info.versionName);
            result.put("buildNumber",Long.toString(info.getLongVersionCode()));
            String installer=getPackageManager().getInstallerPackageName(getPackageName());
            if(installer!=null)result.put("installerStore",installer);
            return result;
        }
        if(channel.equals("com.xiaomi.hyperos/native_ready")){
            main.post(()->send(channel,"{\"method\":\"onReady\",\"args\":null}"));return true;
        }
        if(channel.equals("com.android.package.manager.method.channel")){
            Map<String,Object> info=new LinkedHashMap<>();info.put("versionName","18.0.0.27-R");info.put("versionCode",180000270);info.put("lastUpdateTime",getPackageManager().getPackageInfo(getPackageName(),0).lastUpdateTime);
            info.put("applicationInfo",Collections.singletonMap("flags",getApplicationInfo().flags));return info;
        }
        if(channel.equals("com.android.os.system.properties.method.channel")){
            if(method.equals("getAll"))return Collections.emptyMap();
            JSONObject a=args instanceof JSONObject?(JSONObject)args:new JSONObject();
            if(method.equals("getString")||method.equals("get"))return a.optString("key").equals("ro.miui.region")?getResources().getConfiguration().getLocales().get(0).getCountry():a.optString("defaultValue","");
            if(method.equals("getInt")||method.equals("getLong"))return a.optLong("defaultValue",0);
            if(method.equals("getBoolean")||method.equals("getBool"))return a.optBoolean("defaultValue",false);
        }
        if(channel.equals("com.xiaomi.hyperos/configuration")&&(method.equals("configuration")||method.equals("configurations")))return configuration();
        if(channel.equals("com.xiaomi.hyperos/configuration")&&method.equals("roundedCorner"))return Arrays.asList(0,0,0,0);
        if(channel.equals("com.xiaomi.hyperos/rounded_corner"))return Collections.emptyList();
        if(channel.equals("com.xiaomi.hyperos/device_info"))return method.equals("getDeviceInfo")?Collections.emptyMap():false;
        if(channel.equals("com.xiaomi.hyperos/hyper_material")){
            return 0;
        }
        if(channel.equals("com.xiaomi.hyperos/system_navigation"))return 2;
        if(channel.equals("com.miui.hyper.system_bridge")&&method.equals("is_user_unlocked"))return ((android.os.UserManager)getSystemService(USER_SERVICE)).isUserUnlocked();
        if(channel.equals("com.miui.hyper.system_bridge")&&method.equals("get_files_dir"))return getFilesDir().getAbsolutePath();
        if(channel.equals("com.miui.hyper.system_bridge")&&method.equals("get_network_info"))return networkInfo();
        if(channel.equals("weather_channel")&&method.equals("get_app_path"))return getFilesDir().getAbsolutePath();
        if(channel.equals("plugins.flutter.io/path_provider"))return method.toLowerCase(Locale.ROOT).contains("temporary")?getCacheDir().getAbsolutePath():getFilesDir().getAbsolutePath();
        if(channel.equals("com.android.os.provider.settings.method.channel")&&(method.equals("getLong")||method.equals("getString"))){
            String key,namespace;
            if(args instanceof List){List<?> list=(List<?>)args;key=(String)list.get(0);namespace=(String)list.get(1);}
            else{JSONArray list=(JSONArray)args;key=list.getString(0);namespace=list.getString(1);}
            if(method.equals("getString")){
                if(namespace.equals("secure"))return android.provider.Settings.Secure.getString(getContentResolver(),key);
                if(namespace.equals("global"))return android.provider.Settings.Global.getString(getContentResolver(),key);
                if(namespace.equals("system"))return android.provider.Settings.System.getString(getContentResolver(),key);
                return UNSUPPORTED;
            }
            if(key.equals("location_mode")&&namespace.equals("secure")){
                android.location.LocationManager manager=getSystemService(android.location.LocationManager.class);
                return manager!=null&&manager.isLocationEnabled()?3L:0L;
            }
            if(namespace.equals("secure"))return android.provider.Settings.Secure.getLong(getContentResolver(),key,0L);
            if(namespace.equals("global"))return android.provider.Settings.Global.getLong(getContentResolver(),key,0L);
            if(namespace.equals("system"))return android.provider.Settings.System.getLong(getContentResolver(),key,0L);
            return UNSUPPORTED;
        }
        if(channel.equals("weather_method_channel")){
            if(method.equals("is_accessibility_enabled")){
                android.view.accessibility.AccessibilityManager manager=getSystemService(android.view.accessibility.AccessibilityManager.class);
                return manager!=null&&manager.isEnabled();
            }
            if(method.equals("update_cta_result")){networkConsent=Boolean.TRUE.equals(args);WeatherRefresh.setNetworkAllowed(this,networkConsent);maybeOpenCityPicker();return null;}
            // Optional analytics have no local observer or uploader in this ordinary APK.
            if(method.startsWith("track_"))return null;
            if(method.equals("send_broadcast")&&args instanceof JSONObject){
                if("com.miui.weather2.FROM_FOREGROUND_TO_WAKE_BACKGROUND".equals(((JSONObject)args).optString("action"))){
                    main.removeCallbacks(cacheSync);main.postDelayed(cacheSync,1000);
                    WeatherRefresh.ensureScheduled(this);return null;
                }
            }
            if(method.equals("get_network_info_is_connected")){
                android.net.ConnectivityManager manager=getSystemService(android.net.ConnectivityManager.class);
                android.net.NetworkInfo info=manager==null?null:manager.getActiveNetworkInfo();
                return info!=null&&info.isConnected();
            }
            if(method.equals("get_color_mode"))return getWindow().getColorMode();
            if(method.equals("get_job_is_working"))return WeatherRefresh.originalJobScheduled(this,args);
            if(method.equals("requestScheduleTask"))return WeatherRefresh.requestFromOriginal(this,args);
            if(method.equals("get_network_country_iso")){
                android.telephony.TelephonyManager phone=getSystemService(android.telephony.TelephonyManager.class);
                return phone==null?"":phone.getNetworkCountryIso();
            }
        }
        if(channel.equals("flutter/platform"))return platform(method,args);
        if(channel.equals("weather_channel")&&(method.equals("dart_is_ready")||method.equals("page_route_change")))return null;
        if(channel.equals("com.xiaomi.hyperos/trace"))return null;
        return UNSUPPORTED;
    }
    private Object platform(String method,Object args)throws Exception{
        if(method.equals("SystemNavigator.setFrameworkHandlesBack")){
            frameworkHandlesBack=Boolean.TRUE.equals(args);updateBackCallback();return null;
        }
        if(method.equals("SystemChrome.setSystemUIOverlayStyle")&&args instanceof JSONObject){
            configureSystemBars();
            JSONObject style=(JSONObject)args;
            if(Build.VERSION.SDK_INT>=30){
                WindowInsetsController controller=getWindow().getInsetsController();
                if(controller!=null){
                    int mask=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    int value=("Brightness.dark".equals(style.optString("statusBarIconBrightness"))?WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS:0)
                            |("Brightness.dark".equals(style.optString("systemNavigationBarIconBrightness"))?WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS:0);
                    controller.setSystemBarsAppearance(value,mask);
                }
            }
            return null;
        }
        if(method.equals("SystemChrome.setEnabledSystemUIMode")&&"SystemUiMode.edgeToEdge".equals(args)){
            configureSystemBars();return null;
        }
        if(method.equals("Clipboard.hasStrings")){
            android.content.ClipboardManager clipboard=getSystemService(android.content.ClipboardManager.class);
            return Collections.singletonMap("value",clipboard!=null&&clipboard.hasPrimaryClip());
        }
        if(method.equals("LiveText.isLiveTextInputAvailable"))return false;
        if(method.equals("SystemSound.play")){surface.playSoundEffect(SoundEffectConstants.CLICK);return null;}
        if(method.equals("SystemNavigator.pop")){finish();return null;}
        if(method.equals("SystemChrome.setApplicationSwitcherDescription")&&args instanceof JSONObject){
            JSONObject a=(JSONObject)args;setTaskDescription(new android.app.ActivityManager.TaskDescription(a.optString("label"),null,a.optInt("primaryColor",0)|0xff000000));return null;
        }
        return null;
    }
    private String networkInfo()throws JSONException{
        android.net.ConnectivityManager manager=getSystemService(android.net.ConnectivityManager.class);
        android.net.NetworkInfo info=manager==null?null:manager.getActiveNetworkInfo();
        if(info==null)return null;
        return new JSONObject().put("networkType",info.getType()).put("subTypeName",info.getSubtypeName())
                .put("subType",info.getSubtype()).put("state",info.getState().name()).put("typeName",info.getTypeName())
                .put("extraInfo",info.getExtraInfo()).put("detailedState",info.getDetailedState().name())
                .put("reason",info.getReason()).put("isAvailable",info.isAvailable()).put("isRoaming",info.isRoaming())
                .put("isFailover",info.isFailover()).toString();
    }
    private JSONObject configuration()throws JSONException{
        android.util.DisplayMetrics d=getResources().getDisplayMetrics();android.content.res.Configuration c=getResources().getConfiguration();
        JSONObject j=new JSONObject();String[] zero={"color_mode","screen_type","display_id"};for(String k:zero)j.put(k,0);
        j.put("orientation",c.orientation).put("screen_layout",c.screenLayout).put("screen_width_dp",c.screenWidthDp).put("screen_height_dp",c.screenHeightDp).put("smallest_screen_width_dp",c.smallestScreenWidthDp).put("density_dpi",d.densityDpi).put("display_name","Built-in Screen").put("display_logical_density_dpi",d.densityDpi).put("display_shape_width",d.widthPixels).put("display_shape_height",d.heightPixels);
        JSONObject zeroRect=new JSONObject().put("left",0).put("top",0).put("right",0).put("bottom",0);
        JSONObject cut=new JSONObject(zeroRect.toString());for(String side:new String[]{"left","top","right","bottom"})cut.put("bounding_rect_"+side,zeroRect);
        j.put("display_cutout",cut).put("window_bounds",new JSONObject().put("left",0).put("top",0).put("right",d.widthPixels).put("bottom",d.heightPixels)).put("is_multi_window",isInMultiWindowMode());
        j.put("dm_width_pixels",d.widthPixels).put("dm_height_pixels",d.heightPixels).put("dm_density",d.density).put("dm_density_dpi",d.densityDpi).put("dm_scaled_density",d.scaledDensity).put("dm_x_dpi",d.xdpi).put("dm_y_dpi",d.ydpi);return j;
    }
    public void surfaceCreated(SurfaceHolder holder){}
    private boolean forwardTouch(MotionEvent event){
        if(!started)return false;
        int action=event.getActionMasked();int change=action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_POINTER_DOWN?4:action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_POINTER_UP?6:action==MotionEvent.ACTION_MOVE?5:action==MotionEvent.ACTION_CANCEL?0:-1;
        if(change<0)return false;
        boolean all=action==MotionEvent.ACTION_MOVE||action==MotionEvent.ACTION_CANCEL;
        int count=all?event.getPointerCount():1;ByteBuffer b=ByteBuffer.allocateDirect(count*312).order(ByteOrder.LITTLE_ENDIAN);
        for(int k=0;k<count;k++){
            int i=all?k:event.getActionIndex(),offset=k*312;
            b.putLong(offset+8,event.getEventTime()*1000);b.putLong(offset+16,change);
            b.putLong(offset+40,event.getPointerId(i));b.putDouble(offset+56,event.getX(i));b.putDouble(offset+64,event.getY(i));
            b.putLong(offset+88,change==4||change==5?1:0);b.putDouble(offset+112,event.getPressure(i));b.putDouble(offset+128,1);
            b.putDouble(offset+152,event.getSize(i));b.putDouble(offset+160,event.getToolMajor(i)/2);b.putDouble(offset+168,event.getToolMinor(i)/2);b.putDouble(offset+192,event.getOrientation(i));b.putDouble(offset+264,1);
        }
        nativeTouch(b,count*312);return true;
    }
    private void applyInsets(){
        if(!started||lastInsets==null)return;
        int left=lastInsets.getSystemWindowInsetLeft(),top=lastInsets.getSystemWindowInsetTop(),right=lastInsets.getSystemWindowInsetRight(),bottom=lastInsets.getSystemWindowInsetBottom(),ime=0,gl=0,gt=0,gr=0,gb=0;
        if(Build.VERSION.SDK_INT>=30){
            android.graphics.Insets safe=lastInsets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            android.graphics.Insets keyboard=lastInsets.getInsets(WindowInsets.Type.ime());
            android.graphics.Insets gestures=lastInsets.getInsets(WindowInsets.Type.systemGestures());
            left=safe.left;top=safe.top;right=safe.right;bottom=safe.bottom;ime=keyboard.bottom;
            gl=gestures.left;gt=gestures.top;gr=gestures.right;gb=gestures.bottom;
        }
        nativeInsets(left,top,right,ime>0?0:bottom,ime,gl,gt,gr,gb,ViewConfiguration.get(this).getScaledTouchSlop());
    }
    public void surfaceChanged(SurfaceHolder holder,int format,int w,int h){if(started){nativeSurface(holder.getSurface(),w,h,getResources().getDisplayMetrics().density);applyInsets();}}
    public void surfaceDestroyed(SurfaceHolder holder){if(started)nativeSurface(null,0,0,1);}
    private void maybeOpenCityPicker(){
        if(destroyed||requestedRouteOpened||!networkConsent||!"/home".equals(currentRoute))return;
        if(!WeatherDatabaseBridge.hasSelectedCity(this)){
            requestedRouteOpened=true;
            main.post(()->{if(!destroyed)sendMethod("flutter/navigation","pushRoute","/searchCity");});
        }
    }
    private void syncSavedWeather(){
        if(destroyed)return;
        importSavedWeather(false);
        main.removeCallbacks(cacheSync);
        if(WeatherWidgetProvider.hasWidgets(this))main.postDelayed(cacheSync,30000);
    }
    private void importSavedWeather(boolean leavingApp){
        android.content.Context app=getApplicationContext();
        cacheWorker.execute(()->{
            try{
            WeatherWidgetData data=WeatherDatabaseBridge.readCached(app);
            if(data!=null)WeatherWidgetCache.publishCached(app,data);
            else if(!WeatherDatabaseBridge.hasSelectedCity(app))WeatherWidgetCache.clear(app);
            WeatherRefresh.ensureScheduled(app);
            if(leavingApp&&WeatherWidgetProvider.hasWidgets(app)){
                WeatherWidgetProvider.updateAll(app);
                WeatherRefresh.requestAutomatic(app);
            }
            }catch(Exception error){Log.w("WeatherWidget","Cannot synchronize saved weather",error);}
        });
    }
    private void dispatchBack(){
        if(started&&frameworkHandlesBack)sendMethod("flutter/navigation","popRoute",null);
        else finish();
    }
    private void updateBackCallback(){
        if(Build.VERSION.SDK_INT<33||backCallback==null)return;
        if(frameworkHandlesBack&&!backRegistered){getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,backCallback);backRegistered=true;}
        else if(!frameworkHandlesBack&&backRegistered){getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);backRegistered=false;}
    }
    @Override public void onBackPressed(){dispatchBack();}
    @Override protected void onStart(){super.onStart();if(started)send("flutter/lifecycle","AppLifecycleState.inactive");}
    @Override protected void onResume(){
        super.onResume();
        configureSystemBars();
        if(started){
            nativeAttach();if(majestic!=null)majestic.attach();
            sendSystemSettings();
            if(surface.getHolder().getSurface().isValid()&&surface.getWidth()>0&&surface.getHeight()>0){
                nativeSurface(surface.getHolder().getSurface(),surface.getWidth(),surface.getHeight(),getResources().getDisplayMetrics().density);applyInsets();
            }
            send("flutter/lifecycle","AppLifecycleState.resumed");
        }
        if(permissions!=null)permissions.onResume();if(location!=null)location.onResume();
        main.removeCallbacks(cacheSync);main.postDelayed(cacheSync,1000);
    }
    @Override protected void onNewIntent(android.content.Intent intent){
        super.onNewIntent(intent);setIntent(intent);
        if(intent.getBooleanExtra("pin_widget",false)){
            android.appwidget.AppWidgetManager manager=getSystemService(android.appwidget.AppWidgetManager.class);
            if(manager!=null&&manager.isRequestPinAppWidgetSupported())
                manager.requestPinAppWidget(new android.content.ComponentName(this,WeatherWidgetProvider.class),null,null);
        }
        String route=intent.getStringExtra("open_route");
        if(started&&("/searchCity".equals(route)||"/cityManagement".equals(route))){
            requestedRouteOpened=true;sendMethod("flutter/navigation","pushRoute",route);
        }
        if(started){
            platformServices.dispatchWeatherIntent(intent);
            notifications.dispatchClick(intent,(name,bytes)->nativeSend(name,bytes));
        }
    }
    @Override protected void onPause(){if(majestic!=null)majestic.pause();if(permissions!=null)permissions.onPause();if(location!=null)location.onPause();if(started)send("flutter/lifecycle","AppLifecycleState.inactive");super.onPause();}
    @Override protected void onStop(){
        main.removeCallbacks(cacheSync);
        if(!destroyed&&!isChangingConfigurations()&&WeatherWidgetProvider.hasWidgets(this))importSavedWeather(true);
        if(started)send("flutter/lifecycle","AppLifecycleState.paused");
        super.onStop();
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,android.content.Intent data){
        if(permissions!=null&&permissions.onActivityResult(requestCode,resultCode,data))return;
        if(consent!=null&&consent.onActivityResult(requestCode,resultCode,data))return;
        super.onActivityResult(requestCode,resultCode,data);
    }
    @Override public void onRequestPermissionsResult(int requestCode,String[] names,int[] grants){
        if(permissions==null||!permissions.onRequestPermissionsResult(requestCode,names,grants))super.onRequestPermissionsResult(requestCode,names,grants);
    }
    @Override protected void onDestroy(){
        AndroidMiPushBridge.detach(pushSender);
        destroyed=true;main.removeCallbacks(cacheSync);cacheWorker.shutdown();
        if(permissions!=null)permissions.onDestroy();if(consent!=null)consent.onDestroy();if(location!=null)location.onDestroy();
        if(platformServices!=null)platformServices.onDestroy();
        if(Build.VERSION.SDK_INT>=33&&backRegistered)getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        if(majestic!=null)majestic.close();
        if(started){nativeDetach();started=false;}
        if(channelLog!=null)channelLog.close();super.onDestroy();
    }
}
