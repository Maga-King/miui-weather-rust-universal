"""Exercise production asynchronous logging, overload/close ordering and bounded rotation."""
from pathlib import Path
import os,subprocess,tempfile
ROOT=Path(__file__).resolve().parents[1]
def main():
    suffix='.exe' if os.name=='nt' else '';java=Path(os.environ['JAVA_HOME'])/'bin'
    with tempfile.TemporaryDirectory(prefix='weather-diagnostics-',dir=ROOT/'.build') as tmp:
        p=Path(tmp);log=p/'android/util/Log.java';log.parent.mkdir(parents=True)
        log.write_text('''package android.util;public class Log {
        public static java.util.List<String> errors=java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        public static int e(String t,String m){errors.add(m);return 0;}public static int e(String t,String m,Throwable x){errors.add(m);return 0;}
        public static int w(String t,String m){return 0;}}''',encoding='utf8')
        test=p/'dev/weather/oneplus/DiagnosticsTest.java';test.parent.mkdir(parents=True)
        test.write_text('''package dev.weather.oneplus;
        import java.io.*;import java.nio.file.*;import java.util.*;import java.util.concurrent.*;
        public class DiagnosticsTest {
        static int checks;static void check(boolean x,String m){checks++;if(!x)throw new AssertionError(m);}
        static String text(File f)throws Exception{return new String(Files.readAllBytes(f.toPath()),"UTF-8");}
        public static void main(String[] args)throws Exception{
          File dir=new File(args[0]);WeatherDiagnostics.Hub hub=new WeatherDiagnostics.Hub(4096,2*1024*1024);
          File file=new File(dir,"order.log");WeatherDiagnostics logger=new WeatherDiagnostics(file,true,hub);
          List<Thread> threads=new ArrayList<>();
          for(int t=0;t<8;t++){final int id=t;Thread worker=new Thread(()->{for(int i=0;i<100;i++)logger.verbose(id+":"+i);});threads.add(worker);worker.start();}
          for(Thread thread:threads)thread.join();logger.marker("done");logger.error("diagnostic problem",new IllegalStateException("specific trace"));logger.close();
          check(logger.awaitClosed(5000),"close drains asynchronously");String output=text(file);
          check(output.contains("STARTUP done")&&output.contains("specific trace"),"markers/errors persist with stacktrace");
          check(output.split("DEBUG ").length-1==800,"concurrent accepted lines not lost");
          for(int t=0;t<8;t++){int previous=-1;for(int i=0;i<100;i++){int at=output.indexOf("DEBUG "+t+":"+i+"\\n");check(at>previous,"producer order "+t+":"+i);previous=at;}}
          WeatherDiagnostics disabled=new WeatherDiagnostics(new File(dir,"default.log"),false,hub);disabled.verbose("should not exist");disabled.marker("ready");disabled.close();check(disabled.awaitClosed(5000),"disabled drain");
          check(!text(new File(dir,"default.log")).contains("should not exist"),"verbose off by default policy");
          File shared=new File(dir,"recreated.log");WeatherDiagnostics before=new WeatherDiagnostics(shared,true,hub),after=new WeatherDiagnostics(shared,true,hub);
          synchronized(hub.lock){before.verbose("old activity");after.verbose("new activity before old close");before.close();after.verbose("new activity after old close");after.close();}
          check(before.awaitClosed(5000)&&after.awaitClosed(5000),"Activity recreation close drains in sequence");
          String recreated=text(shared);check(recreated.contains("old activity")&&recreated.contains("new activity before old close")&&recreated.contains("new activity after old close"),"shared pathname append survives old Activity close");
          WeatherDiagnostics.Hub small=new WeatherDiagnostics.Hub(2,1024);File fullFile=new File(dir,"full.log");WeatherDiagnostics full=new WeatherDiagnostics(fullFile,true,small);
          synchronized(small.lock){full.verbose("accepted one");full.verbose("accepted two");full.verbose("overflow");full.error("error during overflow",null);
            long start=System.nanoTime();full.close();check(System.nanoTime()-start<50000000L,"close never waits on file IO/worker");}
          check(full.awaitClosed(5000),"full queue still closes after accepted records");String fullText=text(fullFile);
          check(fullText.contains("accepted one")&&fullText.contains("accepted two")&&!fullText.contains("overflow"),"bounded overload drops file data only");
          check(android.util.Log.errors.contains("error during overflow"),"overflow errors always logcat");
          full.error("error after close",null);check(android.util.Log.errors.contains("error after close"),"closed Activity error still logcat");
          WeatherDiagnostics.Hub rotation=new WeatherDiagnostics.Hub(512,256);File rotate=new File(dir,"rotate.log");WeatherDiagnostics r=new WeatherDiagnostics(rotate,true,rotation);
          for(int i=0;i<40;i++)r.verbose("record-"+i+"-abcdefghijklmnopqrstuv");r.close();check(r.awaitClosed(5000),"rotation drain");
          check(rotate.length()<=256&&new File(rotate+".1").length()<=256,"rotation bounds two files");
          check(text(rotate).contains("record-39"),"rotation retains newest entry");
          File legacy=new File(dir,"legacy.log");Files.write(legacy.toPath(),new byte[4096]);Files.write(new File(legacy+".1").toPath(),new byte[4096]);
          WeatherDiagnostics old=new WeatherDiagnostics(legacy,false,rotation);old.marker("fresh");old.close();check(old.awaitClosed(5000),"legacy cleanup drain");
          check(legacy.length()<=256&&!new File(legacy+".1").exists(),"oversized legacy files trimmed only on worker");
          File bad=new File(dir,"directory.log");bad.mkdir();WeatherDiagnostics broken=new WeatherDiagnostics(bad,true,hub);broken.error("storage denied",null);broken.close();check(broken.awaitClosed(5000),"file failure cannot strand close");
          check(android.util.Log.errors.contains("storage denied"),"file failure keeps original error");
          System.out.println("PASS: "+checks+" diagnostics concurrency/ordering/overflow/rotation assertions");
        }}''',encoding='utf8')
        classes=p/'classes';classes.mkdir()
        subprocess.run([str(java/('javac'+suffix)),'--release','8','-encoding','UTF-8','-d',str(classes),str(log),str(test),str(ROOT/'port/WeatherDiagnostics.java')],check=True)
        data=p/'data';data.mkdir();subprocess.run([str(java/('java'+suffix)),'-cp',str(classes),'dev.weather.oneplus.DiagnosticsTest',str(data)],check=True)
if __name__=='__main__':main()
