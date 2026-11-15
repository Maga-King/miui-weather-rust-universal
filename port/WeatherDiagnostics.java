package dev.weather.oneplus;

import android.util.Log;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Bounded diagnostics: all file operations belong to one process-wide daemon worker. */
public final class WeatherDiagnostics implements AutoCloseable {
    private static final int QUEUE_LIMIT=512,LINE_LIMIT=8192;
    private static final long FILE_LIMIT=2L*1024*1024;
    private static final Hub HUB=new Hub(QUEUE_LIMIT,FILE_LIMIT);
    private final Hub hub;private final File file;private final boolean verbose;
    private boolean closed;private int dropped;
    private final CountDownLatch fileClosed=new CountDownLatch(1);

    public WeatherDiagnostics(File file,boolean verbose){this(file,verbose,HUB);}
    WeatherDiagnostics(File file,boolean verbose,Hub hub){this.file=file.getAbsoluteFile();this.verbose=verbose;this.hub=hub;}
    public boolean isVerbose(){return verbose;}
    public void verbose(String message){if(verbose)enqueue("DEBUG "+message,null);}
    public void marker(String message){enqueue("STARTUP "+message,null);}
    public void warning(String message){Log.w("WeatherChannel",message);enqueue("WARN "+message,null);}
    public void error(String message,Throwable error){
        // Even a saturated queue, unavailable storage or a closed Activity keeps errors in logcat.
        if(error==null)Log.e("WeatherChannel",message);else Log.e("WeatherChannel",message,error);
        enqueue("ERROR "+message,error);
    }
    private void enqueue(String message,Throwable error){
        if(message.length()>LINE_LIMIT)message=message.substring(0,LINE_LIMIT)+" [truncated]";
        synchronized(hub.lock){
            if(closed)return;
            if(hub.queued>=hub.limit){++dropped;return;}
            if(dropped!=0){message+=" [dropped "+dropped+" queued diagnostics]";dropped=0;}
            hub.queue.addLast(new Entry(this,message,error,false));++hub.queued;hub.lock.notifyAll();
        }
    }
    @Override public void close(){
        synchronized(hub.lock){
            if(closed)return;closed=true;
            // Control records never wait for capacity. File close follows every accepted record.
            hub.queue.addLast(new Entry(this,null,null,true));hub.lock.notifyAll();
        }
    }
    boolean awaitClosed(long milliseconds)throws InterruptedException{return fileClosed.await(milliseconds,TimeUnit.MILLISECONDS);}
    private static final class Entry {
        final WeatherDiagnostics owner;final String text;final Throwable error;final boolean close;
        Entry(WeatherDiagnostics owner,String text,Throwable error,boolean close){this.owner=owner;this.text=text;this.error=error;this.close=close;}
    }
    static final class Hub {
        final Object lock=new Object();final ArrayDeque<Entry> queue=new ArrayDeque<>();final int limit;
        final long fileLimit;final Map<String,Sink> sinks=new HashMap<>();
        Hub(int limit,long fileLimit){
            this.limit=limit;this.fileLimit=fileLimit;
            Thread worker=new Thread(this::run,"WeatherDiagnostics");worker.setDaemon(true);worker.start();
        }
        private void run(){
            while(true){
                Entry entry;
                synchronized(lock){
                    while(queue.isEmpty())try{lock.wait();}catch(InterruptedException ignored){}
                    entry=queue.removeFirst();if(!entry.close)--queued;
                }
                try{
                    String key=entry.owner.file.getPath();
                    if(entry.close){try{Sink sink=sinks.remove(key);if(sink!=null)sink.close();}finally{entry.owner.fileClosed.countDown();}continue;}
                    Sink sink=sinks.get(key);
                    if(sink==null){sink=new Sink(entry.owner.file,fileLimit);sinks.put(key,sink);}
                    sink.write(entry.text,entry.error);
                }catch(IOException|RuntimeException failure){
                    Sink sink=sinks.remove(entry.owner.file.getPath());if(sink!=null)try{sink.close();}catch(IOException ignored){}
                    Log.e("WeatherChannel","Cannot write diagnostic file",failure);
                }
            }
        }
        int queued;
    }
    private static final class Sink implements Closeable {
        final File file;final long limit;OutputStream output;long bytes;
        Sink(File file,long limit){this.file=file;this.limit=limit;}
        void write(String text,Throwable error)throws IOException {
            if(error!=null){StringWriter trace=new StringWriter();error.printStackTrace(new PrintWriter(trace));text+="\n"+trace;}
            if(text.length()>LINE_LIMIT)text=text.substring(0,LINE_LIMIT)+" [truncated]";
            byte[] data=(text+"\n").getBytes(StandardCharsets.UTF_8);
            if(output==null){
                File parent=file.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("Cannot create diagnostic directory");
                File previous=new File(file.getPath()+".1");
                if(previous.length()>limit&&!previous.delete())throw new IOException("Cannot discard oversized legacy diagnostics");
                bytes=file.length();if(bytes>limit)bytes=0;
                output=new BufferedOutputStream(new FileOutputStream(file,bytes!=0),32768);
            }
            if(bytes>0&&bytes+data.length>limit){
                close();File previous=new File(file.getPath()+".1");
                if(previous.exists()&&!previous.delete())throw new IOException("Cannot remove previous diagnostic log");
                if(file.exists()&&!file.renameTo(previous))throw new IOException("Cannot rotate diagnostic log");
                output=new BufferedOutputStream(new FileOutputStream(file,false),32768);bytes=0;
            }
            output.write(data);bytes+=data.length;
            // Coarse markers/errors remain inspectable during a running session.
            if(error!=null||text.startsWith("STARTUP ")||text.startsWith("ERROR "))output.flush();
        }
        public void close()throws IOException{if(output!=null){OutputStream old=output;output=null;old.close();}}
    }
}
