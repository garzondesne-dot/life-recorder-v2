package com.lifeblackbox.recorder;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import org.json.JSONObject;
final class ExportReminder {
 static final String CHANNEL="daily_export";
 static synchronized void afterSaved(Context c,JSONObject snapshot)throws Exception{
  android.content.SharedPreferences p=c.getSharedPreferences("recorder-settings",0);
  boolean permission=(Build.VERSION.SDK_INT<33||ContextCompat.checkSelfPermission(c,android.Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)&&NotificationManagerCompat.from(c).areNotificationsEnabled();
  String date=snapshot.getString("date");int count=snapshot.getJSONArray("files").length();
  if(!ReminderPolicy.allow(p.getBoolean("reminderOn",false),p.getBoolean("sleepOn",false),permission,true,count,date,p.getString("lastReminderDate","")))return;
  NotificationManager nm=c.getSystemService(NotificationManager.class);
  if(Build.VERSION.SDK_INT>=26){nm.createNotificationChannel(new NotificationChannel(CHANNEL,"하루 기록 내보내기",NotificationManager.IMPORTANCE_DEFAULT));if(nm.getNotificationChannel(CHANNEL).getImportance()==NotificationManager.IMPORTANCE_NONE)return;}
  Intent open=new Intent(c,MainActivity.class).setAction("com.lifeblackbox.recorder.OPEN_EXPORT").putExtra("exportToken",snapshot.getString("token"));
  open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
  PendingIntent action=PendingIntent.getActivity(c,2301,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  Notification n=new NotificationCompat.Builder(c,CHANNEL).setSmallIcon(c.getResources().getIdentifier("ic_stat_wave","drawable",c.getPackageName()))
   .setContentTitle("오늘의 기록이 준비됐어요").setContentText("오디오 "+count+"개와 하루 타임로그를 AI로 보내볼까요?")
   .setOnlyAlertOnce(true).setContentIntent(action).addAction(0,"내보내기 준비",action).setAutoCancel(true).build();
  nm.notify(2301,n);p.edit().putString("lastReminderDate",date).commit();
 }
}
