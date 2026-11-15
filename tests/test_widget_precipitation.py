"""Execute production widget renderers with recording Android drawing fakes.

The weather/category/count oracle is parsed from the extracted original MAML,
not copied from the Java implementation. This checks render commands and asset
decoding, not Android GPU pixels. No phone is accessed.
"""
from pathlib import Path
import argparse
import ast
import hashlib
import json
import os
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SOURCES = {
    'android/content/Context.java': '''package android.content;
public class Context {
 public android.content.res.AssetManager getAssets(){return new android.content.res.AssetManager();}
 public android.content.res.Resources getResources(){return new android.content.res.Resources();}
 public String getPackageName(){return "fixture";}
 public String getString(int id,Object... args){return "fixture";}
}''',
    'android/content/res/AssetManager.java': '''package android.content.res;
public class AssetManager {
 public static java.nio.file.Path overlay, baseline;
 public static class Named extends java.io.ByteArrayInputStream {
  public final String name; Named(String n,byte[] b){super(b);name=n;}
 }
 public java.io.InputStream open(String name)throws java.io.IOException {
  java.nio.file.Path p=overlay.resolve(name);if(!java.nio.file.Files.exists(p))p=baseline.resolve(name);
  return new Named(name,java.nio.file.Files.readAllBytes(p));
 }
}''',
    'android/content/res/Resources.java': '''package android.content.res;
public class Resources {
 public Configuration getConfiguration(){return new Configuration();}
 public int getIdentifier(String a,String b,String c){return 1;}
}''',
    'android/content/res/Configuration.java': '''package android.content.res;
public class Configuration {public static int UI_MODE_NIGHT_MASK=48,UI_MODE_NIGHT_YES=32;public int uiMode;}''',
    'android/util/Log.java': '''package android.util;
public class Log {public static int w(String t,String m,Throwable e){throw new AssertionError(m,e);}public static int w(String t,String m){throw new AssertionError(m);}}''',
    'android/util/LruCache.java': '''package android.util;
public class LruCache<K,V> extends java.util.HashMap<K,V>{public LruCache(int max){}}''',
    'android/graphics/Typeface.java': '''package android.graphics; public class Typeface {}''',
    'dev/weather/oneplus/WeatherWidgetFonts.java': '''package dev.weather.oneplus;
final class WeatherWidgetFonts {static android.graphics.Typeface face(android.content.Context c,int a,int b){return new android.graphics.Typeface();}}''',
    'android/graphics/Shader.java': '''package android.graphics;
public class Shader {public enum TileMode {CLAMP}}''',
    'android/graphics/LinearGradient.java': '''package android.graphics;
public class LinearGradient extends Shader {public LinearGradient(float a,float b,float c,float d,int[] e,float[] f,TileMode g){}}''',
    'android/graphics/Color.java': '''package android.graphics;
public class Color {public static int WHITE=-1;public static int parseColor(String s){return (int)(Long.parseLong(s.substring(1),16)|0xff000000L);}}''',
    'android/graphics/Paint.java': '''package android.graphics;
public class Paint {
 public static final int ANTI_ALIAS_FLAG=1,FILTER_BITMAP_FLAG=2;
 public enum Align{LEFT,CENTER,RIGHT} public enum Cap{BUTT,ROUND} public enum Style{FILL,STROKE}
 public int alpha=255; public float size; public Paint(int flags){}
 public void setAlpha(int a){alpha=a;} public void setColor(int c){} public void setShader(Shader s){}
 public void setTypeface(Typeface t){} public void setTextSize(float s){size=s;} public void setTextAlign(Align a){}
 public void setStrokeWidth(float w){} public void setStrokeCap(Cap c){} public void setStyle(Style s){}
 public float measureText(String s){return s.length()*size*.5f;} public float ascent(){return -.8f*size;} public float descent(){return .2f*size;}
}''',
    'android/text/TextPaint.java': '''package android.text;
public class TextPaint extends android.graphics.Paint {public TextPaint(int flags){super(flags);}}''',
    'android/text/TextUtils.java': '''package android.text;
public class TextUtils {public enum TruncateAt{END}public static CharSequence ellipsize(String s,TextPaint p,float w,TruncateAt t){return s;}}''',
    'android/graphics/RectF.java': '''package android.graphics;
public class RectF {public final float left,top,right,bottom;public RectF(float l,float t,float r,float b){left=l;top=t;right=r;bottom=b;}}''',
    'android/graphics/Path.java': '''package android.graphics;
public class Path {public enum Direction{CW}public void addRoundRect(RectF r,float a,float b,Direction d){}public void moveTo(float a,float b){}public void lineTo(float a,float b){}public void close(){}}''',
    'android/graphics/Bitmap.java': '''package android.graphics;
public class Bitmap {
 public enum Config{ARGB_8888} public String name=""; public int width,height; public boolean recycled;
 public java.util.List<String> commands=new java.util.ArrayList<>();
 public static Bitmap createBitmap(int w,int h,Config c){Bitmap b=new Bitmap();b.width=w;b.height=h;return b;}
 public void recycle(){recycled=true;}public int getWidth(){return width;}public int getHeight(){return height;}
}''',
    'android/graphics/BitmapFactory.java': '''package android.graphics;
public class BitmapFactory {
 public static Bitmap decodeStream(java.io.InputStream in)throws java.io.IOException {
  java.awt.image.BufferedImage i=javax.imageio.ImageIO.read(in);if(i==null)throw new java.io.IOException("Cannot decode fixture");
  Bitmap b=Bitmap.createBitmap(i.getWidth(),i.getHeight(),Bitmap.Config.ARGB_8888);
  b.name=((android.content.res.AssetManager.Named)in).name;return b;
 }
}''',
    'android/graphics/Canvas.java': '''package android.graphics;
public class Canvas {
 private final Bitmap bitmap;private int depth=0; public Canvas(Bitmap b){bitmap=b;}
 public int save(){bitmap.commands.add("save");return depth++;}
 public void restore(){depth--;bitmap.commands.add("restore");}
 public void restoreToCount(int n){depth=n;bitmap.commands.add("restore");}
 public void clipRect(float a,float b,float c,float d){bitmap.commands.add("clipRect");}
 public void clipPath(Path p){bitmap.commands.add("clipPath");}public void rotate(float a,float b,float c){}
 public void scale(float a,float b){}public void translate(float a,float b){}
 public void drawBitmap(Bitmap b,Object source,RectF r,Paint p){
  if(b.recycled)throw new AssertionError("recycled bitmap");
  if(!(r.right>r.left&&r.bottom>r.top))throw new AssertionError("nonpositive dimensions");
  bitmap.commands.add("image:"+b.name+":"+p.alpha+":"+r.left+":"+r.top+":"+(r.right-r.left)+":"+(r.bottom-r.top));
 }
 public void drawRect(float a,float b,float c,float d,Paint p){bitmap.commands.add("rect");}
 public void drawText(String s,float a,float b,Paint p){bitmap.commands.add("text:"+s+":"+p.alpha);}
 public void drawLine(float a,float b,float c,float d,Paint p){}public void drawColor(int c){}
 public void drawCircle(float a,float b,float c,Paint p){}public void drawPath(Path a,Paint p){}
}''',
}

