"""Exercise the same Java Chinese frontend used by the Android engine.

Compares dynamic city/forecast phones and BERT token IDs against Genie Python;
the first forecast also compares nonzero original float16 RoBERTa features.
"""
from __future__ import annotations
import importlib.util
import json
import os
import subprocess
import shutil
import sys
import types
from pathlib import Path

import jieba
import jieba.posseg
import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

ROOT = Path(__file__).resolve().parents[1]
VOICE = ROOT / '.build/tibao-voice'
SOURCE = VOICE / 'source/src/genie_tts'
DATA = VOICE / 'genie-data'
BUILD = VOICE / 'frontend-parity'


def load_source():
    # Isolate the frontend modules from unrelated GUI/server/model imports.
    for name, path in [('genie_tts', SOURCE), ('genie_tts.G2P', SOURCE/'G2P'),
                       ('genie_tts.G2P.Chinese', SOURCE/'G2P/Chinese'), ('genie_tts.Core', SOURCE/'Core')]:
        package = types.ModuleType(name); package.__path__ = [str(path)]; sys.modules[name] = package
    resources = types.ModuleType('genie_tts.Core.Resources')
    resources.Chinese_G2P_DIR = str(DATA/'G2P/ChineseG2P')
    sys.modules[resources.__name__] = resources
    sys.modules['jieba_fast'] = jieba
    sys.modules['jieba_fast.posseg'] = jieba.posseg
    from genie_tts.G2P.Chinese.ChineseG2P import processor
    return processor


