"""Exercise the production Java resolver with verified public cert and fake HTTPS, no device/network."""
from pathlib import Path
import argparse
import hashlib
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JSON_SHA256 = "3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed"
SOURCES = {
    "android/content/Context.java": '''package android.content;
public class Context {public static final int MODE_PRIVATE=0;public boolean allowed=true;
 public String getPackageName(){return "dev.weather.oneplus";}
 public android.content.pm.PackageManager getPackageManager(){return new android.content.pm.PackageManager();}
 public SharedPreferences getSharedPreferences(String n,int mode){return (k,d)->allowed;}}''',
    "android/content/SharedPreferences.java": '''package android.content;
public interface SharedPreferences {boolean getBoolean(String key,boolean fallback);}''',
    "android/content/pm/PackageManager.java": '''package android.content.pm;
public class PackageManager {public static class PackageInfo {public int versionCode=1;}
 public PackageInfo getPackageInfo(String p,int f){return new PackageInfo();}}''',
    "android/os/SystemClock.java": '''package android.os;public class SystemClock {
 public static long elapsedRealtime(){return System.nanoTime()/1000000;}}''',
    "android/os/Build.java": '''package android.os;public class Build {
 public static final String BRAND="generic",MODEL="test";
 public static class VERSION {public static final String RELEASE="15";}}''',
    "dev/weather/oneplus/WeatherAppearancePreferences.java": '''package dev.weather.oneplus;
public class WeatherAppearancePreferences {public static boolean enabled=true;
 public static boolean colorOsLocationRulesEnabled(android.content.Context c){return enabled;}}''',
    "dev/weather/oneplus/ColorOsPlaceResolverCheck.java": r'''package dev.weather.oneplus;
import java.net.*;import java.io.*;import java.nio.charset.StandardCharsets;import java.nio.file.*;
import java.util.*;import javax.net.ssl.*;import java.security.cert.*;import java.lang.reflect.*;
import javax.crypto.*;import javax.crypto.spec.*;
public class ColorOsPlaceResolverCheck {
 static int checks,queries,certs,status=200;static String certificate,reply;static boolean block,disconnected;
 static Connection last;static final android.content.Context context=new android.content.Context();
 static final String VALID="{\"locationKey\":\"key\",\"cityName\":\"东城区\",\"tertiaryName\":\"北京市\",\"placeName\":\"天安门\",\"placeId\":\"2448416542656250862\",\"placeType\":6,\"placeDetailStatus\":0}";
 static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 static byte[] fromHex(String hex){byte[] a=new byte[hex.length()/2];for(int i=0;i<a.length;i++)a[i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);return a;}
 static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format("%02x",b));return s.toString();}
 static void reset()throws Exception {Field f=ColorOsPlaceResolver.class.getDeclaredField("CACHE");f.setAccessible(true);((Map<?,?>)f.get(null)).clear();reply=VALID;status=200;}
 static class Connection extends HttpsURLConnection {
  Map<String,String> headers=new HashMap<>();ByteArrayOutputStream body=new ByteArrayOutputStream();
  Connection(URL u){super(u);last=this;}
  public void setRequestProperty(String k,String v){headers.put(k,v);}
  public void disconnect(){disconnected=true;}public boolean usingProxy(){return false;}public void connect(){}
  public OutputStream getOutputStream()throws IOException{if(block){while(!disconnected){try{Thread.sleep(1);}catch(InterruptedException e){throw new IOException(e);}}throw new IOException("closed");}return body;}
  public int getResponseCode(){if(url.getPath().equals("/crypto/cert/upgrade")){certs++;return 200;}queries++;return status;}
  public InputStream getInputStream(){return new ByteArrayInputStream((url.getPath().equals("/crypto/cert/upgrade")?certificate:reply).getBytes(StandardCharsets.UTF_8));}
  public String getCipherSuite(){return "test";}public java.security.cert.Certificate[] getLocalCertificates(){return null;}
  public java.security.cert.Certificate[] getServerCertificates(){return null;}
 }
 public static void main(String[] args)throws Exception {
  certificate=new String(Files.readAllBytes(Paths.get(args[0])),StandardCharsets.UTF_8);reply=VALID;
  URL.setURLStreamHandlerFactory(protocol->"https".equals(protocol)?new URLStreamHandler(){protected URLConnection openConnection(URL url){return new Connection(url);}}:null);
  byte[] secret=fromHex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),salt=fromHex("000102030405060708090a0b0c"),info=fromHex("f0f1f2f3f4f5f6f7f8f9");
  check(hex(ColorOsPlaceResolver.hkdf(secret,salt,info)).equals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf"),"RFC5869 first 32 bytes");
  check(hex(ColorOsPlaceResolver.encryptCtr(fromHex("6bc1bee22e409f96e93d7e117393172a"),fromHex("2b7e151628aed2a6abf7158809cf4f3c"),fromHex("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff"))).equals("874d6191b620e3261bef6864990db6ce"),"NIST AES-CTR vector");
  check(ColorOsPlaceResolver.authCode("1792929950","dev.weather.oneplus").equals(args[1]),"authCode golden vector");
  check("116.3913".equals(ColorOsPlaceResolver.formatCoordinate(116.39126)),"source four-place rounding");
  check(ColorOsPlaceResolver.canReuse(39.9,116.3,39.9,116.3,100,101),"same fix cache accepted");
  check(!ColorOsPlaceResolver.canReuse(39.9001,116.3,39.9,116.3,100,101),"11m movement invalidates cache");
  check(!ColorOsPlaceResolver.canReuse(39.9,116.3,39.9,116.3,101,101),"expired cache rejected");
  ColorOsPlaceResolver.PlaceResult result=ColorOsPlaceResolver.resolve(context,39.9072,116.3913,"zh-CN");
  check(result.hasPlace()&&"天安门".equals(result.placeName)&&"东城区".equals(result.cityName),"exact backend labels");
  check("北京市".equals(result.locality)&&result.adminArea.isEmpty(),"no false province from continent");
  check(certs==1&&queries==1,"certificate plus query once");
  check("dev.weather.oneplus".equals(last.headers.get("pkgName"))&&"1".equals(last.headers.get("versionCode")),"actual package and app version");
  check("17006020".equals(last.headers.get("serviceVersionCode")),"SDK protocol version");
  check(last.getConnectTimeout()<=2000&&last.getReadTimeout()<=2000&&!last.getInstanceFollowRedirects(),"bounded HTTPS without redirects");
  String sent=new String(last.body.toByteArray(),StandardCharsets.UTF_8);
  check(!sent.contains("116.3913")&&!sent.contains("39.9072"),"coordinates encrypted");
  check(sent.contains("\"source\":\"Google\"")&&sent.contains("\"placeDetailLevel\":\"1\""),"official WGS source and detail level");
  ColorOsPlaceResolver.resolve(context,39.9072001,116.3913001,"zh-CN");check(queries==1,"near identical fix cached");
  ColorOsPlaceResolver.resolve(context,39.9073,116.3913,"zh-CN");check(queries==2&&certs==1,"movement re-resolves with cached certificate");
  context.allowed=false;try{ColorOsPlaceResolver.resolve(context,39.9072,116.3913,"zh-CN");throw new AssertionError("consent");}catch(IOException good){}
  check(queries==2,"consent denial prevents even cached lookup");context.allowed=true;
  WeatherAppearancePreferences.enabled=false;try{ColorOsPlaceResolver.resolve(context,39.9,116.3,"zh-CN");throw new AssertionError("toggle");}catch(IOException good){}
  check(queries==2,"off switch never networks");WeatherAppearancePreferences.enabled=true;
  Thread.currentThread().interrupt();try{ColorOsPlaceResolver.resolve(context,39.9,116.3,"zh-CN");throw new AssertionError("interrupt");}catch(IOException good){}finally{Thread.interrupted();}
  check(queries==2,"cancelled work never networks");
  reset();reply=VALID.replace("\"placeDetailStatus\":0","\"placeDetailStatus\":2");check(!ColorOsPlaceResolver.resolve(context,39.9,116.3,"zh-CN").hasPlace(),"detail failure is not a POI");
  reset();reply=VALID.substring(0,VALID.length()-1)+",\"isValid\":false}";check(!ColorOsPlaceResolver.resolve(context,39.9,116.3,"zh-CN").hasPlace(),"explicit invalid city rejected");
  reset();reply=VALID.replace("\"placeId\":\"2448416542656250862\",","");check(!ColorOsPlaceResolver.resolve(context,39.9,116.3,"zh-CN").hasPlace(),"missing identity rejected");
  reset();status=412;try{ColorOsPlaceResolver.resolve(context,39.9,116.3,"zh-CN");throw new AssertionError("http error");}catch(IOException good){}
  int before=queries;try{ColorOsPlaceResolver.resolve(context,39.9,116.3,"zh-CN");throw new AssertionError("cache error");}catch(IOException good){}
  check(queries==before,"negative cache avoids repeated failures");
  reset();reply="{";try{ColorOsPlaceResolver.resolve(context,39.9,116.3,"zh-CN");throw new AssertionError("json");}catch(IOException good){}checks++;
  reset();reply=new String(new char[65537]).replace('\0','x');try{ColorOsPlaceResolver.resolve(context,39.9,116.3,"zh-CN");throw new AssertionError("size");}catch(IOException good){}checks++;
  reset();Method post=ColorOsPlaceResolver.class.getDeclaredMethod("post",android.content.Context.class,long.class,String.class,Map.class,String.class);post.setAccessible(true);
  block=true;disconnected=false;long start=System.nanoTime();try{post.invoke(null,context,android.os.SystemClock.elapsedRealtime()+60,"https://test/blocked",Collections.emptyMap(),"{}");throw new AssertionError("deadline");}catch(InvocationTargetException expected){check(expected.getCause() instanceof IOException,"deadline closes blocked connection");}finally{block=false;}
  check(disconnected&&(System.nanoTime()-start)/1000000<1500,"whole request deadline interrupts blocked write");
  Field cert=ColorOsPlaceResolver.class.getDeclaredField("certificate");cert.setAccessible(true);cert.set(null,null);
  org.json.JSONObject changed=new org.json.JSONObject(certificate);String pem=changed.getJSONObject("data").getString("cert4Encrypt");
  byte[] der=Base64.getMimeDecoder().decode(pem.replace("-----BEGIN CERTIFICATE-----","").replace("-----END CERTIFICATE-----",""));der[der.length-1]^=1;
  changed.getJSONObject("data").put("cert4Encrypt","-----BEGIN CERTIFICATE-----\n"+Base64.getEncoder().encodeToString(der)+"\n-----END CERTIFICATE-----\n");certificate=changed.toString();
  try{ColorOsPlaceResolver.resolve(context,39.9,116.3,"zh-CN");throw new AssertionError("signature");}catch(java.security.GeneralSecurityException good){}checks++;
  System.out.println("PASS: "+checks+" production place resolver checks; public certificate, crypto vectors, identity, cache, consent, cancellation and deadline");
 }
}''',
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, default=Path("C:/Program Files/Java/jdk-17"))
    parser.add_argument("--network", action="store_true", help="Also probe production Java HTTPS with the fixed public Tiananmen coordinate")
    args = parser.parse_args()
    jar = ROOT / ".build/dependencies/json-20240303.jar"
    if not jar.exists() or hashlib.sha256(jar.read_bytes()).hexdigest() != JSON_SHA256:
        parser.error("Download pinned org.json 20240303 into .build/dependencies/json-20240303.jar")
    executable = args.java_home / "bin"
    with tempfile.TemporaryDirectory(prefix="coloros-resolver-test-", dir=ROOT / ".build") as temporary:
        work = Path(temporary)
        for name, content in SOURCES.items():
            target = work / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8")
        if args.network:
            target = work / "dev/weather/oneplus/ColorOsPlaceResolverLive.java"
            target.write_text('''package dev.weather.oneplus; public class ColorOsPlaceResolverLive {
 public static void main(String[] args)throws Exception {
 ColorOsPlaceResolver.PlaceResult p=ColorOsPlaceResolver.resolve(new android.content.Context(),39.9072,116.3913,"zh-CN");
 if(!p.hasPlace()||!"天安门".equals(p.placeName)||!"东城区".equals(p.cityName))throw new AssertionError("Public place response mismatch");
 System.out.println("PASS: production Java public Tiananmen probe, actual package/version, verified TLS and business certificate");}}
''', encoding="utf-8")
        classes = work / "classes"
        classes.mkdir()
        subprocess.run([str(executable / "javac.exe"), "--release", "8", "-encoding", "UTF-8", "-classpath", str(jar), "-d", str(classes),
                        *map(str, work.rglob("*.java")), str(ROOT / "port/ColorOsPlaceResolver.java")], check=True)
        timestamp, package = "1792929950", "dev.weather.oneplus"
        auth = hashlib.sha256((timestamp + "app-weather" + "/weather/location/for-service/sdk" + hashlib.sha256(package.encode()).hexdigest()).encode()).hexdigest()
        subprocess.run([str(executable / "java.exe"), "-cp", str(classes) + ";" + str(jar), "dev.weather.oneplus.ColorOsPlaceResolverCheck",
                        str(ROOT / "tests/coloros_place_public_certificate.json"), auth], check=True)
        if args.network:
            # Fresh process: default platform HTTPS, without the offline URL fake or TLS overrides.
            subprocess.run([str(executable / "java.exe"), "-cp", str(classes) + ";" + str(jar),
                            "dev.weather.oneplus.ColorOsPlaceResolverLive"], check=True, timeout=12)


if __name__ == "__main__":
    main()