PROBE = r'''package dev.weather.oneplus;
import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.*;
import org.json.*;
import java.nio.file.*;
import java.util.*;
public class PrecipitationCheck {
 static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static WeatherWidgetData data(int type,boolean minuteRain)throws Exception {
  JSONObject d=new JSONObject().put("cityId","fixture").put("cityName","City")
   .put("temperature","16").put("unit","C").put("weatherType",type)
   .put("description",XiaomiWeatherParser.description(type,"zh_CN"))
   .put("sunriseMinute",360).put("sunsetMinute",1080).put("zoneId","GMT+08:00");
  if(minuteRain){JSONArray v=new JSONArray();for(int i=0;i<20;i++)v.put(.2);
   d.put("rainfall",v).put("rainStartMs",1000).put("rainDescription","Light rain forecast");}
  return new WeatherWidgetData(d);
 }
 public static void main(String[] args)throws Exception {
  AssetManager.overlay=Paths.get(args[0]);AssetManager.baseline=Paths.get(args[1]);
  JSONObject oracle=new JSONObject(Files.readString(Paths.get(args[2])));
  JSONObject mapping=oracle.getJSONObject("mapping");
  for(String code:mapping.keySet())check(XiaomiWeatherParser.weatherType(Integer.parseInt(code))==mapping.getInt(code),"V7/V6 "+code);
  JSONArray art=oracle.getJSONArray("art");
  for(int i=0;i<art.length();i++) {JSONObject item=art.getJSONObject(i);int type=item.getInt("type");
   check((item.getString("kind").equals("icon")?WeatherWidgetRenderer.icon(type):WeatherWidgetRenderer.backgroundType(type))==item.getInt("value"),"original icon/background mapping");
  }
  JSONObject raw=new JSONObject().put("current",new JSONObject().put("weather",7)
   .put("pubTime","2026-10-26T12:00:00+08:00")
   .put("temperature",new JSONObject().put("value","16").put("unit","C")));
  WeatherWidgetData rain=XiaomiWeatherParser.parse(raw,"fixture","City","C","zh_CN");
  check(rain.weatherType==11&&rain.description.equals("小雨"),"provider light rain");
  check(WeatherWidgetRenderer.icon(rain.weatherType)==11,"light rain icon");
  check(!rain.hasRain(1000),"current rain does not require minute forecast");
  WeatherWidgetRenderer renderer=new WeatherWidgetRenderer(new Context());
  WeatherCardWidgetRenderer cards=new WeatherCardWidgetRenderer(new Context());
  JSONArray cases=oracle.getJSONArray("cases");
  for(int k=0;k<cases.length();k++) {
   JSONObject c=cases.getJSONObject(k);int style=c.getInt("style"),type=c.getInt("type");
   for(boolean snow:new boolean[]{false,true})
    check(WeatherWidgetRenderer.precipitationCount(style,type,snow)==c.getInt(snow?"snow":"rain"),"MAML count "+style+"/"+type+"/"+snow);
   for(boolean minuteRain:new boolean[]{false,true}) {
    WeatherWidgetData d=data(type,minuteRain);String saved=d.toJson().toString();
    Bitmap a=Bitmap.createBitmap(300,300,Bitmap.Config.ARGB_8888);
    renderer.drawPrecipitation(new Canvas(a),d,style,150,150);
    List<String> images=new ArrayList<>();for(String s:a.commands)if(s.startsWith("image:"))images.add(s);
    int expected=c.getInt("rain")+c.getInt("snow");
    check(expected==0?images.isEmpty():!images.isEmpty(),"weather layer enabled "+style+"/"+type);
    if(c.getInt("rain")>0)check(images.size()==expected,"all positive-size rain drops");
    if(!images.isEmpty()) {
     check(a.commands.indexOf("clipRect")<a.commands.indexOf(images.get(0)),"clip before particles");
     check(a.commands.get(a.commands.size()-1).equals("restore"),"restore canvas");
     for(String s:images) {String[] p=s.split(":");boolean isSnow=p[1].endsWith("snow.png");
      int alpha=Integer.parseInt(p[2]);check(isSnow?alpha>=77&&alpha<=204:alpha>=102&&alpha<=166,"MAML opacity");
      check(Float.parseFloat(p[3])>=0&&Float.parseFloat(p[3])<150&&Float.parseFloat(p[4])>=0&&Float.parseFloat(p[4])<150,"positions within sky");
      check(isSnow?Math.abs(Float.parseFloat(p[5])-Float.parseFloat(p[6]))<.0001:Math.abs(Float.parseFloat(p[6])/Float.parseFloat(p[5])-12)<.03,"texture shape");
     }
    }
    Bitmap b=Bitmap.createBitmap(300,300,Bitmap.Config.ARGB_8888);
    renderer.drawPrecipitation(new Canvas(b),d,style,150,150);
    check(a.commands.equals(b.commands),"repeat refresh stability");
    check(saved.equals(d.toJson().toString()),"observations unchanged");
   }
  }
  // Execute full production renderers, checking layer placement and shared paths.
  for(int style=0;style<=6;style++)for(int type:new int[]{0,2,7,11,17,22,25})for(long time:new long[]{1000,43201000}) {
   WeatherWidgetData d=data(type,false);Bitmap image=style==0?renderer.render(d,time,300,300):cards.render(style,d,new JSONObject(),time,600,300);
   int firstText=-1,lastParticle=-1;for(int n=0;n<image.commands.size();n++) {
    String s=image.commands.get(n);if(s.startsWith("text:")&&firstText<0)firstText=n;
    if(s.contains("weather_widget/texture/"))lastParticle=n;
   }
   int expected=WeatherWidgetRenderer.precipitationCount(style,type,false)+WeatherWidgetRenderer.precipitationCount(style,type,true);
   check((lastParticle>=0)==(expected>0),"full renderer layer "+style+"/"+type);
   if(lastParticle>=0)check(firstText>lastParticle,"weather behind content");
   for(String s:image.commands)if(s.contains("weather_11/"))check(s.contains(":255:"),"particle alpha must not leak into icon");
  }
  Bitmap missing=Bitmap.createBitmap(300,300,Bitmap.Config.ARGB_8888);renderer.drawPrecipitation(new Canvas(missing),null,0,150,150);
  check(missing.commands.isEmpty(),"missing data has no weather");
  System.out.println("PASS: "+checks+" original mapping/MAML conditions, recording-render, clipping, order, alpha and stable-frame checks");
 }
}'''


