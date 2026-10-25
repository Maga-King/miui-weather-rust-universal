package dev.weather.oneplus;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real Android Editable/InputConnection tests; no access to weather data. */
public final class TextInputRegressionProbe extends Activity {
    private AndroidTextInput bridge;
    private InputConnection connection;
    private final ArrayList<JSONObject> edits=new ArrayList<>();
    private int assertions;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        SurfaceView surface=new SurfaceView(this);setContentView(surface);
        bridge=new AndroidTextInput(this,(channel,json)->{
            try {
                JSONObject message=new JSONObject(json);
                if(message.optString("method").equals("TextInputClient.updateEditingState"))
                    edits.add(message.getJSONArray("args").getJSONObject(1));
            }catch(Exception error){throw new RuntimeException(error);}
        });
        bridge.view=surface;surface.setFocusableInTouchMode(true);surface.requestFocus();
        surface.post(()->{
            JSONObject result=new JSONObject();
            try {test();result.put("passed",true).put("assertions",assertions);}
            catch(Throwable error){try{result.put("passed",false).put("assertions",assertions).put("failure",error.toString());}catch(Exception ignored){}android.util.Log.e("TextInputProbe","FAIL",error);}
            try(FileOutputStream out=new FileOutputStream(new File(getFilesDir(),"result.json"))){out.write(result.toString().getBytes(StandardCharsets.UTF_8));}
            catch(Exception error){android.util.Log.e("TextInputProbe","Cannot save result",error);}
            finish();
        });
    }
    private void check(boolean condition,String why){assertions++;if(!condition)throw new AssertionError(why);}
    private void seed(String value,int start,int end)throws Exception{
        bridge.handle("TextInput.setEditingState",new JSONObject().put("text",value).put("selectionBase",start)
                .put("selectionExtent",end).put("composingBase",-1).put("composingExtent",-1));
        edits.clear();
    }
    private void textIs(String expected)throws Exception{
        check(bridge.editable.toString().equals(expected),"Editable expected "+expected+" got "+bridge.editable);
        check(!edits.isEmpty(),"Deletion must notify the Flutter framework");
        check(edits.get(edits.size()-1).getString("text").equals(expected),"Framework text must match Editable");
    }
    private void key(int code){
        check(connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,code)),"Key down handled");
        check(connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,code)),"Key up handled");
    }
    private void test()throws Exception{
        bridge.handle("TextInput.setClient",new JSONArray().put(42).put(new JSONObject()
                .put("inputType",new JSONObject().put("name","TextInputType.text"))
                .put("inputAction","TextInputAction.search")));
        seed("",0,0);EditorInfo info=new EditorInfo();connection=bridge.connection(info);
        check((info.imeOptions&EditorInfo.IME_MASK_ACTION)==EditorInfo.IME_ACTION_SEARCH,"Search IME action preserved");
        connection.commitText("广州",1);textIs("广州");
        key(KeyEvent.KEYCODE_DEL);textIs("广");
        key(KeyEvent.KEYCODE_DEL);textIs("");
        key(KeyEvent.KEYCODE_DEL);textIs("");
        seed("ABC",1,1);key(KeyEvent.KEYCODE_FORWARD_DEL);textIs("AC");
        seed("ABCD",1,3);key(KeyEvent.KEYCODE_DEL);textIs("AD");
        seed("厦门😀",4,4);connection.deleteSurroundingTextInCodePoints(1,0);textIs("厦门");
        seed("厦门😀",4,4);key(KeyEvent.KEYCODE_DEL);textIs("厦门");
        seed("广州",2,2);connection.deleteSurroundingText(1,0);textIs("广");
        seed("厦门",0,0);connection.deleteSurroundingTextInCodePoints(0,1);textIs("门");
        seed("ABC",3,3);connection.beginBatchEdit();
        key(KeyEvent.KEYCODE_DEL);key(KeyEvent.KEYCODE_DEL);
        check(edits.isEmpty(),"Batch does not send half-edited states");
        connection.endBatchEdit();textIs("A");check(edits.size()==1,"Batch publishes one final state");
        seed("",0,0);connection.setComposingText("beijing",1);textIs("beijing");
        JSONObject echo=edits.get(edits.size()-1);bridge.handle("TextInput.setEditingState",echo);
        check(BaseInputConnection.getComposingSpanStart(bridge.editable)==0,"Echo preserves composition start");
        check(BaseInputConnection.getComposingSpanEnd(bridge.editable)==7,"Echo preserves composition end");
        connection.commitText("北京",1);textIs("北京");
        seed("北京",2,2);check(bridge.handleKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DEL)),"Surface key route handled");textIs("北");
        check(!bridge.handleKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK)),"System back remains available");
        bridge.handle("TextInput.clearClient",null);
        check(!bridge.handleKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DEL)),"Inactive editor cannot consume deletion");
    }
}
