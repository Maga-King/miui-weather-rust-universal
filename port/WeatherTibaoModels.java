package dev.weather.oneplus;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.CancellationSignal;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONObject;

/** Optional private GPT-SoVITS model data; the APK contains only its hash allowlist. */
public final class WeatherTibaoModels {
    public static final String MODEL_VERSION="tribbie-v2proplus-userclip-eos-13-19-20-v1";
    public static final String SOURCE_PAGE="https://www.modelscope.cn/models/aihobbyist/GPT-SoVITS_Model_Collection";
    public static final String VOICE_PREFIX="weather-local-tibao:";
    private static final String PREFS="weather_tibao_speech_v1";
    private static final long MANIFEST_LIMIT=65536L;
    private static volatile Manifest cachedManifest;
    private WeatherTibaoModels() {}
    public interface Progress { void update(long bytes,long total); }
    private static SharedPreferences prefs(Context context){return context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
    public static File root(Context context){return new File(context.getNoBackupFilesDir(),"weather_tibao_models");}
    public static File directory(Context context){return new File(root(context),MODEL_VERSION);}
    public static boolean enabled(Context context){return prefs(context).getBoolean("enabled",false);}
    public static void setEnabled(Context context,boolean value){
        if(value&&!installed(context))throw new IllegalStateException("Import the Tribbie model ZIP first");
        if(!prefs(context).edit().putBoolean("enabled",value).commit())throw new IllegalStateException("Could not save speech engine");
    }
    public static int preset(Context context){int value=prefs(context).getInt("preset",13);return validPreset(value)?value:13;}
    public static void setPreset(Context context,int value){
        if(!validPreset(value))throw new IllegalArgumentException("Unknown Tribbie preset");
        if(!prefs(context).edit().putInt("preset",value).commit())throw new IllegalStateException("Could not save speech preset");
    }
    private static boolean validPreset(int value){return value==13||value==19||value==20;}
    public static boolean downloadAvailable(){return false;}
    public static long expandedBytes(Context context){try{return manifest(context).expanded;}catch(Exception ignored){return 0;}}
    public static boolean installed(Context context){
        try{
            Manifest manifest=manifest(context);File dir=directory(context),stamp=new File(dir,".verified");
            if(!stamp.isFile()||stamp.length()!=64)return false;
            try(InputStream input=new FileInputStream(stamp)){
                byte[] bytes=new byte[64];readExactly(input,bytes);
                if(!manifest.sha256.equals(new String(bytes,"US-ASCII")))return false;
            }
            File[] files=dir.listFiles();if(files==null||files.length!=manifest.files.size()+1)return false;
            for(Spec spec:manifest.files.values()){
                File file=new File(dir,spec.name);
                if(!file.isFile()||file.length()!=spec.bytes)return false;
            }
            return true;
        }catch(Exception ignored){return false;}
    }
    /** There is no published Android bundle: the author ZIP is a different PyTorch format. */
    public static void download(Context context,CancellationSignal cancellation,Progress progress)throws IOException{
        throw new IOException("The author provides PyTorch weights. Import the locally prepared Tribbie Android ZIP.");
    }
    /** Accept only the exact local bundle whose individual files match the APK allowlist. */
    public static synchronized void install(Context context,InputStream source,CancellationSignal cancellation,Progress progress)throws Exception{
        Context app=context.getApplicationContext();if(app==null)app=context;
        Manifest manifest=manifest(app);File base=root(app);
        if(!base.isDirectory()&&!base.mkdirs())throw new IOException("Cannot create model storage");
        File archive=new File(base,"import-"+UUID.randomUUID()+".part");
        File staging=new File(base,"stage-"+UUID.randomUUID());
        File previous=null;boolean published=false;
        CancellationSignal signal=cancellation==null?new CancellationSignal():cancellation;
        // Closing a provider stream makes cancellation effective even during a blocked read.
        signal.setOnCancelListener(()->{try{source.close();}catch(IOException ignored){}});
        try{
            signal.throwIfCanceled();byte[] buffer=new byte[65536];long copied=0,last=0;
            try(FileOutputStream output=new FileOutputStream(archive)){
                int n;while((n=source.read(buffer))!=-1){
                    signal.throwIfCanceled();copied+=n;
                    if(copied>manifest.expanded+2L*1024*1024)throw new IOException("Model ZIP exceeds the import limit");
                    output.write(buffer,0,n);
                    if(copied-last>=1024*1024){last=copied;report(progress,copied,-1);}
                }
                output.getFD().sync();
            }
            report(progress,copied,-1);
            signal.throwIfCanceled();
            long required=manifest.expanded+16L*1024*1024;
            if(base.getUsableSpace()<required)throw new IOException("Not enough storage to extract the Tribbie model");
            if(!staging.mkdir())throw new IOException("Cannot create model staging directory");
            extract(archive,staging,manifest,signal,progress);
            signal.throwIfCanceled();
            // Preserve immutable files that an existing speech session can have mapped.
            if(installed(app))return;
            try(FileOutputStream stamp=new FileOutputStream(new File(staging,".verified"))){
                stamp.write(manifest.sha256.getBytes("US-ASCII"));stamp.getFD().sync();
            }
            signal.throwIfCanceled();
            File destination=directory(app);
            if(destination.exists()){
                previous=new File(base,"old-"+UUID.randomUUID());
                if(!destination.renameTo(previous))throw new IOException("Cannot preserve the installed model");
            }
            if(!staging.renameTo(destination)){
                if(previous!=null&&!previous.renameTo(destination))throw new IOException("Could not restore the installed model");
                throw new IOException("Cannot publish the verified model");
            }
            published=true;
        }finally{
            signal.setOnCancelListener(null);
            cleanup(archive,base);cleanup(staging,base);
            if(published&&previous!=null)cleanup(previous,base);
        }
    }
    private static void extract(File archive,File staging,Manifest manifest,CancellationSignal cancellation,Progress progress)throws Exception{
        Set<String> seen=new HashSet<>();long expanded=0;byte[] buffer=new byte[65536];
        try(ZipFile zip=new ZipFile(archive)){
            Enumeration<? extends ZipEntry> entries=zip.entries();
            while(entries.hasMoreElements()){
                cancellation.throwIfCanceled();ZipEntry entry=entries.nextElement();String name=entry.getName();
                Spec spec=manifest.files.get(name);
                if(spec==null||!seen.add(name)||entry.isDirectory()||entry.getSize()!=spec.bytes
                    ||(entry.getMethod()!=ZipEntry.STORED&&entry.getMethod()!=ZipEntry.DEFLATED))
                    throw new IOException("Unknown, duplicate or incomplete model ZIP entry");
                File target=new File(staging,name);
                if(!target.getCanonicalPath().startsWith(staging.getCanonicalPath()+File.separator))
                    throw new IOException("Model ZIP path escapes staging storage");
                MessageDigest hash=MessageDigest.getInstance("SHA-256");long bytes=0,last=0;
                try(InputStream input=zip.getInputStream(entry);FileOutputStream output=new FileOutputStream(target)){
                    int n;while((n=input.read(buffer))!=-1){
                        cancellation.throwIfCanceled();bytes+=n;
                        if(bytes>spec.bytes||expanded+bytes>manifest.expanded)throw new IOException("Expanded model limit exceeded");
                        hash.update(buffer,0,n);output.write(buffer,0,n);
                        if(bytes-last>=1024*1024){last=bytes;report(progress,expanded+bytes,manifest.expanded);}
                    }
                    output.getFD().sync();
                }
                if(bytes!=spec.bytes||!spec.sha256.equals(hex(hash.digest())))throw new IOException("Model checksum mismatch: "+name);
                expanded+=bytes;report(progress,expanded,manifest.expanded);
            }
        }
        if(seen.size()!=manifest.files.size()||expanded!=manifest.expanded)throw new IOException("Missing Tribbie model files");
    }
    private static Manifest manifest(Context context)throws Exception{
        Manifest current=cachedManifest;if(current!=null)return current;
        synchronized(WeatherTibaoModels.class){
            if(cachedManifest!=null)return cachedManifest;
            ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[4096];
            try(InputStream input=context.getAssets().open("tibao_models/manifest.json")){
                int n;while((n=input.read(buffer))!=-1){if(output.size()+n>MANIFEST_LIMIT)throw new IOException("Invalid model manifest");output.write(buffer,0,n);}
            }
            byte[] bytes=output.toByteArray();JSONObject value=new JSONObject(new String(bytes,"UTF-8"));
            if(value.getInt("schema_version")!=1||!MODEL_VERSION.equals(value.getString("model_version")))throw new IOException("Unsupported model manifest");
            LinkedHashMap<String,Spec> files=new LinkedHashMap<>();JSONArray rows=value.getJSONArray("files");long expanded=0;
            if(rows.length()==0||rows.length()>32)throw new IOException("Invalid model manifest file count");
            for(int i=0;i<rows.length();i++){
                JSONObject row=rows.getJSONObject(i);String name=row.getString("file"),sha=row.getString("sha256");long size=row.getLong("bytes");
                if(!name.matches("[a-zA-Z0-9_.-]+")||name.equals(".")||name.equals("..")||name.equals(".verified")
                    ||!sha.matches("[0-9a-f]{64}")||size<1||size>700L*1024*1024||files.containsKey(name))throw new IOException("Invalid model manifest entry");
                files.put(name,new Spec(name,size,sha));expanded+=size;
            }
            if(expanded!=value.getLong("expanded_bytes")||expanded>2L*1024*1024*1024)throw new IOException("Invalid model manifest size");
            cachedManifest=new Manifest(files,expanded,hex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            return cachedManifest;
        }
    }
    private static void readExactly(InputStream input,byte[] buffer)throws IOException{
        int position=0,n;while(position<buffer.length&&(n=input.read(buffer,position,buffer.length-position))!=-1)position+=n;
        if(position!=buffer.length)throw new EOFException("Incomplete verification stamp");
    }
    private static void report(Progress progress,long bytes,long total){if(progress!=null)progress.update(bytes,total);}
    private static String hex(byte[] bytes){char[] digits="0123456789abcdef".toCharArray(),out=new char[bytes.length*2];for(int i=0;i<bytes.length;i++){out[i*2]=digits[(bytes[i]&255)>>>4];out[i*2+1]=digits[bytes[i]&15];}return new String(out);}
    private static void cleanup(File target,File base){try{removeTree(target,base);}catch(IOException ignored){}}
    private static void removeTree(File target,File base)throws IOException{
        if(!target.exists())return;
        String path=target.getCanonicalPath(),prefix=base.getCanonicalPath()+File.separator;
        if(!path.startsWith(prefix))throw new IOException("Refusing cleanup outside model storage");
        File[] files=target.listFiles();if(files!=null)for(File file:files)removeTree(file,base);
        if(!target.delete())throw new IOException("Could not remove temporary model data");
    }
    private static final class Spec{final String name,sha256;final long bytes;Spec(String n,long b,String s){name=n;bytes=b;sha256=s;}}
    private static final class Manifest{final Map<String,Spec> files;final long expanded;final String sha256;Manifest(Map<String,Spec> f,long e,String s){files=f;expanded=e;sha256=s;}}
}
