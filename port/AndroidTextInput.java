package dev.weather.oneplus;

import android.app.Activity;
import android.text.*;
import android.view.*;
import android.view.inputmethod.*;
import org.json.*;

/** Android IME adapter for Flutter's text input platform channel. */
final class AndroidTextInput {
    interface Sender {void send(String channel,String json);}
    final Activity activity;
    final Sender sender;
    final InputMethodManager manager;
    final Editable editable=Editable.Factory.getInstance().newEditable("");
    View view;
    int client=-1;
    JSONObject configuration=new JSONObject();
    boolean updating;
    AndroidTextInput(Activity activity,Sender sender){
        this.activity=activity;this.sender=sender;manager=(InputMethodManager)activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
    }
    Object handle(String method,Object arguments)throws JSONException{
        if(method.equals("TextInput.setClient")){
            JSONArray a=(JSONArray)arguments;client=a.getInt(0);configuration=a.getJSONObject(1);manager.restartInput(view);
        }else if(method.equals("TextInput.setEditingState")){
            JSONObject a=(JSONObject)arguments;updating=true;
            editable.replace(0,editable.length(),a.optString("text"));
            int base=a.optInt("selectionBase",editable.length()),extent=a.optInt("selectionExtent",base);
            if(base>=0&&extent>=0)Selection.setSelection(editable,Math.min(base,editable.length()),Math.min(extent,editable.length()));
            updating=false;manager.updateSelection(view,Selection.getSelectionStart(editable),Selection.getSelectionEnd(editable),-1,-1);
        }else if(method.equals("TextInput.show")){view.requestFocus();manager.showSoftInput(view,0);}
        else if(method.equals("TextInput.hide")){manager.hideSoftInputFromWindow(view.getWindowToken(),0);}
        else if(method.equals("TextInput.clearClient")){client=-1;}
        return null;
    }
    InputConnection connection(EditorInfo info){
        if(client<0)return null;
        JSONObject type=configuration.optJSONObject("inputType");String name=type==null?"":type.optString("name");
        info.inputType=android.text.InputType.TYPE_CLASS_TEXT;
        if(name.contains("number"))info.inputType=android.text.InputType.TYPE_CLASS_NUMBER;
        else if(name.contains("phone"))info.inputType=android.text.InputType.TYPE_CLASS_PHONE;
        else if(name.contains("email"))info.inputType|=android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS;
        else if(name.contains("multiline"))info.inputType|=android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE;
        if(configuration.optBoolean("obscureText"))info.inputType|=android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD;
        String action=configuration.optString("inputAction","TextInputAction.done");
        int code=action.endsWith("search")?EditorInfo.IME_ACTION_SEARCH:action.endsWith("go")?EditorInfo.IME_ACTION_GO:action.endsWith("next")?EditorInfo.IME_ACTION_NEXT:action.endsWith("send")?EditorInfo.IME_ACTION_SEND:EditorInfo.IME_ACTION_DONE;
        info.imeOptions=code|EditorInfo.IME_FLAG_NO_EXTRACT_UI;
        info.initialSelStart=Math.max(0,Selection.getSelectionStart(editable));info.initialSelEnd=Math.max(0,Selection.getSelectionEnd(editable));
        return new BaseInputConnection(view,true){
            private int batch;
            @Override public Editable getEditable(){return editable;}
            @Override public boolean beginBatchEdit(){batch++;return true;}
            @Override public boolean endBatchEdit(){if(batch>0)batch--;if(batch==0)changed();return true;}
            @Override public boolean commitText(CharSequence text,int cursor){boolean ok=super.commitText(text,cursor);if(batch==0)changed();return ok;}
            @Override public boolean setComposingText(CharSequence text,int cursor){boolean ok=super.setComposingText(text,cursor);if(batch==0)changed();return ok;}
            @Override public boolean finishComposingText(){boolean ok=super.finishComposingText();if(batch==0)changed();return ok;}
            @Override public boolean deleteSurroundingText(int before,int after){boolean ok=super.deleteSurroundingText(before,after);if(batch==0)changed();return ok;}
            @Override public boolean setSelection(int start,int end){boolean ok=super.setSelection(start,end);if(batch==0)changed();return ok;}
            @Override public boolean performEditorAction(int actionCode){try{sender.send("flutter/textinput",new JSONObject().put("method","TextInputClient.performAction").put("args",new JSONArray().put(client).put(action)).toString());}catch(JSONException ignored){}return true;}
        };
    }
    private void changed(){
        if(updating||client<0)return;
        try{
            JSONObject state=new JSONObject().put("text",editable.toString()).put("selectionBase",Selection.getSelectionStart(editable)).put("selectionExtent",Selection.getSelectionEnd(editable)).put("selectionAffinity","TextAffinity.downstream").put("selectionIsDirectional",false).put("composingBase",BaseInputConnection.getComposingSpanStart(editable)).put("composingExtent",BaseInputConnection.getComposingSpanEnd(editable));
            sender.send("flutter/textinput",new JSONObject().put("method","TextInputClient.updateEditingState").put("args",new JSONArray().put(client).put(state)).toString());
        }catch(JSONException ignored){}
    }
}
