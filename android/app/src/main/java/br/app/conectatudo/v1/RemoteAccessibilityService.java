package br.app.conectatudo.v1;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Point;
import android.os.*;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONObject;

/** Nunca lê/encaminha conteúdos da tela: somente gestos e texto enviado voluntariamente. */
public final class RemoteAccessibilityService extends AccessibilityService {
    public static volatile RemoteAccessibilityService instance;
    private final Handler main = new Handler(Looper.getMainLooper());
    @Override protected void onServiceConnected(){super.onServiceConnected();instance=this;}
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { /* Não coletar eventos nem texto. */ }
    @Override public void onInterrupt() { }
    @Override public void onDestroy(){if(instance==this)instance=null;super.onDestroy();}

    public boolean queue(JSONObject cmd){
        if(instance!=this)return false;
        final String action=cmd.optString("action","");
        if(!(action.equals("click")||action.equals("double")||action.equals("swipe")||action.equals("back")||action.equals("home")||action.equals("recents")||action.equals("text")))return false;
        if(action.equals("text") && cmd.optString("text","").length()>300)return false;
        if(action.equals("click")||action.equals("double")||action.equals("swipe")){
            double x=cmd.optDouble("x",-1),y=cmd.optDouble("y",-1);
            if(!within(x)||!within(y))return false;
            if(action.equals("swipe")&&(!within(cmd.optDouble("x2",-1))||!within(cmd.optDouble("y2",-1))))return false;
        }
        main.post(()->{
            LanService host=LanService.active;
            if(instance!=this||host==null||!host.controlActive())return;
            try{apply(cmd);}catch(Exception ignored){}
        });
        return true;
    }
    private static boolean within(double x){return !Double.isNaN(x)&&x>=0&&x<=1;}
    @SuppressWarnings("deprecation")
    private Point screenSize(){Point p=new Point();((WindowManager)getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealSize(p);return p;}
    private void gesture(JSONObject c,boolean doubleTap){
        Point p=screenSize();float x=(float)(c.optDouble("x",.5)*(p.x-1)),y=(float)(c.optDouble("y",.5)*(p.y-1));
        Path path=new Path();path.moveTo(x,y);
        long duration=doubleTap?65:Math.max(80,Math.min(1300,c.optLong("duration",260)));
        if("swipe".equals(c.optString("action")))path.lineTo((float)(c.optDouble("x2",.5)*(p.x-1)),(float)(c.optDouble("y2",.5)*(p.y-1)));
        GestureDescription.Builder builder=new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path,0,duration));
        dispatchGesture(builder.build(),null,null);
        if(doubleTap) main.postDelayed(()->{
            LanService host=LanService.active;
            if(host!=null&&host.controlActive()&&instance==this){
                GestureDescription.Builder second=new GestureDescription.Builder();
                second.addStroke(new GestureDescription.StrokeDescription(path,0,65));dispatchGesture(second.build(),null,null);
            }
        },165);
    }
    private void apply(JSONObject c){
        switch(c.optString("action","")){
          case "click":case "swipe": gesture(c,false);break;
          case "double":gesture(c,true);break;
          case "back":performGlobalAction(GLOBAL_ACTION_BACK);break;
          case "home":performGlobalAction(GLOBAL_ACTION_HOME);break;
          case "recents":performGlobalAction(GLOBAL_ACTION_RECENTS);break;
          case "text":{
            AccessibilityNodeInfo root=getRootInActiveWindow();if(root==null)return;
            AccessibilityNodeInfo edit=root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if(edit!=null&&edit.isEditable()&&!edit.isPassword()){
                Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,c.optString("text",""));
                edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args);
            }break;
          }
        }
    }
}
