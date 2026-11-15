"""Run production location bridge against deterministic Android fakes, without a device."""
from pathlib import Path
import argparse
import os
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCES = {
    "android/Manifest.java": '''package android; public class Manifest {public static class permission {
 public static final String ACCESS_FINE_LOCATION="fine",ACCESS_COARSE_LOCATION="coarse";}}''',
    "android/content/pm/PackageManager.java": '''package android.content.pm;
public class PackageManager {public static final int PERMISSION_GRANTED=0;}''',
    "android/content/SharedPreferences.java": '''package android.content;
public interface SharedPreferences {boolean getBoolean(String key,boolean fallback);Editor edit();
 interface Editor {Editor putBoolean(String key,boolean value);boolean commit();}}''',
    "android/content/Context.java": '''package android.content;
public class Context {public static final int MODE_PRIVATE=0;
 public final android.location.LocationManager manager=new android.location.LocationManager();
 private final java.util.Map<String,Boolean> flags=new java.util.HashMap<>();
 public Context getApplicationContext(){return this;}
 public <T> T getSystemService(Class<T> type){return type.cast(manager);}
 public SharedPreferences getSharedPreferences(String name,int mode){return new SharedPreferences(){
  public boolean getBoolean(String key,boolean fallback){return flags.containsKey(key)?flags.get(key):fallback;}
  public Editor edit(){return new Editor(){public Editor putBoolean(String key,boolean value){flags.put(key,value);return this;}
   public boolean commit(){return true;}};}};}}''',
    "android/app/Activity.java": '''package android.app;
public class Activity extends android.content.Context {public boolean granted=true,fine=true,finishing,destroyed;
 public int checkSelfPermission(String value){return granted&&(!"fine".equals(value)||fine)?0:-1;}
 public boolean isFinishing(){return finishing;}public boolean isDestroyed(){return destroyed;}}''',
    "android/os/Build.java": '''package android.os;public class Build {
 public static class VERSION {public static int SDK_INT=31;}}''',
    "android/os/Bundle.java": "package android.os;public class Bundle {}",
    "android/os/CancellationSignal.java": '''package android.os;public class CancellationSignal {
 private boolean cancelled;public void cancel(){cancelled=true;}public boolean isCanceled(){return cancelled;}}''',
    "android/os/SystemClock.java": '''package android.os;public class SystemClock {
 public static long elapsedRealtimeNanos(){return 1000000000000L;}}''',
    "android/os/Looper.java": '''package android.os;public class Looper {
 public static final Thread mainThread=Thread.currentThread();
 public static Looper getMainLooper(){return new Looper();}}''',
    "android/os/Handler.java": '''package android.os;import java.util.*;
public class Handler {private static final List<Runnable> posted=new ArrayList<>();
 private static final Map<Runnable,Long> timed=new LinkedHashMap<>();private static long now;
 public Handler(Looper value){}public boolean post(Runnable r){synchronized(posted){posted.add(r);}return true;}
 public boolean postDelayed(Runnable r,long delay){timed.put(r,now+delay);return true;}
 public void removeCallbacks(Runnable r){synchronized(posted){posted.remove(r);}timed.remove(r);}
 public static void flush(){for(;;){Runnable r;synchronized(posted){if(posted.isEmpty())return;r=posted.remove(0);}r.run();}}
 public static void advance(long ms){now+=ms;List<Runnable> due=new ArrayList<>();
  for(Map.Entry<Runnable,Long> e:timed.entrySet())if(e.getValue()<=now)due.add(e.getKey());
  for(Runnable r:due)if(timed.remove(r)!=null)r.run();flush();}
}''',
    "android/util/Log.java": '''package android.util;public class Log {
 public static int i(String t,String s){return 0;}public static int w(String t,String s,Throwable e){return 0;}}''',
    "android/net/Uri.java": '''package android.net;public class Uri {
 private final java.net.URI uri;private Uri(String s){uri=java.net.URI.create(s);}
 public static Uri parse(String s){return new Uri(s);}public String getScheme(){return uri.getScheme();}
 public String getAuthority(){return uri.getAuthority();}public String getQueryParameter(String name){
  for(String item:uri.getQuery().split("&")){String[] pair=item.split("=",2);if(pair[0].equals(name))return pair[1];}return null;}}''',
    "android/location/Address.java": '''package android.location;public class Address {
 public String city="青岛市",admin="山东省",subAdmin,subLocality="黄岛区",street="长江路",premises="科技中心",feature="123";
 public String getLocality(){return city;}public String getSubAdminArea(){return subAdmin;}
 public String getAdminArea(){return admin;}public String getSubLocality(){return subLocality;}
 public String getThoroughfare(){return street;}public String getPremises(){return premises;}
 public String getFeatureName(){return feature;}public String getSubThoroughfare(){return "123";}
 public String getCountryName(){return "中国";}}''',
    "android/location/Location.java": '''package android.location;public class Location {
 private double lat,lon;private final String provider;private long time=android.os.SystemClock.elapsedRealtimeNanos();private float accuracy=10;
 public Location(String p){provider=p;}public Location(Location l){provider=l.provider;lat=l.lat;lon=l.lon;time=l.time;accuracy=l.accuracy;}
 public String getProvider(){return provider;}public double getLatitude(){return lat;}public double getLongitude(){return lon;}
 public void setLatitude(double d){lat=d;}public void setLongitude(double d){lon=d;}
 public void setElapsedRealtimeNanos(long n){time=n;}public long getElapsedRealtimeNanos(){return time;}
 public void setAccuracy(float a){accuracy=a;}public float getAccuracy(){return accuracy;}}''',
    "android/location/LocationListener.java": '''package android.location;public interface LocationListener {
 void onLocationChanged(Location l);void onProviderDisabled(String p);void onProviderEnabled(String p);
 void onStatusChanged(String p,int status,android.os.Bundle extras);}''',
    "android/location/LocationManager.java": '''package android.location;import java.util.*;
public class LocationManager {public static final String NETWORK_PROVIDER="network",GPS_PROVIDER="gps",PASSIVE_PROVIDER="passive";
 public final Set<LocationListener> listeners=new LinkedHashSet<>();public boolean enabled=true,enumerationFails,stateFails;
 public List<String> available=new ArrayList<>(Arrays.asList("fused","network","gps"));
 public final Set<String> disabled=new HashSet<>(),currentFail=new HashSet<>(),updateFail=new HashSet<>(),denied=new HashSet<>(),cacheFail=new HashSet<>(),checkFail=new HashSet<>();
 public final Map<String,Location> last=new HashMap<>();public final List<String> currentAsked=new ArrayList<>(),updatesAsked=new ArrayList<>();
 public final Map<String,java.util.function.Consumer<Location>> current=new LinkedHashMap<>();
 public final Map<String,android.os.CancellationSignal> signals=new HashMap<>();
 public final Map<String,java.util.concurrent.Executor> executors=new HashMap<>();
 public boolean isLocationEnabled(){if(stateFails)throw new IllegalStateException("system unavailable");return enabled;}
 public List<String> getAllProviders(){if(enumerationFails)throw new IllegalStateException("enumeration unavailable");return new ArrayList<>(available);}
 public List<String> getProviders(boolean onlyEnabled){List<String> result=new ArrayList<>(available);if(onlyEnabled)result.removeAll(disabled);return result;}
 public boolean isProviderEnabled(String p){if(checkFail.contains(p))throw new IllegalStateException("state unavailable");return available.contains(p)&&!disabled.contains(p);}
 private void permit(String p){if(!available.contains(p))throw new IllegalArgumentException("missing provider");if(denied.contains(p))throw new SecurityException("denied");}
 public Location getLastKnownLocation(String p){permit(p);if(cacheFail.contains(p))throw new IllegalStateException("cache unavailable");return last.get(p);}
 public void getCurrentLocation(String p,android.os.CancellationSignal signal,java.util.concurrent.Executor executor,java.util.function.Consumer<Location> reply){
  currentAsked.add(p);permit(p);if(currentFail.contains(p))throw new IllegalStateException("current unavailable");current.put(p,reply);signals.put(p,signal);executors.put(p,executor);}
 public void requestLocationUpdates(String p,long ms,float metres,LocationListener l,android.os.Looper loop){
  updatesAsked.add(p);permit(p);if(updateFail.contains(p))throw new IllegalStateException("updates unavailable");listeners.add(l);}
 public void removeUpdates(LocationListener l){listeners.remove(l);}
 public void emitCurrent(String p,Location fix){java.util.function.Consumer<Location> reply=current.remove(p);
  if(reply!=null&&!signals.get(p).isCanceled())executors.get(p).execute(()->reply.accept(fix));}
 public void emit(Location fix){for(String p:new ArrayList<>(current.keySet()))emitCurrent(p,fix);
  for(LocationListener l:new ArrayList<>(listeners))l.onLocationChanged(fix);android.os.Handler.flush();}
 public void emit(){Location fix=new Location("network");fix.setLatitude(36);fix.setLongitude(120);emit(fix);}}''',
    "android/location/Geocoder.java": '''package android.location;import java.util.*;import java.util.concurrent.*;
public class Geocoder {public static boolean present=true;public static volatile boolean throwsError;
 public static volatile CountDownLatch started=new CountDownLatch(1),gate=new CountDownLatch(0),ended=new CountDownLatch(1);
 public static volatile GeocodeListener listener;public static volatile List<Address> addresses=Arrays.asList(new Address());
 public interface GeocodeListener {void onGeocode(List<Address> addresses);void onError(String message);}
 public Geocoder(android.content.Context c,Locale l){}public static boolean isPresent(){return present;}
 public void getFromLocation(double lat,double lon,int max,GeocodeListener reply){listener=reply;}
 public List<Address> getFromLocation(double lat,double lon,int max)throws java.io.IOException {
  if(Thread.currentThread()==android.os.Looper.mainThread)throw new AssertionError("Geocoder blocked main thread");
  CountDownLatch waiting=gate,done=ended;List<Address> result=addresses;boolean fail=throwsError;started.countDown();
  try {while(waiting.getCount()!=0){try{waiting.await();}catch(InterruptedException ignored){}}
   if(fail)throw new java.io.IOException("unavailable");return result;
  }finally{done.countDown();}}
 public static void reset(boolean blocked){started=new CountDownLatch(1);gate=new CountDownLatch(blocked?1:0);
  ended=new CountDownLatch(1);listener=null;throwsError=false;present=true;addresses=Arrays.asList(new Address());}}''',
    "org/json/JSONObject.java": '''package org.json;import java.util.*;
public class JSONObject {private final Map<?,?> value;private static final Map<String,Map<?,?>> saved=new HashMap<>();
 public JSONObject(Map<?,?> value){this.value=new LinkedHashMap<>(value);}
 public JSONObject(String source){if(!saved.containsKey(source))throw new IllegalArgumentException("Bad JSON");value=saved.get(source);}
 public Object opt(String key){return value.get(key);}public String toString(){String result=value.toString();saved.put(result,value);return result;}}''',
    "dev/weather/oneplus/AndroidPermissions.java": '''package dev.weather.oneplus;
public class AndroidPermissions {private final android.app.Activity activity;public boolean pending;public int requests;public GrantResult waiting;
 public AndroidPermissions(android.app.Activity activity){this.activity=activity;}
 public interface GrantResult {void complete(boolean granted);}
 public boolean hasForegroundLocation(){return activity.granted;}
 public void requestForegroundLocation(GrantResult result){requests++;if(pending)waiting=result;else result.complete(activity.granted);}
 public void complete(boolean granted){activity.granted=granted;GrantResult saved=waiting;waiting=null;saved.complete(granted);}}''',
    "dev/weather/oneplus/ColorOsPlaceResolver.java": '''package dev.weather.oneplus;
import java.util.concurrent.*;
public final class ColorOsPlaceResolver {
 public static volatile CountDownLatch started=new CountDownLatch(1),gate=new CountDownLatch(0),ended=new CountDownLatch(1);
 public static volatile PlaceResult result;public static volatile boolean throwsError;public static int calls;
 public static volatile double latitude,longitude;public static volatile String language;
 public static final class PlaceResult {
  public final String cityName,placeName,placeId,locality,adminArea,countryName;
  public final int placeType,placeDetailStatus;
  public PlaceResult(String city,String place,int status){cityName=city;placeName=place;placeId="verified-id";
   locality="青岛市";adminArea="山东省";countryName="中国";placeType=1;placeDetailStatus=status;}
  public boolean hasPlace(){return placeName!=null&&!placeName.isEmpty()&&placeDetailStatus==0;}}
 public static PlaceResult resolve(android.content.Context context,double lat,double lon,String lang)throws java.io.IOException {
  if(Thread.currentThread()==android.os.Looper.mainThread)throw new AssertionError("Place resolver blocked main thread");
  latitude=lat;longitude=lon;language=lang;calls++;CountDownLatch waiting=gate,done=ended;
  PlaceResult saved=result;boolean fail=throwsError;started.countDown();
  try {while(waiting.getCount()!=0){try{waiting.await();}catch(InterruptedException ignored){}}
   if(fail)throw new java.io.IOException("unavailable");return saved;}finally{done.countDown();}}
 public static void reset(boolean blocked){started=new CountDownLatch(1);gate=new CountDownLatch(blocked?1:0);
  ended=new CountDownLatch(1);result=null;throwsError=false;calls=0;}
}''',
    "dev/weather/oneplus/AndroidLocationBridgeCheck.java": '''package dev.weather.oneplus;
import android.app.Activity;import android.location.*;import android.os.*;import java.util.*;import java.util.concurrent.*;
public class AndroidLocationBridgeCheck {
 static int checks;static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
 static class Reply implements AndroidLocation.Result {int replies;Object value;String code;
  public void success(Object v){check(Thread.currentThread()==Looper.mainThread,"main-thread success");replies++;value=v;}
  public void error(String c,String m){check(Thread.currentThread()==Looper.mainThread,"main-thread error");replies++;code=c;}}
 static AndroidLocation location;static Activity activity;
 static void setup(int sdk,boolean blocked){Build.VERSION.SDK_INT=sdk;Geocoder.reset(blocked);ColorOsPlaceResolver.reset(false);activity=new Activity();
  location=new AndroidLocation(activity,new AndroidPermissions(activity));location.onResume();}
 static Reply begin(String channel,String method,Object arguments){Reply r=new Reply();
  check(location.handle(channel,method,arguments,r),"supported path");Handler.flush();return r;}
 static Reply reverse(){Map<String,Object> args=new HashMap<>();
  args.put("uri","content://com.miui.weather2map?latitude=36&longitude=120");
  return begin("weather_method_channel","query_xms_core_reverse_geography",args);}
 static void await(CountDownLatch latch)throws Exception {check(latch.await(3,TimeUnit.SECONDS),"worker progressed");}
 static void deliver()throws Exception {await(Geocoder.ended);for(int i=0;i<30;i++){Handler.flush();Thread.sleep(2);}Handler.flush();}
 static void placeMode(boolean consent){WeatherAppearancePreferences.setColorOsLocationRulesEnabled(activity,true);
  activity.getSharedPreferences("weather_refresh_state",0).edit().putBoolean("network_allowed",consent).commit();}
 static void deliverPlace()throws Exception {await(ColorOsPlaceResolver.ended);for(int i=0;i<30;i++){Handler.flush();Thread.sleep(2);}Handler.flush();}
 static void deliverFallback()throws Exception {deliverPlace();deliver();}
 static String field(Reply r,String name){return (String)new org.json.JSONObject((String)r.value).opt(name);}
 static String street(Reply r){return field(r,"mStreetName");}
 public static void main(String[] args)throws Exception {
  setup(31,false);check(!AndroidLocation.colorOsLocationRules(activity),"setting defaults off");
  Reply coordinates=begin("weather_location","location_amp_nlp",null);activity.manager.emit();
  check(coordinates.replies==1&&coordinates.value instanceof Map,"weather Map contract");
  check(((Map<?,?>)coordinates.value).size()==2,"lat/lng-only Dart consumes only coordinates");
  check(Geocoder.started.getCount()==1,"weather caller has no discarded geocoding");location.onDestroy();
  setup(31,false);Reply old=reverse();deliver();check(old.replies==1,"Android 12 reverse replies once");
  check("长江路".equals(street(old)),"default uses street");
  check("黄岛区".equals(field(old,"mCityName")),"district retained for original zh_cn override");
  check("青岛市".equals(field(old,"mLocality")),"locality stays a separate administrative field");location.onDestroy();
  setup(31,false);Address districtOnly=new Address();districtOnly.street="";districtOnly.premises="";
  districtOnly.feature="黄岛区";Geocoder.addresses=Arrays.asList(districtOnly);Reply district=reverse();deliver();
  check("黄岛区".equals(field(district,"mCityName")),"district-only result keeps district");
  check("".equals(street(district)),"district fallback is not appended to the same district");location.onDestroy();
  setup(31,false);districtOnly=new Address();districtOnly.street="";districtOnly.premises="";districtOnly.feature="黄岛区";
  Geocoder.addresses=Arrays.asList(districtOnly);WeatherAppearancePreferences.setColorOsLocationRulesEnabled(activity,true);
  Reply districtNearby=reverse();deliver();check("".equals(street(districtNearby)),"ColorOS skips administrative feature and fallback");location.onDestroy();
  setup(31,false);Address county=new Address();county.subLocality="";county.subAdmin="真实县";
  Geocoder.addresses=Arrays.asList(county);Reply countyResult=reverse();deliver();
  check("真实县".equals(field(countyResult,"mCityName")),"subadmin fallback preserves district level");location.onDestroy();
  setup(31,false);Address locality=new Address();locality.subLocality=null;locality.subAdmin="";
  Geocoder.addresses=Arrays.asList(locality);Reply localityResult=reverse();deliver();
  check("青岛市".equals(field(localityResult,"mCityName")),"locality used only when district unavailable");location.onDestroy();
  setup(31,false);WeatherAppearancePreferences.setColorOsLocationRulesEnabled(activity,true);
  Reply nearby=reverse();deliver();check("长江路".equals(street(nearby)),"without consent ColorOS uses ordinary Geocoder street");
  check(ColorOsPlaceResolver.calls==0,"ColorOS option cannot bypass network consent");location.onDestroy();
  setup(31,false);placeMode(true);Reply optedCoordinates=begin("weather_location","location_amp_nlp",null);activity.manager.emit();
  check(optedCoordinates.replies==1&&ColorOsPlaceResolver.calls==0&&Geocoder.started.getCount()==1,"coordinate-only caller never starts discarded landmark work");location.onDestroy();
  setup(31,false);activity.getSharedPreferences("weather_refresh_state",0).edit().putBoolean("network_allowed",true).commit();
  Reply off=reverse();deliver();check(ColorOsPlaceResolver.calls==0,"switch off makes no ColorOS request");location.onDestroy();
  setup(31,false);placeMode(true);Geocoder.present=false;
  ColorOsPlaceResolver.result=new ColorOsPlaceResolver.PlaceResult("黄岛区","青岛理工大学（黄岛校区）",0);
  Reply campus=reverse();deliverPlace();check(campus.replies==1&&"青岛理工大学（黄岛校区）".equals(street(campus)),"verified backend landmark populates street");
  check("黄岛区".equals(field(campus,"mCityName"))&&"青岛市".equals(field(campus,"mLocality")),"backend keeps district and locality separate");
  check(Geocoder.started.getCount()==1,"successful place does not invoke Android Geocoder");
  check(ColorOsPlaceResolver.latitude==36&&ColorOsPlaceResolver.longitude==120,"resolver receives real WGS84 coordinates without translation");
  check(ColorOsPlaceResolver.language.equals(Locale.getDefault().toLanguageTag()),"resolver receives actual locale");
  org.json.JSONObject campusJson=new org.json.JSONObject((String)campus.value);
  check(((Number)campusJson.opt("mLatitude")).doubleValue()==36&&((Number)campusJson.opt("mLongitude")).doubleValue()==120,"payload retains input fix instead of server-city centroid");location.onDestroy();
  setup(31,false);placeMode(true);ColorOsPlaceResolver.throwsError=true;Reply placeError=reverse();deliverFallback();
  check(placeError.replies==1&&"长江路".equals(street(placeError)),"backend error uses ordinary Geocoder fallback");location.onDestroy();
  setup(31,false);placeMode(true);ColorOsPlaceResolver.result=new ColorOsPlaceResolver.PlaceResult("黄岛区","",0);
  Reply noPoi=reverse();deliverFallback();check("长江路".equals(street(noPoi)),"no backend landmark falls back to street");location.onDestroy();
  setup(31,false);placeMode(true);ColorOsPlaceResolver.result=new ColorOsPlaceResolver.PlaceResult("黄岛区","科技中心",1);
  Reply disabledPoi=reverse();deliverFallback();check("长江路".equals(street(disabledPoi)),"backend disabled detail status cannot display POI");location.onDestroy();
  setup(31,false);placeMode(true);ColorOsPlaceResolver.result=new ColorOsPlaceResolver.PlaceResult("黄岛区","黄岛区",0);
  Reply duplicatePoi=reverse();deliverFallback();check("长江路".equals(street(duplicatePoi)),"administrative backend label cannot duplicate district");location.onDestroy();
  setup(31,false);placeMode(true);ColorOsPlaceResolver.result=new ColorOsPlaceResolver.PlaceResult("","真实地标",0);
  Reply missingDistrict=reverse();deliverFallback();check("黄岛区".equals(field(missingDistrict,"mCityName"))&&"长江路".equals(street(missingDistrict)),"incomplete backend city falls back without losing district");location.onDestroy();
  setup(31,false);placeMode(true);ColorOsPlaceResolver.reset(true);
  ColorOsPlaceResolver.result=new ColorOsPlaceResolver.PlaceResult("黄岛区","真实地标",0);
  Reply placeSlow=reverse();await(ColorOsPlaceResolver.started);Handler.advance(6500);deliver();
  check(placeSlow.replies==1&&"长江路".equals(street(placeSlow)),"backend deadline starts bounded Geocoder fallback");
  ColorOsPlaceResolver.gate.countDown();deliverPlace();check(placeSlow.replies==1&&"长江路".equals(street(placeSlow)),"late backend cannot replace fallback");location.onDestroy();
  setup(31,false);placeMode(true);ColorOsPlaceResolver.reset(true);Reply placePaused=reverse();await(ColorOsPlaceResolver.started);
  location.onPause();check(placePaused.replies==1&&"CANCELLED".equals(placePaused.code),"pause cancels backend once");
  ColorOsPlaceResolver.gate.countDown();deliverPlace();check(placePaused.replies==1&&Geocoder.started.getCount()==1,"cancelled backend does not start fallback");location.onDestroy();
  setup(31,false);placeMode(true);ColorOsPlaceResolver.reset(true);Reply placeDead=reverse();await(ColorOsPlaceResolver.started);
  location.onDestroy();ColorOsPlaceResolver.gate.countDown();deliverPlace();Handler.advance(6500);
  check(placeDead.replies==0&&Geocoder.started.getCount()==1,"destroy suppresses backend reply and fallback");
  setup(31,false);placeMode(true);ColorOsPlaceResolver.reset(true);
  Reply placeOccupied=reverse();await(ColorOsPlaceResolver.started);Reply placeQueued=reverse();Reply placeFull=reverse();deliver();
  check(placeFull.replies==1&&"长江路".equals(street(placeFull)),"saturated backend uses independent Geocoder worker");
  location.onDestroy();ColorOsPlaceResolver.gate.countDown();deliverPlace();Handler.advance(6500);
  check(placeOccupied.replies==0&&placeQueued.replies==0&&ColorOsPlaceResolver.calls==1,"destroy removes queued backend work");
  setup(31,false);placeMode(true);ColorOsPlaceResolver.reset(true);
  ColorOsPlaceResolver.result=new ColorOsPlaceResolver.PlaceResult("黄岛区","真实地标",0);
  Reply revokedConsent=reverse();await(ColorOsPlaceResolver.started);
  activity.getSharedPreferences("weather_refresh_state",0).edit().putBoolean("network_allowed",false).commit();
  ColorOsPlaceResolver.gate.countDown();deliverFallback();check("长江路".equals(street(revokedConsent)),"revoked network consent rejects late backend result");location.onDestroy();
  setup(31,false);placeMode(true);ColorOsPlaceResolver.reset(true);
  ColorOsPlaceResolver.result=new ColorOsPlaceResolver.PlaceResult("黄岛区","真实地标",0);
  Reply revokedOption=reverse();await(ColorOsPlaceResolver.started);WeatherAppearancePreferences.setColorOsLocationRulesEnabled(activity,false);
  ColorOsPlaceResolver.gate.countDown();deliverFallback();check("长江路".equals(street(revokedOption)),"disabled option rejects late backend result");location.onDestroy();
  setup(33,false);placeMode(true);ColorOsPlaceResolver.result=new ColorOsPlaceResolver.PlaceResult("黄岛区","真实地标",0);
  Reply newPlace=reverse();deliverPlace();check("真实地标".equals(street(newPlace))&&Geocoder.listener==null,"API33 uses same backend before async Geocoder");location.onDestroy();
  setup(33,false);placeMode(true);ColorOsPlaceResolver.throwsError=true;Reply newFallback=reverse();deliverPlace();
  check(newFallback.replies==0&&Geocoder.listener!=null,"API33 backend error starts asynchronous fallback");
  Geocoder.listener.onGeocode(Geocoder.addresses);Handler.flush();check("长江路".equals(street(newFallback)),"API33 fallback maps ordinary street");location.onDestroy();
  setup(31,false);placeMode(true);ColorOsPlaceResolver.reset(true);Geocoder.present=false;
  Reply noServices=reverse();await(ColorOsPlaceResolver.started);Handler.advance(6500);
  check(noServices.replies==1&&"".equals(street(noServices)),"backend timeout without Geocoder returns original coordinates");
  ColorOsPlaceResolver.gate.countDown();deliverPlace();check(noServices.replies==1,"late result after both services unavailable replies once");location.onDestroy();
  setup(31,false);Reply xms=begin("xms_location","locationFromNlp",true);activity.manager.emit();deliver();
  check(xms.replies==1&&xms.value instanceof String,"legacy XMS JSON string");
  check(activity.manager.listeners.isEmpty(),"fix removes providers");location.onDestroy();
  setup(31,true);Reply slow=reverse();await(Geocoder.started);Handler.advance(2500);
  check(slow.replies==1&&"".equals(street(slow)),"blocked old backend returns coordinates by deadline");
  Geocoder.gate.countDown();deliver();check(slow.replies==1,"late old geocode cannot reply twice");location.onDestroy();
  setup(31,true);Reply paused=reverse();await(Geocoder.started);location.onPause();
  check(paused.replies==1&&"CANCELLED".equals(paused.code),"pause cancels worker query");
  Geocoder.gate.countDown();deliver();check(paused.replies==1,"paused query ignores late result");location.onDestroy();
  setup(31,true);Reply dead=reverse();await(Geocoder.started);location.onDestroy();
  Geocoder.gate.countDown();deliver();Handler.advance(2500);check(dead.replies==0,"destroy never replies late");
  setup(31,true);Reply occupied=reverse();await(Geocoder.started);Reply queued=reverse();Reply saturated=reverse();
  check(saturated.replies==1&&"".equals(street(saturated)),"full bounded worker falls back immediately");
  location.onDestroy();Geocoder.gate.countDown();deliver();Handler.advance(2500);
  check(occupied.replies==0&&queued.replies==0,"destroy cancels running and queued work");
  setup(31,false);Geocoder.throwsError=true;Reply unavailable=reverse();deliver();
  check(unavailable.replies==1&&"".equals(street(unavailable)),"old geocoder error retains coordinates");location.onDestroy();
  setup(33,false);Reply async=reverse();Geocoder.GeocodeListener saved=Geocoder.listener;
  saved.onGeocode(Geocoder.addresses);Handler.flush();check(async.replies==1&&"长江路".equals(street(async)),"new async result");
  saved.onError("late");Handler.flush();check(async.replies==1,"new async replies once");location.onDestroy();
  setup(33,false);Reply asyncSlow=reverse();saved=Geocoder.listener;Handler.advance(2500);
  saved.onGeocode(Geocoder.addresses);Handler.flush();check(asyncSlow.replies==1,"new timeout ignores callback");location.onDestroy();
  setup(33,false);Reply asyncDead=reverse();saved=Geocoder.listener;location.onDestroy();
  saved.onGeocode(Geocoder.addresses);Handler.flush();check(asyncDead.replies==0,"new destroy ignores callback");
  setup(31,false);Geocoder.present=false;Reply absent=reverse();check(absent.replies==1,"missing service returns coordinates");location.onDestroy();
  System.out.println("PASS: "+checks+" location bridge checks; Android 12 worker, deadlines, cancellation, codec and API 33 callbacks");
 }}''',
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, default=os.environ.get('JAVA_HOME'))
    args = parser.parse_args()
    java_dir = args.java_home / 'bin' if args.java_home else None
    suffix = '.exe' if os.name == 'nt' else ''
    javac = str(java_dir / ('javac' + suffix)) if java_dir else shutil.which('javac')
    java = str(java_dir / ('java' + suffix)) if java_dir else shutil.which('java')
    if not javac or not java:
        parser.error('Set JAVA_HOME or pass --java-home')
    build = ROOT / '.build'
    build.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='location-test-', dir=build) as temp:
        work = Path(temp)
        for name, content in SOURCES.items():
            source = work / name
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_text(content, encoding='utf-8')
        classes = work / 'classes'
        classes.mkdir()
        sources = list(work.rglob('*.java')) + [ROOT / 'port/AndroidLocation.java',
            ROOT / 'port/AndroidLocationLabels.java', ROOT / 'port/WeatherAppearancePreferences.java',
            ROOT / 'tests/AndroidLocationLabelsCheck.java', ROOT / 'tests/AndroidLocationProviderCheck.java']
        subprocess.run([javac, '--release', '8', '-encoding', 'UTF-8', '-d', str(classes),
                        *map(str, sources)], check=True)
        for probe in ('AndroidLocationLabelsCheck', 'AndroidLocationBridgeCheck', 'AndroidLocationProviderCheck'):
            subprocess.run([java, '-cp', str(classes), 'dev.weather.oneplus.' + probe], check=True)


if __name__ == '__main__':
    main()