def oracle(templates, weather_type):
    ids = {0:'b8006e83',1:'93d4f1b8',2:'bc0f0cd2',3:'5a4e9c9a',4:'f34b4e78',5:'fd6815cf',6:'c989887f',7:'620f3044'}
    cases=[];art=[]
    for style, identity in ids.items():
        matches=[f for f in templates.rglob('*-manifest.xml') if identity in str(f) and '40176' not in str(f)]
        assert matches,identity
        tree=ET.parse(min(matches,key=lambda p:len(str(p)))).getroot()
        for kind,prefix in [('icon','weather_id_4ico_'),('background','weather_id_4bg_')]:
            node=next((v for v in tree.iter('VariableCommand') if v.get('name','').startswith(prefix)
                       and v.get('expression','').startswith('ifelse(#weather_id_')),None)
            if node is not None:
                expression=re.sub(r'#weather_id_\w+','code',node.get('expression'))
                expression=expression.replace('}=','>=').replace('{=','<=').replace('}','>').replace('{','<').replace('**',' and ').replace('||',' or ')
                parsed=ast.parse(expression,mode='eval')
                allowed=(ast.Expression,ast.Call,ast.Name,ast.Load,ast.Constant,ast.Compare,ast.BoolOp,ast.And,ast.Or,ast.Eq,ast.GtE,ast.LtE,ast.Gt,ast.Lt)
                assert all(isinstance(n,allowed) for n in ast.walk(parsed)),expression
                def ifelse(*values):
                    for i in range(0,len(values)-1,2):
                        if values[i]:return values[i+1]
                    return values[-1]
                compiled=compile(parsed,'original-maml','eval')
                for code in range(26):art.append({'kind':kind,'type':code,'value':eval(compiled,{'__builtins__':{},'ifelse':ifelse,'code':code})})
        parents={c:n for n in tree.iter() for c in n}
        sets={}
        for kind in ('rain','snow'):
            groups=[]
            for size in ('small','middle','heavy'):
                var=next((v for v in tree.iter('Var') if v.get('name','').startswith(f'show_{size}_{kind}_')),None)
                groups.append(set(map(int,re.findall(r'==([0-9]+)',var.get('expression')))) if var is not None else set())
            image=next((v for v in tree.iter('Image') if '/texture/'+kind+'.' in v.get('src','')),None)
            counts=[0,0,0]
            allowed=set(range(26))
            if image is not None:
                thresholds=list(map(int,re.findall(r'#i\{([0-9]+)',image.get('visibility',''))))
                assert len(thresholds)==2
                counts=thresholds+[int(parents[image].get('count'))]
                ancestor=image
                while ancestor in parents:
                    ancestor=parents[ancestor]
                    if ancestor.get('name')=='lowerElement' and ancestor.get('visibility'):
                        gate=ancestor.get('visibility')
                        lo=re.search(r'}=([0-9]+)',gate);hi=re.search(r'\{=([0-9]+)',gate)
                        assert lo and hi,gate
                        allowed=set(range(int(lo[1]),int(hi[1])+1))
            sets[kind]={code:counts[level] for level,group in enumerate(groups) for code in group&allowed}
        for code in range(-1,27):cases.append({'style':style,'type':code,**{k:v.get(code,0) for k,v in sets.items()}})
    source=weather_type.read_text(encoding='utf8')
    block=source[source.index('V7V6_MAP ='):source.index('sparseIntArray2.put')]
    mapping={int(a):int(b) for a,b in re.findall(r'sparseIntArray.put\((\d+), (\d+)\)',block)}
    assert mapping.pop(99)==99 and 'UNUSED_NEW_WEATHER_TYPE.RAIN, 11' in block
    mapping[301]=11
    return {'cases':cases,'mapping':mapping,'art':art}


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--templates',type=Path,required=True)
    ap.add_argument('--weather-type-source',type=Path,required=True)
    ap.add_argument('--baseline-assets',type=Path,required=True)
    ap.add_argument('--json-jar',type=Path,required=True)
    ap.add_argument('--java-home',type=Path,required=True)
    args=ap.parse_args()
    inventory=json.loads((ROOT/'port/assets/weather_widget/precipitation_sources.json').read_text())
    for item in inventory['resources']:
        assert hashlib.sha256((ROOT/item['destination']).read_bytes()).hexdigest()==item['sha256']
    with tempfile.TemporaryDirectory(prefix='widget-precipitation-',dir=ROOT/'.build') as directory:
        work=Path(directory);sources=[]
        for name,body in {**SOURCES,'dev/weather/oneplus/PrecipitationCheck.java':PROBE}.items():
            file=work/name;file.parent.mkdir(parents=True,exist_ok=True);file.write_text(body,encoding='utf8');sources.append(file)
        for name in ('WeatherWidgetRenderer','WeatherCardWidgetRenderer','WeatherWidgetData','WeatherWidgetDayNight','XiaomiWeatherParser','WeatherWidgetGeometry'):
            sources.append(ROOT/'port'/f'{name}.java')
        (work/'oracle.json').write_text(json.dumps(oracle(args.templates,args.weather_type_source)),encoding='utf8')
        java=args.java_home/'bin'
        subprocess.run([str(java/'javac.exe'),'-encoding','UTF-8','-cp',str(args.json_jar),'-d',str(work/'classes'),*map(str,sources)],check=True)
        subprocess.run([str(java/'java.exe'),'-Djava.awt.headless=true','-cp',os.pathsep.join(map(str,[work/'classes',args.json_jar])),
                        'dev.weather.oneplus.PrecipitationCheck',str(ROOT/'port/assets'),str(args.baseline_assets),str(work/'oracle.json')],check=True)

if __name__=='__main__':main()
