package dev.weather.oneplus;

import android.content.Intent;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import miuix.appcompat.widget.Button;
import miuix.appcompat.widget.Spinner;
import miuix.slidingwidget.widget.SlidingButton;
import miuix.androidbasewidget.widget.SeekBar;

/** Optional model install and voice choice using the existing MIUIX settings runtime. */
public final class WeatherSpeechSettingsActivity extends WeatherWidgetSettingsActivity {
    private static final int IMPORT=7103,IMPORT_TIBAO=7104;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private CancellationSignal cancellation;private Future<?> task;
    private TextView status;private Button download,importButton,cancel;
    private SlidingButton neural;private TextToSpeech preview;
    private boolean closed,restoring,foreground,installing;
    private int previewGeneration;
    private TextView tibaoStatus;private Button tibaoImport;
    private AudioManager audio;private AudioFocusRequest focus;private Runnable previewTimeout;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);setTitle(chinese?"天气播报":"Weather narration");
        LinearLayout page=page();
        note(page,chinese?"模型按需下载，合成和播放都在手机本地完成。未启用时使用系统语音。":"Download an optional model; synthesis and playback run on your phone. Off uses system speech.");
        LinearLayout tibaoCard=card(page);tibaoCard.addView(text(chinese?"缇宝天气播报":"Tribbie weather narration",19,false));
        tibaoStatus=text("",14,true);tibaoCard.addView(tibaoStatus);
        note(tibaoCard,chinese?"模型约 1.1 GB，首次使用导入本地转换包；单独存储，不计入 APK。":"About 1.1 GB. Import the locally prepared model once; it is stored separately from the APK.");
        tibaoImport=new Button(this);tibaoImport.setText(chinese?"导入缇宝模型包 (.zip)":"Import Tribbie model (.zip)");tibaoCard.addView(tibaoImport);
        tibaoImport.setOnClickListener(v->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),IMPORT_TIBAO));
        Spinner tibaoPreset=new Spinner(this);ArrayAdapter<String> presets=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,new String[]{
                chinese?"13 · 原声线（默认）":"13 · Original (default)",chinese?"19 · 较低随机强度":"19 · Lower acoustic variation",chinese?"20 · 较高随机强度":"20 · Higher acoustic variation"});
        presets.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);tibaoPreset.setAdapter(presets);
        int chosen=WeatherTibaoModels.preset(this);tibaoPreset.setSelection(chosen==19?1:chosen==20?2:0);tibaoCard.addView(tibaoPreset,new LinearLayout.LayoutParams(-1,dp(52)));
        tibaoPreset.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(android.widget.AdapterView<?> v){}
            public void onItemSelected(android.widget.AdapterView<?> v,android.view.View child,int at,long id){WeatherTibaoModels.setPreset(WeatherSpeechSettingsActivity.this,at==1?19:at==2?20:13);stopPreview();}
        });
        note(tibaoCard,chinese?"三档共用一套模型，切换不需要重新安装。":"The three presets share one model; switching needs no reinstallation.");
        Button enableTibao=new Button(this);enableTibao.setText(chinese?"使用缇宝播报":"Use Tribbie narration");tibaoCard.addView(enableTibao);
        enableTibao.setOnClickListener(v->{try{WeatherSpeechPreferences.setBackend(this,WeatherSpeechPreferences.TIBAO);stopPreview();renderStatus();toast(chinese?"已切换缇宝播报":"Tribbie narration selected");}catch(RuntimeException e){toast(chinese?"请先导入缇宝模型":"Import the Tribbie model first");}});
        Button previewTibao=new Button(this);previewTibao.setText(chinese?"试听缇宝声音":"Preview Tribbie");tibaoCard.addView(previewTibao);previewTibao.setOnClickListener(v->preview());
        Button system=new Button(this);system.setText(chinese?"跟随系统语音":"Use system speech");tibaoCard.addView(system);
        system.setOnClickListener(v->{WeatherSpeechPreferences.setBackend(this,WeatherSpeechPreferences.SYSTEM);stopPreview();renderStatus();});
        LinearLayout row=card(page);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.addView(text(chinese?"使用 Kokoro 语音":"Use Kokoro speech",17,false),new LinearLayout.LayoutParams(0,dp(52),1));
        neural=new SlidingButton(this);neural.setContentDescription(chinese?"使用本地自然语音":"Use local neural speech");
        neural.setChecked(WeatherSpeechPreferences.backend(this)==WeatherSpeechPreferences.KOKORO);row.addView(neural,new LinearLayout.LayoutParams(-2,-2));
        neural.setOnCheckedChangeListener((button,value)->{
            if(restoring)return;
            try{WeatherSpeechPreferences.setBackend(this,value?WeatherSpeechPreferences.KOKORO:WeatherSpeechPreferences.SYSTEM);stopPreview();renderStatus();}
            catch(RuntimeException error){restoring=true;neural.setChecked(!value);restoring=false;renderStatus();toast(chinese?"请先下载模型":"Download the model first");}
        });
        status=text("",14,true);page.addView(status,new LinearLayout.LayoutParams(-1,-2));
        note(page,chinese?"Kokoro v1.1 中文量化模型 · 下载约 140 MB；不计入 APK，支持 100 个中文音色和 3 个英文音色。":"Kokoro v1.1 int8 · about 140 MB download, stored separately from the APK. 100 Chinese and 3 English voices.");
        download=new Button(this);download.setText(chinese?"下载 Kokoro 中文模型":"Download Kokoro model");page.addView(download,new LinearLayout.LayoutParams(-1,-2));
        download.setOnClickListener(v->startInstall(null));
        importButton=new Button(this);importButton.setText(chinese?"导入官方模型包 (.tar.bz2)":"Import official model (.tar.bz2)");page.addView(importButton,new LinearLayout.LayoutParams(-1,-2));
        importButton.setOnClickListener(v->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),IMPORT));
        cancel=new Button(this);cancel.setText(chinese?"取消下载 / 导入":"Cancel download / import");page.addView(cancel,new LinearLayout.LayoutParams(-1,-2));cancel.setOnClickListener(v->cancelInstall());
        LinearLayout voices=card(page);voices.addView(text(chinese?"音色":"Voice",17,false));
        Spinner voice=new Spinner(this);String[] names=WeatherNeuralSpeechModels.VOICE_NAMES.clone();
        for(int i=0;i<names.length;i++)names[i]=(names[i].startsWith("zf_")?(chinese?"中文女声 ":"Chinese female "):names[i].startsWith("zm_")?(chinese?"中文男声 ":"Chinese male "):(chinese?"英文 ":"English "))+names[i];
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,names);adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        voice.setAdapter(adapter);voice.setSelection(WeatherNeuralSpeechModels.voice(this));voices.addView(voice,new LinearLayout.LayoutParams(-1,dp(52)));
        voice.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(android.widget.AdapterView<?> view){}
            public void onItemSelected(android.widget.AdapterView<?> view,android.view.View child,int at,long id){try{WeatherNeuralSpeechModels.setVoice(WeatherSpeechSettingsActivity.this,at);stopPreview();}catch(RuntimeException error){toast(chinese?"无法保存音色":"Could not save voice");}}
        });
        LinearLayout rateCard=card(page);TextView rateLabel=text("",17,false);rateCard.addView(rateLabel);
        SeekBar rate=new SeekBar(this,null);rate.setMax(150);rate.setProgress(WeatherNeuralSpeechModels.ratePercent(this)-50);
        rateLabel.setText((chinese?"语速：":"Speech rate: ")+WeatherNeuralSpeechModels.ratePercent(this)+"%");
        rateCard.addView(rate,new LinearLayout.LayoutParams(-1,dp(48)));
        rate.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(android.widget.SeekBar view,int value,boolean user){rateLabel.setText((chinese?"语速：":"Speech rate: ")+(value+50)+"%");}
            public void onStartTrackingTouch(android.widget.SeekBar view){stopPreview();}
            public void onStopTrackingTouch(android.widget.SeekBar view){
                try{WeatherNeuralSpeechModels.setRatePercent(WeatherSpeechSettingsActivity.this,view.getProgress()+50);}
                catch(RuntimeException error){view.setProgress(WeatherNeuralSpeechModels.ratePercent(WeatherSpeechSettingsActivity.this)-50);toast(chinese?"无法保存语速":"Could not save speech rate");}
            }
        });
        Button test=new Button(this);test.setText(chinese?"试听音色":"Preview voice");page.addView(test,new LinearLayout.LayoutParams(-1,-2));test.setOnClickListener(v->preview());
        note(page,chinese?"当前模型支持切换音色，暂无情感选项。":"This model supports voice selection; emotion options are unavailable.");
        link(page,chinese?"系统文字转语音设置":"System text-to-speech settings",chinese?"管理其他已安装的语音引擎":"Manage installed system speech engines",()->{
            try{startActivity(new Intent("com.android.settings.TTS_SETTINGS"));}catch(RuntimeException error){startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));}
        });renderStatus();
    }
    private void renderStatus(){
        boolean busy=installing,installed=WeatherNeuralSpeechModels.installed(this);
        tibaoImport.setEnabled(!busy);tibaoStatus.setText(WeatherTibaoModels.installed(this)?
                (WeatherSpeechPreferences.backend(this)==WeatherSpeechPreferences.TIBAO?(chinese?"缇宝已启用 · 预设 ":"Tribbie enabled · preset ")+WeatherTibaoModels.preset(this):(chinese?"缇宝模型已安装":"Tribbie model installed")):
                (chinese?"尚未导入缇宝模型":"Tribbie model not imported"));
        restoring=true;neural.setChecked(WeatherSpeechPreferences.backend(this)==WeatherSpeechPreferences.KOKORO);restoring=false;
        download.setEnabled(!busy);importButton.setEnabled(!busy);cancel.setEnabled(busy);neural.setEnabled(installed&&!busy);
        status.setText(busy?(chinese?"正在处理模型…":"Installing model…"):installed?(chinese?"模型已安装 · 离线可用":"Model installed · works offline"):(chinese?"尚未安装模型":"No model installed"));
    }
    private void startInstall(android.net.Uri uri){
        if(closed||installing)return;
        installing=true;
        cancellation=new CancellationSignal();CancellationSignal signal=cancellation;stopPreview();
        task=worker.submit(()->{
            String failure=null;
            try {
                WeatherNeuralSpeechModels.Progress progress=(bytes,total)->main.post(()->{if(!closed&&cancellation==signal&&!signal.isCanceled())status.setText(total>0
                        ?(chinese?"下载中 ":"Downloading ")+(bytes*100/total)+"%"
                        :(chinese?"解压并校验模型…":"Extracting and validating model…"));});
                if(uri==null)WeatherNeuralSpeechModels.download(getApplicationContext(),signal,progress);
                else try(InputStream input=getContentResolver().openInputStream(uri)){if(input==null)throw new java.io.IOException("Cannot open model archive");WeatherNeuralSpeechModels.install(getApplicationContext(),input,signal,progress);}
            }catch(Exception error){failure=signal.isCanceled()?(chinese?"已取消":"Cancelled"):error.getMessage();}
            String result=failure;main.post(()->{if(closed||cancellation!=signal)return;task=null;installing=false;cancellation=null;renderStatus();if(result!=null)toast(result);else toast(chinese?"模型已安装，可启用自然语音":"Model installed. You can enable neural speech.");});
        });renderStatus();
    }
    private void cancelInstall(){if(cancellation!=null)cancellation.cancel();renderStatus();}
    private void importTibao(android.net.Uri uri){
        if(closed||installing)return;installing=true;cancellation=new CancellationSignal();CancellationSignal signal=cancellation;stopPreview();
        worker.submit(()->{String failure=null;
            try(InputStream input=getContentResolver().openInputStream(uri)){
                if(input==null)throw new java.io.IOException("Cannot open model archive");
                WeatherTibaoModels.install(getApplicationContext(),input,signal,(bytes,total)->main.post(()->{
                    if(!closed&&cancellation==signal&&!signal.isCanceled())tibaoStatus.setText((chinese?"安装缇宝模型 ":"Installing Tribbie ")+(total>0?bytes*100/total:0)+"%");}));
            }catch(Exception e){failure=signal.isCanceled()?(chinese?"已取消":"Cancelled"):e.getMessage();}
            String result=failure;main.post(()->{if(closed||cancellation!=signal)return;installing=false;cancellation=null;renderStatus();toast(result==null?(chinese?"缇宝模型已安装":"Tribbie model installed"):result);});
        });renderStatus();
    }
    @Override protected void onActivityResult(int code,int result,Intent data){super.onActivityResult(code,result,data);
        if(code==IMPORT_TIBAO&&result==RESULT_OK&&data!=null&&data.getData()!=null)importTibao(data.getData());
        if(code==IMPORT&&result==RESULT_OK&&data!=null&&data.getData()!=null)startInstall(data.getData());}
    private void preview(){
        stopPreview();if(!WeatherSpeechPreferences.local(this)||!WeatherSpeechPreferences.installed(this)){toast(chinese?"请先安装模型并启用对应声音":"Install and enable the selected voice first");return;}
        final int attempt=++previewGeneration;
        try{
        preview=new TextToSpeech(this,code->main.post(()->{
            if(closed||!foreground||attempt!=previewGeneration||preview==null)return;if(code!=TextToSpeech.SUCCESS){toast(chinese?"无法初始化播报":"Could not initialize speech");stopPreview();return;}
            try{
                if(!WeatherSpeechPreferences.isPrivateEngine(preview))throw new IllegalStateException("Local engine unavailable");
                AudioAttributes attributes=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
                preview.setAudioAttributes(attributes);preview.setSpeechRate(WeatherNeuralSpeechModels.ratePercent(this)/100f);
                if(preview.setLanguage(java.util.Locale.SIMPLIFIED_CHINESE)<TextToSpeech.LANG_AVAILABLE)throw new IllegalStateException("Voice unavailable");
                preview.setOnUtteranceProgressListener(new UtteranceProgressListener(){
                    public void onStart(String id){main.post(()->{if(attempt==previewGeneration&&previewTimeout!=null){main.removeCallbacks(previewTimeout);previewTimeout=null;}});}
                    public void onDone(String id){main.post(()->{if(attempt==previewGeneration)stopPreview();});}
                    public void onError(String id){main.post(()->{if(attempt==previewGeneration){toast(chinese?"试听失败，请重试":"Preview failed. Try again.");stopPreview();}});}
                });
                audio=getSystemService(AudioManager.class);
                focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener(change->{if(change<0&&attempt==previewGeneration)stopPreview();},main).build();
                if(audio==null||audio.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED)throw new IllegalStateException("Audio busy");
                Bundle parameters=new Bundle();parameters.putInt("weather_voice_sid",WeatherNeuralSpeechModels.voice(this));
                if(WeatherSpeechPreferences.backend(this)==WeatherSpeechPreferences.TIBAO)parameters.putInt("weather_tibao_preset",WeatherTibaoModels.preset(this));
                if(preview.speak("今天上海晴，气温二十三度，出门记得带上水。",TextToSpeech.QUEUE_FLUSH,parameters,"weather-voice-preview")!=TextToSpeech.SUCCESS)throw new IllegalStateException("Speech rejected");
            }catch(RuntimeException error){toast(chinese?"无法试听，请稍后重试":"Could not preview. Try again later.");stopPreview();}
        }),getPackageName());
        previewTimeout=()->{if(attempt==previewGeneration){toast(chinese?"试听响应超时，请重试":"Preview timed out. Try again.");stopPreview();}};
        main.postDelayed(previewTimeout,90000);
        }catch(RuntimeException error){toast(chinese?"无法初始化播报":"Could not initialize speech");stopPreview();}
    }
    private void stopPreview(){++previewGeneration;
        if(previewTimeout!=null){main.removeCallbacks(previewTimeout);previewTimeout=null;}
        if(preview!=null){TextToSpeech old=preview;preview=null;try{old.stop();old.shutdown();}catch(RuntimeException ignored){}}
        if(focus!=null&&audio!=null){try{audio.abandonAudioFocusRequest(focus);}catch(RuntimeException ignored){}}focus=null;
    }
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    @Override public void onResume(){super.onResume();foreground=true;}
    @Override protected void onStop(){foreground=false;stopPreview();super.onStop();}
    @Override protected void onDestroy(){closed=true;if(cancellation!=null)cancellation.cancel();worker.shutdown();stopPreview();super.onDestroy();}
}
