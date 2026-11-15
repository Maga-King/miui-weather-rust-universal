package dev.weather.oneplus;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.CancellationSignal;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import java.io.*;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import javax.net.ssl.HttpsURLConnection;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

/** Optional private model data. The APK contains the engine, never these large weights. */
public final class WeatherNeuralSpeechModels {
    public static final String KOKORO="kokoro-int8-multi-lang-v1_1";
    public static final String SHA256="259c9c8bc906d6078e4e0570421885dabb68b539c2f970fcbb890a58eeaf3e9c";
    public static final long DOWNLOAD_BYTES=147052523L;
    private static final String URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"+KOKORO+".tar.bz2";
    private static final long MAX_EXPANDED=350L*1024*1024;
    private static final String PREFS="weather_neural_speech_v1";
    public static final String VOICE_PREFIX="weather-local-kokoro:";
    private WeatherNeuralSpeechModels() {}
    public interface Progress { void update(long bytes,long total); }
    private static SharedPreferences prefs(Context context){return context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
    public static boolean enabled(Context context){return prefs(context).getBoolean("enabled",false);}
    public static void setEnabled(Context context,boolean value){
        if(value&&!installed(context))throw new IllegalStateException("Download a speech model first");
        if(!prefs(context).edit().putBoolean("enabled",value).commit())throw new IllegalStateException("Could not save speech engine");
    }
    public static int voice(Context context){int value=prefs(context).getInt("voice",3);return value>=0&&value<VOICE_NAMES.length?value:3;}
    public static void setVoice(Context context,int value){
        if(value<0||value>=VOICE_NAMES.length)throw new IllegalArgumentException("Unknown voice");
        if(!prefs(context).edit().putInt("voice",value).commit())throw new IllegalStateException("Could not save voice");
    }
    public static int ratePercent(Context context){return Math.max(50,Math.min(200,prefs(context).getInt("rate",100)));}
    public static void setRatePercent(Context context,int value){
        if(value<50||value>200)throw new IllegalArgumentException("Speech rate out of range");
        if(!prefs(context).edit().putInt("rate",value).commit())throw new IllegalStateException("Could not save speech rate");
    }
    public static boolean isLocalEngine(TextToSpeech engine){
        Set<Voice> voices=engine.getVoices();if(voices==null||voices.size()!=VOICE_NAMES.length)return false;
        Set<String> names=new HashSet<>();for(Voice voice:voices)names.add(voice.getName());
        for(String name:VOICE_NAMES)if(!names.contains(VOICE_PREFIX+name))return false;
        return true;
    }
    private static File root(Context context){return new File(context.getNoBackupFilesDir(),"weather_speech_models");}
    public static File directory(Context context){return new File(root(context),KOKORO);}
    public static boolean installed(Context context){
        try {
            File dir=directory(context);File stamp=new File(dir,".verified");
            if(!stamp.isFile()||stamp.length()!=SHA256.length())return false;
            try(InputStream in=new FileInputStream(stamp)){byte[] bytes=new byte[64];if(in.read(bytes)!=64||!SHA256.equals(new String(bytes,"US-ASCII")))return false;}
            return validFiles(dir);
        }catch(Exception ignored){return false;}
    }
    private static boolean validFiles(File dir){
        return sized(dir,"model.int8.onnx",114203914)&&sized(dir,"voices.bin",53790720)
            &&sized(dir,"tokens.txt",1111)&&sized(dir,"lexicon-zh.txt",2120131)
            &&sized(dir,"lexicon-us-en.txt",5956885)&&sized(dir,"lexicon-gb-en.txt",6366635)
            &&sized(dir,"number-zh.fst",64482)&&sized(dir,"date-zh.fst",59154)&&sized(dir,"phone-zh.fst",88630)
            &&new File(dir,"espeak-ng-data/phondata").isFile();
    }
    private static boolean sized(File dir,String name,long size){File file=new File(dir,name);return file.isFile()&&file.length()==size;}
    public static synchronized void download(Context context,CancellationSignal cancellation,Progress progress)throws Exception{
        Context app=context.getApplicationContext();
        if(!WeatherRefresh.networkAllowed(app))throw new IOException("请先允许天气联网 / Allow Weather network access first");
        File base=root(app);if(!base.isDirectory()&&!base.mkdirs())throw new IOException("Cannot create model directory");
        File archive=new File(base,"download-"+UUID.randomUUID()+".part");
        HttpsURLConnection connection=(HttpsURLConnection)new URL(URL).openConnection();
        connection.setConnectTimeout(15000);connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent","WeatherPort/NeuralSpeech");
        // GitHub's signed release-asset redirect is required; standard TLS and hostname checks stay on.
        connection.setInstanceFollowRedirects(true);
        cancellation.setOnCancelListener(connection::disconnect);
        try {
            cancellation.throwIfCanceled();
            if(connection.getResponseCode()!=200)throw new IOException("Speech model download failed");
            try(InputStream input=connection.getInputStream();OutputStream out=new FileOutputStream(archive)){
                copyDownload(input,out,cancellation,progress,app);
            }
            cancellation.throwIfCanceled();
            try(InputStream input=new FileInputStream(archive)){install(app,input,cancellation,progress);}
        }finally{cancellation.setOnCancelListener(null);connection.disconnect();archive.delete();}
    }
    private static void copyDownload(InputStream input,OutputStream output,CancellationSignal cancellation,Progress progress,Context context)throws Exception{
        byte[] buffer=new byte[65536];long bytes=0,last=0,started=System.nanoTime();int n;
        while((n=input.read(buffer))!=-1){
            cancellation.throwIfCanceled();
            if(!WeatherRefresh.networkAllowed(context))throw new IOException("Network consent withdrawn");
            bytes+=n;if(bytes>DOWNLOAD_BYTES||System.nanoTime()-started>20L*60*1000000000L)throw new IOException("Download limit exceeded");
            output.write(buffer,0,n);
            if(bytes-last>=512*1024){last=bytes;progress.update(bytes,DOWNLOAD_BYTES);}
        }
        if(bytes!=DOWNLOAD_BYTES)throw new IOException("Incomplete speech model download");
        progress.update(bytes,DOWNLOAD_BYTES);
    }
    /** Import accepts this exact published model archive; arbitrary executable/model files are not loaded. */
    public static synchronized void install(Context context,InputStream source,CancellationSignal cancellation,Progress progress)throws Exception{
        File base=root(context);if(!base.isDirectory()&&!base.mkdirs())throw new IOException("Cannot create model directory");
        File archive=new File(base,"import-"+UUID.randomUUID()+".part");
        File staging=new File(base,"stage-"+UUID.randomUUID());
        File previous=null;boolean published=false;
        try {
            MessageDigest hash=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];long bytes=0;int n;
            try(OutputStream output=new FileOutputStream(archive)){
                while((n=source.read(buffer))!=-1){cancellation.throwIfCanceled();bytes+=n;
                    if(bytes>DOWNLOAD_BYTES)throw new IOException("Unrecognized speech model archive");
                    hash.update(buffer,0,n);output.write(buffer,0,n);}
            }
            if(bytes!=DOWNLOAD_BYTES||!SHA256.equals(hex(hash.digest())))throw new IOException("模型校验失败，请选择官方 Kokoro v1.1 int8 包 / Model archive checksum mismatch");
            // Every accepted archive has this identical hash. Preserve immutable files
            // which a speech process may currently have mmap'ed instead of replacing them.
            if(installed(context))return;
            if(!staging.mkdir())throw new IOException("Cannot create model staging directory");
            extract(archive,staging,cancellation,progress);
            File candidate=new File(staging,KOKORO);
            if(!validFiles(candidate))throw new IOException("Incomplete speech model");
            try(OutputStream stamp=new FileOutputStream(new File(candidate,".verified"))){stamp.write(SHA256.getBytes("US-ASCII"));}
            cancellation.throwIfCanceled();File finalDir=directory(context);
            if(finalDir.exists()){previous=new File(base,"old-"+UUID.randomUUID());if(!finalDir.renameTo(previous))throw new IOException("Cannot preserve installed model");}
            if(!candidate.renameTo(finalDir)){if(previous!=null)previous.renameTo(finalDir);throw new IOException("Cannot publish speech model");}
            published=true;
        }finally{
            archive.delete();removeTree(staging,base);
            if(published&&previous!=null)removeTree(previous,base);
        }
    }
    private static void extract(File archive,File staging,CancellationSignal cancellation,Progress progress)throws Exception{
        Set<String> names=new HashSet<>();long expanded=0;int entries=0;byte[] buffer=new byte[65536];
        try(InputStream raw=new FileInputStream(archive);BZip2CompressorInputStream bz=new BZip2CompressorInputStream(raw);
            TarArchiveInputStream tar=new TarArchiveInputStream(bz)){
            TarArchiveEntry entry;
            while((entry=tar.getNextTarEntry())!=null){
                cancellation.throwIfCanceled();if(++entries>4096)throw new IOException("Too many model files");
                String name=entry.getName();
                if(!name.startsWith(KOKORO+"/")||name.indexOf('\\')>=0||name.indexOf(':')>=0||entry.isSymbolicLink()||entry.isLink()
                        ||(!entry.isDirectory()&&!entry.isFile())||!names.add(name))throw new IOException("Invalid model archive path");
                File target=new File(staging,name);String path=target.getCanonicalPath(),prefix=staging.getCanonicalPath()+File.separator;
                if(!path.startsWith(prefix)||name.contains("../")||name.contains("/.."))throw new IOException("Model path escapes staging directory");
                if(entry.isDirectory()){if(!target.isDirectory()&&!target.mkdirs())throw new IOException("Cannot create model directory");continue;}
                if(entry.getSize()<0||entry.getSize()>MAX_EXPANDED||expanded+entry.getSize()>MAX_EXPANDED)throw new IOException("Expanded model limit exceeded");
                File parent=target.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("Cannot create model parent");
                try(OutputStream output=new FileOutputStream(target)){int n;while((n=tar.read(buffer))!=-1){cancellation.throwIfCanceled();output.write(buffer,0,n);}}
                expanded+=entry.getSize();progress.update(expanded,-1);
            }
        }
    }
    private static void removeTree(File target,File base)throws IOException{
        if(!target.exists())return;
        if(!target.getCanonicalPath().startsWith(base.getCanonicalPath()+File.separator))throw new IOException("Refusing cleanup outside model storage");
        File[] children=target.listFiles();if(children!=null)for(File child:children)removeTree(child,base);
        if(!target.delete())throw new IOException("Could not remove temporary model file");
    }
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
    /** Actual ONNX speaker_names metadata, in sid order; no invented character labels. */
    public static final String[] VOICE_NAMES=("af_maple,af_sol,bf_vale,zf_001,zf_002,zf_003,zf_004,zf_005,zf_006,zf_007,zf_008,zf_017,zf_018,zf_019,zf_021,zf_022,zf_023,zf_024,zf_026,zf_027,zf_028,zf_032,zf_036,zf_038,zf_039,zf_040,zf_042,zf_043,zf_044,zf_046,zf_047,zf_048,zf_049,zf_051,zf_059,zf_060,zf_067,zf_070,zf_071,zf_072,zf_073,zf_074,zf_075,zf_076,zf_077,zf_078,zf_079,zf_083,zf_084,zf_085,zf_086,zf_087,zf_088,zf_090,zf_092,zf_093,zf_094,zf_099,zm_009,zm_010,zm_011,zm_012,zm_013,zm_014,zm_015,zm_016,zm_020,zm_025,zm_029,zm_030,zm_031,zm_033,zm_034,zm_035,zm_037,zm_041,zm_045,zm_050,zm_052,zm_053,zm_054,zm_055,zm_056,zm_057,zm_058,zm_061,zm_062,zm_063,zm_064,zm_065,zm_066,zm_068,zm_069,zm_080,zm_081,zm_082,zm_089,zm_091,zm_095,zm_096,zm_097,zm_098,zm_100").split(",");
}
