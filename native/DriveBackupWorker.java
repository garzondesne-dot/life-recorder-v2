package com.lifeblackbox.recorder;
import android.accounts.Account;
import android.content.*;
import androidx.annotation.NonNull;
import androidx.work.*;
import com.google.android.gms.auth.api.identity.*;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Tasks;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Network-constrained WorkManager worker; never starts microphone or opens consent in background. */
public class DriveBackupWorker extends Worker {
 public DriveBackupWorker(Context c,WorkerParameters p){super(c,p);}
 @NonNull @Override public Result doWork(){Context c=getApplicationContext();SharedPreferences p=DriveBackup.prefs(c);String account=p.getString("account",""),generation=p.getString("generation","");if(!p.getBoolean("enabled",false)||account.isEmpty())return Result.success();
  Runnable guard=()->{if(isStopped()||!p.getBoolean("enabled",false)||!account.equals(p.getString("account",""))||!generation.equals(p.getString("generation","")))throw new IllegalStateException("Drive 백업 중단");};boolean deletesOnly=getInputData().getBoolean("deletesOnly",false),manual=getInputData().getBoolean("manual",false);String token=null;long requested=p.getLong("requestedVersion",0);
  synchronized(DriveBackupWorker.class){DriveBackup.working=true;try{
   guard.run();if(!deletesOnly){WindowMerger.reconcile(c);if(RetentionManager.supported(DriveBackup.days(c)))RetentionManager.sweep(c,DriveBackup.days(c),System.currentTimeMillis());}if(!DriveBackup.hasWork(c,account,deletesOnly)){guard.run();if(!deletesOnly){SharedPreferences.Editor edit=p.edit().putLong("checkedVersion",requested);if(manual)edit.putBoolean("manualPending",false);edit.apply();}return Result.success();}DriveBackup.progress="Google 연결 확인 중";
   AuthorizationResult auth=Tasks.await(Identity.getAuthorizationClient(c).authorize(AuthorizationRequest.builder().setAccount(new Account(p.getString("email",""),"com.google")).setRequestedScopes(Collections.singletonList(new Scope(DriveBackup.SCOPE))).build()),30,TimeUnit.SECONDS);
   if(auth.hasResolution()||auth.getAccessToken()==null){p.edit().putString("error","Google 권한 확인 필요 · 설정에서 다시 연결해 주세요").putBoolean("manualPending",false).apply();return Result.success();}
   token=auth.getAccessToken();DriveHttp api=new DriveHttp(token,guard);if(!account.equals(api.about().getString("permissionId")))throw new IllegalStateException("Google 계정이 달라 백업을 중단했습니다");
   // Local reconciliation is shared with export; network I/O runs outside its lock.
   boolean more=DriveBackup.run(c,api,account,guard,deletesOnly);guard.run();more=DriveBackup.hasWork(c,account,deletesOnly)||more;if(!deletesOnly){SharedPreferences.Editor edit=p.edit().putString("error","").putLong("lastSuccess",System.currentTimeMillis()).putLong("checkedVersion",requested);if(manual&&!more)edit.putBoolean("manualPending",false);edit.apply();}return more?Result.retry():Result.success();
  }catch(Exception e){if(isStopped()||!generation.equals(p.getString("generation","")))return Result.success();
   String message=e instanceof DriveHttp.Failure?e.getMessage():"백업 대기 · 네트워크 또는 Google 연결을 확인해 주세요";p.edit().putString("error",message).apply();
   if(e instanceof DriveHttp.Failure&&((DriveHttp.Failure)e).code==401&&token!=null)try{Tasks.await(Identity.getAuthorizationClient(c).clearToken(ClearTokenRequest.builder().setToken(token).build()),10,TimeUnit.SECONDS);}catch(Exception ignored){}
   return Result.retry();
  }finally{DriveBackup.pruneCache(c);DriveBackup.working=false;DriveBackup.progress="";}}
 }
 public static class Tick extends Worker {
  public Tick(Context c,WorkerParameters p){super(c,p);}
  @NonNull @Override public Result doWork(){DriveBackup.requestCleanup(getApplicationContext());DriveBackup.request(getApplicationContext());return Result.success();}
 }
}
