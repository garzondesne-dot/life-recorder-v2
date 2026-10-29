package com.lifeblackbox.recorder;
import android.content.*;
import com.getcapacitor.*;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.google.android.gms.auth.api.identity.*;
import com.google.android.gms.common.api.Scope;
import java.util.*;

@CapacitorPlugin(name="DriveBackup")
public class DriveBackupPlugin extends Plugin {
 @PluginMethod public void status(PluginCall call){try{call.resolve(new JSObject(DriveBackup.status(getContext()).toString()));}catch(Exception e){call.reject("백업 상태 읽기 실패");}}
 @PluginMethod public void connect(PluginCall call){
  if(!Boolean.TRUE.equals(call.getBoolean("consent",false))){call.reject("음성·타임로그 업로드 및 보관 기간 공동 적용 동의가 필요합니다");return;}
  getActivity().runOnUiThread(()->((MainActivity)getActivity()).authorizeDrive(call,this));
 }
 void accept(AuthorizationResult auth,PluginCall call){if(auth.getAccessToken()==null){call.reject("Drive 권한을 받지 못했습니다");return;}
  new Thread(()->{try{
   JSONObjectUser user=new JSONObjectUser(new DriveHttp(auth.getAccessToken(),()->{}).about());
   DriveBackup.stop(getContext());android.content.SharedPreferences p=DriveBackup.prefs(getContext());DriveBackup.instance(getContext());
   if(!p.edit().putString("account",user.id).putString("email",user.email).putString("generation",UUID.randomUUID().toString()).putBoolean("enabled",true).putLong("lastSuccess",0).putInt("pending",0).putString("error","").commit())throw new Exception();
   DriveBackup.schedule(getContext());call.resolve(new JSObject(DriveBackup.status(getContext()).toString()));
  }catch(Exception e){call.reject("Google 연결 확인 실패. Drive API 사용 설정과 Android OAuth 패키지명·SHA-1을 확인해 주세요.");}},"Drive-Connect").start();
 }
 static final class JSONObjectUser {final String id,email;JSONObjectUser(org.json.JSONObject o)throws Exception{id=o.getString("permissionId");email=o.getString("emailAddress");if(id.isEmpty()||email.isEmpty())throw new Exception();}}
 @PluginMethod public void economy(PluginCall call){DriveBackup.setEconomy(getContext(),Boolean.TRUE.equals(call.getBoolean("enabled",true)));call.resolve();}
 private final java.util.concurrent.atomic.AtomicBoolean preparingBackup=new java.util.concurrent.atomic.AtomicBoolean();
 @PluginMethod public void sync(PluginCall call){if(!DriveBackup.prefs(getContext()).getBoolean("enabled",false)){call.reject("먼저 Google 계정을 연결해 주세요");return;}if(!preparingBackup.compareAndSet(false,true)){call.reject("백업 자료를 준비 중입니다");return;}
  final String generation=DriveBackup.prefs(getContext()).getString("generation","");
  new Thread(()->{try{NativeRecorder recorder=NativeRecorder.instance;if(recorder!=null)recorder.prepareBackup().get(180,java.util.concurrent.TimeUnit.SECONDS);
   if(!DriveBackup.prefs(getContext()).getBoolean("enabled",false)||!generation.equals(DriveBackup.prefs(getContext()).getString("generation","")))throw new Exception("연결이 변경되었습니다");DriveBackup.manual(getContext());call.resolve();
  }catch(Exception e){call.reject("지금 백업 준비 실패: "+(e.getCause()!=null?e.getCause().getMessage():e.getMessage()));}finally{preparingBackup.set(false);}},"Drive-Manual-Backup").start();
 }
 @PluginMethod public void disconnect(PluginCall call){DriveBackup.stop(getContext());DriveBackup.prefs(getContext()).edit().remove("email").remove("account").putString("error","").apply();call.resolve();}
}
