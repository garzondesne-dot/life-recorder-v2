package com.lifeblackbox.recorder;
import android.app.*;
import android.content.*;
import android.os.Build;
import org.json.*;

/** Persist user intent and diagnostics, never start a new microphone service from an alarm receiver. */
final class RecordingSession {
 static final String PREF="recording-session-v3";
 static SharedPreferences prefs(Context c){return c.getSharedPreferences(PREF,0);}
 static int boot(Context c){return android.provider.Settings.Global.getInt(c.getContentResolver(),android.provider.Settings.Global.BOOT_COUNT,0);}
 static void begin(Context c,JSONObject config){prefs(c).edit().putBoolean("active",true).putBoolean("awaitingRest",false).putInt("boot",boot(c)).putString("config",config.toString()).putString("reason","").commit();}
 static void configure(Context c,JSONObject config){if(prefs(c).getBoolean("active",false))prefs(c).edit().putString("config",config.toString()).commit();}
 static void stop(Context c){prefs(c).edit().putBoolean("active",false).putBoolean("awaitingRest",false).putLong("wakeAt",0).putString("reason","사용자가 녹음을 정지했습니다").commit();cancel(c);}
 static PendingIntent pending(Context c,long at){return PendingIntent.getBroadcast(c,3100,new Intent(c,RestAlarmReceiver.class).setAction("com.lifeblackbox.recorder.REST_END").putExtra("at",at),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
 static void cancel(Context c){AlarmManager am=c.getSystemService(AlarmManager.class);if(am!=null)am.cancel(pending(c,0));}
 static boolean exact(Context c){AlarmManager am=c.getSystemService(AlarmManager.class);return am!=null&&(Build.VERSION.SDK_INT<31||am.canScheduleExactAlarms());}
 static void schedule(Context c,long at){prefs(c).edit().putBoolean("awaitingRest",true).putLong("wakeAt",at).putLong("scheduledAt",System.currentTimeMillis()).putString("reason","").commit();AlarmManager am=c.getSystemService(AlarmManager.class);if(am==null){reason(c,"알람 서비스를 사용할 수 없습니다");return;}
  try{if(exact(c))am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pending(c,at));else{am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pending(c,at));reason(c,"정확한 알람 미허용 · 예약 재개가 지연될 수 있습니다");}}
  catch(SecurityException denied){am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pending(c,at));reason(c,"정확한 알람 권한 변경 · 일반 알람으로 예약했습니다");}
 }
 static void reason(Context c,String reason){prefs(c).edit().putString("reason",reason==null?"":reason).commit();}
 static void resumed(Context c){prefs(c).edit().putBoolean("awaitingRest",false).putLong("resumedAt",System.currentTimeMillis()).putString("reason","").commit();}
 static boolean due(Context c,long now){SharedPreferences p=prefs(c);return p.getBoolean("active",false)&&p.getBoolean("awaitingRest",false)&&p.getInt("boot",-1)==boot(c)&&p.getLong("wakeAt",0)>0&&now>=p.getLong("wakeAt",0);}
 static void fired(Context c,long expected){SharedPreferences p=prefs(c);if(!p.getBoolean("active",false)||!p.getBoolean("awaitingRest",false)||expected!=p.getLong("wakeAt",0)||p.getInt("boot",-1)!=boot(c))return;
  p.edit().putLong("firedAt",System.currentTimeMillis()).commit();NativeRecorder r=NativeRecorder.instance;BlackboxService service=BlackboxService.live;
  if(r!=null&&service!=null){service.alarmWake();r.wakeForSchedule();}else{reason(c,"휴식 중 녹음 프로세스 종료 · 알림을 눌러 재개해 주세요");notifyNeedsResume(c);}
 }
 static void notifyNeedsResume(Context c){try{NotificationManager nm=c.getSystemService(NotificationManager.class);NotificationChannel ch=new NotificationChannel("recording_recovery","녹음 재개 확인",NotificationManager.IMPORTANCE_DEFAULT);nm.createNotificationChannel(ch);PendingIntent pi=PendingIntent.getActivity(c,3101,new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  nm.notify(3101,new androidx.core.app.NotificationCompat.Builder(c,"recording_recovery").setSmallIcon(c.getResources().getIdentifier("ic_stat_wave","drawable",c.getPackageName())).setContentTitle("LifeRecorder · 녹음 재개 확인 필요").setContentText("예약 시간이 지났습니다. 눌러 앱에서 녹음을 재개하세요.").setContentIntent(pi).setAutoCancel(true).build());
 }catch(SecurityException denied){/* The pending state remains visible on next app open. */}}
 static void resumeVisible(android.app.Activity activity){Context c=activity.getApplicationContext();if(NativeRecorder.instance!=null||BlackboxService.running||!due(c,System.currentTimeMillis()))return;
  if(androidx.core.content.ContextCompat.checkSelfPermission(c,android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED){reason(c,"마이크 권한을 확인한 후 녹음을 시작해 주세요");return;}
  try{JSONObject config=new JSONObject(prefs(c).getString("config","{}"));if(RestSchedule.current(RestSchedule.normalize(config),System.currentTimeMillis())[1]>0)return;
   Intent i=new Intent(c,BlackboxService.class).putExtra("config",config.toString()).putExtra("visibleRecovery",true);androidx.core.content.ContextCompat.startForegroundService(activity,i);
  }catch(Exception e){reason(c,"예약 재개 실패 · 녹음 시작 버튼으로 다시 시작해 주세요");}
 }
 static JSONObject status(Context c)throws Exception{SharedPreferences p=prefs(c);return new JSONObject().put("exact",exact(c)).put("active",p.getBoolean("active",false)).put("awaitingRest",p.getBoolean("awaitingRest",false)).put("wakeAt",p.getLong("wakeAt",0)).put("firedAt",p.getLong("firedAt",0)).put("resumedAt",p.getLong("resumedAt",0)).put("reason",p.getString("reason",""));}
}
