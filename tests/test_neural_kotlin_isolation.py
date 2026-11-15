"""Verify isolated Kotlin ABI and typed JNI callbacks using real pinned Kotlin classes."""
from pathlib import Path
import importlib.util,os,subprocess,tempfile,zipfile,hashlib
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('isolation',ROOT/'port/isolate_neural_kotlin.py')
isolation=importlib.util.module_from_spec(spec);spec.loader.exec_module(isolation)
def main():
    suffix='.exe' if os.name=='nt' else '';java=Path(os.environ['JAVA_HOME'])/'bin'
    kotlin=ROOT/'.build/dependencies/kotlin-stdlib-2.2.0.jar';sherpa=ROOT/'.build/dependencies/sherpa-onnx-2.0.1.jar'
    with tempfile.TemporaryDirectory(prefix='neural-isolation-',dir=ROOT/'.build') as temp:
        p=Path(temp);source=p/'Probe.java';classes=p/'classes';classes.mkdir()
        source.write_text('''public class Probe {
          public static kotlin.jvm.functions.Function1<float[],Integer> callback(){return new kotlin.jvm.functions.Function1<float[],Integer>(){
            public Integer invoke(float[] x){return x.length;}};}
          public static void main(String[] x)throws Exception {
            Object c=callback();if(!c.getClass().getInterfaces()[0].getName().equals("dev.weather.speech.kotlin.jvm.functions.Function1"))throw new AssertionError("namespace");
            if(!c.getClass().getMethod("invoke",float[].class).getReturnType().equals(Integer.class))throw new AssertionError("JNI typed callback");
            if(callback().invoke(new float[]{1,2})!=2)throw new AssertionError("callback invoke");
            kotlin.Pair<String,Integer> pair=new kotlin.Pair<>("weather",3);
            if(!pair.getFirst().equals("weather")||pair.getSecond()!=3)throw new AssertionError("isolated stdlib ABI");
            kotlin.jvm.internal.Intrinsics.checkNotNull(pair);
            System.out.println("PASS: isolated runtime, original JNI typed method and stdlib ABI");
          }}''',encoding='utf8')
        subprocess.run([str(java/('javac'+suffix)),'--release','8','-cp',str(kotlin),'-d',str(classes),str(source)],check=True)
        host,jars=isolation.isolated_inputs(list(classes.glob('*.class')),[sherpa,kotlin],p/'isolated')
        with zipfile.ZipFile(jars[0]) as jar:
            body=jar.read('com/k2fsa/sherpa/onnx/OfflineTts.class')
            assert b'Ldev/weather/speech/kotlin/jvm/functions/Function1;' in body
            assert b'com/k2fsa/sherpa/onnx/OfflineTts' in body
        with zipfile.ZipFile(jars[1]) as jar:
            assert 'dev/weather/speech/kotlin/jvm/internal/Intrinsics.class' in jar.namelist()
            assert not any(n.startswith('kotlin/') and n.endswith('.class') for n in jar.namelist())
        subprocess.run([str(java/('java'+suffix)),'-cp',os.pathsep.join(map(str,(p/'isolated/host',*jars))),'Probe'],check=True)
        hashes=[hashlib.sha256(j.read_bytes()).hexdigest() for j in jars]
        isolation.isolated_inputs(list(classes.glob('*.class')),[sherpa,kotlin],p/'isolated')
        assert hashes==[hashlib.sha256(j.read_bytes()).hexdigest() for j in jars]
        print('PASS: deterministic isolated jars preserve JNI class paths')
if __name__=='__main__':main()