def run():
    BUILD.mkdir(parents=True, exist_ok=True)
    java = Path(os.environ.get('JAVA_HOME', 'C:/Program Files/Java/jdk-17'))/'bin'
    android = Path(os.environ.get('ANDROID_HOME', 'C:/Users/a1510/AppData/Local/Android/Sdk'))/'platforms/android-36/android.jar'
    jar = ROOT/'.build/dependencies/onnxruntime-android-1.22.0.jar'
    if not jar.is_file():
        raise FileNotFoundError('Host parity requires ONNX Runtime Java 1.22.0')
    # JDK17 includes older VC runtime DLLs; Windows may choose them before the
    # installed runtime and fail during ORT DllMain. Use an isolated JDK copy.
    private_jre=VOICE/'frontend-jre'
    if not private_jre.exists():
        shutil.copytree(java.parent,private_jre)
        for name in ('msvcp140.dll','vcruntime140.dll','vcruntime140_1.dll'):
            shutil.copy2(Path(os.environ.get('SystemRoot','C:/Windows'))/'System32'/name,private_jre/'bin'/name)
    runner = BUILD/'FrontendParity.java'
    runner.write_text('''package dev.weather.oneplus;
import ai.onnxruntime.*; import java.io.*; import java.nio.*; import java.nio.file.*; import java.util.*;
public class FrontendParity {
 public static void main(String[] a)throws Exception {
  OrtEnvironment env=OrtEnvironment.getEnvironment();
  try(OrtSession.SessionOptions options=new OrtSession.SessionOptions()) {
   options.setIntraOpNumThreads(2);
   try(OrtSession bert=env.createSession(a[1],options);
       WeatherTibaoFrontend f=new WeatherTibaoFrontend(env,bert,new FileInputStream(a[0]+"/lexicon.bin.gz"),new FileInputStream(a[0]+"/vocab.txt"),new FileInputStream(a[0]+"/tone_words.txt"),Files.readAllBytes(Paths.get(a[0],"g2pm.onnx")))) {
    List<String> lines=Files.readAllLines(Paths.get(a[2]),java.nio.charset.StandardCharsets.UTF_8);
    for(int i=0;i<lines.size();i++) {
     WeatherTibaoFrontend.Result r=f.prepare(lines.get(i));
     System.out.println(i+"\\t"+r.normalizedText+"\\t"+Arrays.toString(r.phones)+"\\t"+Arrays.toString(r.word2ph)+"\\t"+Arrays.toString(r.tokenIds));
     if(i==0) {
      ByteBuffer out=ByteBuffer.allocate(r.bert.length*4).order(ByteOrder.LITTLE_ENDIAN);
      for(float value:r.bert)out.putFloat(value);
      Files.write(Paths.get(a[3]),out.array());
     }
    }
    for(String bad:Arrays.asList("weather tomorrow", "未知字符😀")) {
     try { f.prepare(bad);throw new AssertionError("Unsupported text accepted: "+bad); }
     catch(IllegalArgumentException expected) {}
    }
   }
  }
 }
}
''', encoding='utf8')
    cp = os.pathsep.join([str(android),str(jar)])
    subprocess.run([str(java/'javac.exe'),'--release','8','-encoding','UTF-8','-cp',cp,'-d',str(BUILD),str(ROOT/'port/WeatherTibaoFrontend.java'),str(runner)],check=True)
    corpus = [
        '北京今天晴，当前温度23℃，湿度65%，东北风3到4级。',
        '重庆今天多云，最高气温26℃，最低气温18℃。',
        '哈尔滨今天小雪，最低气温-15℃，请注意保暖。',
        '长春今天大雪，长时间外出请注意安全。',
        '成都气温20.5℃，降水概率60%，空气质量良好。',
        '上海今天阵雨，风速12km/h，降水量2.5mm。',
        '长沙今天阴转多云，气温-3～8℃，风力3-4级。',
        '乌鲁木齐明天有沙尘暴，部分地区能见度不足1公里。',
        '朝阳区今天天气晴朗，请做好防晒。',
        '2026年11月01日08:30，北京相对湿度80%。',
        '杭州今晚有雷阵雨，明天上午天气转晴。',
        '西安今天晴间多云，最高温度三十一度。',
        '南京今天有小雨，请随身带好雨伞。',
        '广州市今天天气闷热，体感温度三十四度。',
        '深圳未来三天多云转晴，空气质量优。',
        '大连明天北风五级，沿海阵风七到八级。',
        '苏州夜间最低温度零下二点五度。',
        '吐鲁番气温升高，阿勒泰部分地区有降雪。',
        '呼和浩特晴转阴，西北风三到四级。',
        '银川今天没有降水，紫外线指数中等。',
        '太阳出来了，不怕冷，休息一下再出门。',
        '拉萨日照充足，注意昼夜温差和高原防晒。',
        '嘉兴嘉善县气温适宜，日出时间06:05。',
        '空气质量指数AQI为125，PM2.5浓度35微克每立方米。',
        '台北今天天氣晴朗，最高溫度二十八度。',
        '空气质量指数300，气压1003百帕，颗粒物浓度125微克每立方米。',
        '气温-3℃~8℃，湿度40%～80%，风力３－４级。',
        '天气：晴朗！不要忘记防晒……明天见。',
    ]
    text_file=BUILD/'corpus.txt'; text_file.write_text('\n'.join(corpus),encoding='utf8')
    bert_file=BUILD/'bert.bin'
    runtime=VOICE/'frontend-jre/bin/java.exe'
    completed=subprocess.run([str(runtime if runtime.is_file() else java/'java.exe'),'-Xmx3g','-Dfile.encoding=UTF-8','-Donnxruntime.native.path='+str(VOICE/'frontend-native'),'-cp',os.pathsep.join([str(BUILD),str(jar),str(android)]),
            'dev.weather.oneplus.FrontendParity',str(ROOT/'port/assets/tibao_frontend'),str(DATA/'RoBERTa/RoBERTa.onnx'),str(text_file),str(bert_file)],capture_output=True,encoding='utf8')
    if completed.returncode:
        raise RuntimeError(completed.stderr)
    processor=load_source();tokenizer=Tokenizer.from_file(str(DATA/'RoBERTa/roberta_tokenizer/tokenizer.json'))
    cases=[]
    for line in completed.stdout.splitlines():
        index, normalized, phones, repeats, ids = line.split('\t')
        index=int(index);actual_phones=json.loads(phones);actual_repeats=json.loads(repeats);actual_ids=json.loads(ids)
        assert normalized.startswith('.'), normalized
        exact_normalizations={
            0:'.北京今天晴,当前温度二十三度,湿度百分之六十五,东北风三到四级.',
            2:'.哈尔滨今天小雪,最低气温零下十五度,请注意保暖.',
            5:'.上海今天阵雨,风速十二千米每小时,降水量二点五毫米.',
            25:'.空气质量指数三百,气压一千零三百帕,颗粒物浓度一百二十五微克每立方米.',
            26:'.气温零下三度至八度,湿度百分之四十至百分之八十,风力三到四级.',
        }
        if index in exact_normalizations:
            assert normalized==exact_normalizations[index],(corpus[index],normalized,exact_normalizations[index])
        expected_symbols,expected_repeats=processor.g2p(normalized)
        from genie_tts.G2P.SymbolsV2 import symbol_to_id_v2
        expected_phones=[symbol_to_id_v2[p] for p in expected_symbols]
        assert actual_phones==expected_phones, (corpus[index],normalized,actual_phones,expected_phones,expected_symbols)
        assert actual_repeats==expected_repeats, (corpus[index],actual_repeats,expected_repeats)
        assert actual_ids==tokenizer.encode(normalized).ids, (corpus[index],actual_ids,tokenizer.encode(normalized).ids)
        assert sum(actual_repeats)==len(actual_phones)
        cases.append({'input':corpus[index],'normalized':normalized,'phones':actual_phones,'word2ph':actual_repeats,'tokenIds':actual_ids})
    # Original model produces real phone-major half precision BERT features.
    first=cases[0]
    options=ort.SessionOptions();options.intra_op_num_threads=2
    bert=ort.InferenceSession(str(DATA/'RoBERTa/RoBERTa.onnx'),sess_options=options,providers=['CPUExecutionProvider'])
    expected=bert.run(None,{'input_ids':np.array([first['tokenIds']],np.int64),'attention_mask':np.ones((1,len(first['tokenIds'])),np.int64),'repeats':np.array(first['word2ph'],np.int64)})[0].astype(np.float32)
    actual=np.fromfile(bert_file,np.float32).reshape((-1,1024))
    assert actual.shape==expected.shape
    assert np.any(actual!=0) and np.isfinite(actual).all()
    error=float(np.max(np.abs(actual-expected)))
    assert np.allclose(actual,expected,rtol=0.02,atol=0.02),error
    report={'cases':len(cases),'bertShape':list(actual.shape),'bertMaxAbsError':error,'unsupportedInputsRejected':True,'fixtures':cases}
    (BUILD/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
    print(json.dumps({k:v for k,v in report.items() if k!='fixtures'}))


if __name__=='__main__':
    run()
