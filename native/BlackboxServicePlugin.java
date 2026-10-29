package com.lifeblackbox.recorder;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

/** Native service bridge; microphone lifecycle is owned by the foreground service. */
@CapacitorPlugin(
    name = "BlackboxService",
    permissions = { @Permission(alias = "microphone", strings = { "android.permission.RECORD_AUDIO" }), @Permission(alias = "notifications", strings = { "android.permission.POST_NOTIFICATIONS" }) }
)
public class BlackboxServicePlugin extends Plugin {

    @PluginMethod
    public void start(PluginCall call) {
        if (getPermissionState("microphone") != PermissionState.GRANTED) {
            requestPermissionForAlias("microphone", call, "afterMicrophonePermission"); return;
        }
        startWithNotifications(call);
    }
    @PermissionCallback
    private void afterMicrophonePermission(PluginCall call) {
        if (getPermissionState("microphone") != PermissionState.GRANTED) { call.reject("마이크 권한이 필요합니다"); return; }
        startWithNotifications(call);
    }
    private void startWithNotifications(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 33 && getPermissionState("notifications") != PermissionState.GRANTED) {
            requestPermissionForAlias("notifications", call, "afterNotificationPermission");
            return;
        }
        launch(call);
    }

    @PermissionCallback
    private void afterNotificationPermission(PluginCall call) {
        launch(call);   // 알림 권한을 거부해도 서비스는 동작 (알림만 숨겨짐)
    }

    private void launch(PluginCall call) {
        try {
            if(!getContext().getSharedPreferences("usage-notice",0).getBoolean("accepted",false)){call.reject("먼저 사용 안내를 확인해 주세요");return;}
            Intent i = new Intent(getContext(), BlackboxService.class);
            i.putExtra(BlackboxService.EXTRA_TEXT, call.getString("text", BlackboxService.DEFAULT_TEXT));
            persistSettings(call.getObject("config",new JSObject()));
            i.putExtra("config", call.getObject("config", new JSObject()).toString());
            ContextCompat.startForegroundService(getContext(), i);
            call.resolve();
        } catch (Exception e) {
            call.reject("포그라운드 서비스 시작 실패: " + e.getMessage());
        }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        RecordingSession.stop(getContext());
        if (BlackboxService.running) getContext().startService(new Intent(getContext(), BlackboxService.class).setAction(BlackboxService.ACTION_STOP));
        call.resolve();
    }

    @PluginMethod
    public void requestIgnoreBattery(PluginCall call) {
        Context ctx = getContext();
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        boolean ignoring = pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
        if (!ignoring) {
            try {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + ctx.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Exception e) {
                Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            }
        }
        JSObject r = new JSObject();
        r.put("ignoring", ignoring);
        call.resolve(r);
    }

    @PluginMethod public void status(PluginCall call) {
        try {
            NativeRecorder r=NativeRecorder.instance;
            org.json.JSONObject o=r==null?new org.json.JSONObject().put("running",false).put("error",BlackboxService.lastError).put("filesVersion",RecordingStore.version).put("maintenance",WindowMerger.lastError).put("merging",WindowMerger.working):r.status(Boolean.TRUE.equals(call.getBoolean("visual",true)));
            call.resolve(new JSObject(o.toString()));
        } catch(Exception e){call.reject(e.getMessage());}
    }
    @PluginMethod public void configure(PluginCall call) {
        try{JSObject config=call.getObject("config",new JSObject());persistSettings(config);RecordingSession.configure(getContext(),config);NativeRecorder r=NativeRecorder.instance;if(r!=null)r.configure(config);call.resolve();}catch(Exception e){call.reject(e.getMessage());}
    }
    @PluginMethod public void listRecordings(PluginCall call) {
        new Thread(()->{
            try {JSObject o=new JSObject();o.put("files",RecordingStore.list(getContext()));call.resolve(o);}
            catch(Exception e){call.reject(e.getMessage());}
        },"Recording-Index").start();
    }
    @PluginMethod public void openRecording(PluginCall call) {
        try {
            String uri=call.getString("uri");
            synchronized(ExportSnapshot.class){boolean known=false;org.json.JSONArray files=RecordingStore.list(getContext());
                for(int n=0;n<files.length();n++)if(files.getJSONObject(n).optString("uri").equals(uri))known=true;
                if(!known)throw new Exception("파일을 찾을 수 없습니다");ExportSnapshot.protectUri(getContext(),uri);
            }
            Intent i=new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri),"audio/mp4");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);getActivity().startActivity(i);call.resolve();
        }catch(Exception e){call.reject("재생 앱을 열지 못했습니다: "+e.getMessage());}
    }
    @PluginMethod public void shareRecording(PluginCall call) {
        try {
            String uri=call.getString("uri");
            synchronized(ExportSnapshot.class){boolean known=false;org.json.JSONArray files=RecordingStore.list(getContext());
                for(int n=0;n<files.length();n++)if(files.getJSONObject(n).optString("uri").equals(uri))known=true;
                if(!known)throw new Exception("파일을 찾을 수 없습니다");ExportSnapshot.protectUri(getContext(),uri);
            }
            Intent i=new Intent(Intent.ACTION_SEND).setType("audio/mp4").putExtra(Intent.EXTRA_STREAM,Uri.parse(uri));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);getActivity().startActivity(Intent.createChooser(i,"녹음 파일 내보내기"));call.resolve();
        }catch(Exception e){call.reject(e.getMessage());}
    }

    @PluginMethod public void acceptUsage(PluginCall call){if(!getContext().getSharedPreferences("usage-notice",0).edit().putBoolean("accepted",true).putLong("acceptedAt",System.currentTimeMillis()).commit()){call.reject("사용 안내 확인 저장 실패");return;}call.resolve();}
    @PluginMethod public void scheduleStatus(PluginCall call){try{call.resolve(new JSObject(RecordingSession.status(getContext()).toString()));}catch(Exception e){call.reject("예약 상태 확인 실패");}}
    @PluginMethod public void allowExactAlarm(PluginCall call){try{if(Build.VERSION.SDK_INT>=31){Intent i=new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:"+getContext().getPackageName()));getActivity().startActivity(i);}call.resolve();}catch(Exception e){call.reject("기기 설정에서 알람 및 리마인더를 허용해 주세요");}}
    @PluginMethod public void requestMic(PluginCall call){if(getPermissionState("microphone")!=PermissionState.GRANTED)requestPermissionForAlias("microphone",call,"afterGuideMic");else{JSObject o=new JSObject();o.put("allowed",true);call.resolve(o);}}
    @PermissionCallback private void afterGuideMic(PluginCall call){JSObject o=new JSObject();o.put("allowed",getPermissionState("microphone")==PermissionState.GRANTED);call.resolve(o);}

    private void persistSettings(JSObject config){
        try{org.json.JSONArray rests=RestSchedule.normalize(config);getContext().getSharedPreferences("recorder-settings",0).edit().putBoolean("reminderOn",config.optBoolean("reminderOn",false)).putBoolean("sleepOn",rests.length()>0).putBoolean("restNotify",config.optBoolean("restNotify",true)).putInt("retentionDays",config.optInt("retentionDays",0)).apply();}catch(Exception invalid){throw new IllegalArgumentException(invalid.getMessage());}
    }
    @PluginMethod public void cleanup(PluginCall call){WindowMerger.request(getContext());RetentionManager.request(getContext());DriveBackup.request(getContext());call.resolve();}
    @PluginMethod public void notificationStatus(PluginCall call){JSObject o=new JSObject();o.put("allowed",androidx.core.app.NotificationManagerCompat.from(getContext()).areNotificationsEnabled());call.resolve(o);}
    @PluginMethod public void allowNotifications(PluginCall call){
        if(Build.VERSION.SDK_INT>=33&&getPermissionState("notifications")!=PermissionState.GRANTED)requestPermissionForAlias("notifications",call,"afterReminderPermission");
        else {Intent i=new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getContext().getPackageName());getActivity().startActivity(i);call.resolve();}
    }
    @PermissionCallback private void afterReminderPermission(PluginCall call){notificationStatus(call);}
    @PluginMethod public void launchExport(PluginCall call){
        String token=getActivity().getIntent().getStringExtra("exportToken");getActivity().getIntent().removeExtra("exportToken");JSObject o=new JSObject();o.put("token",token);call.resolve(o);
    }

}

