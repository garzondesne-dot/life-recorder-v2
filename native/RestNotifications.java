package com.lifeblackbox.recorder;
import android.app.*;
import android.content.*;
import androidx.core.app.*;
final class RestNotifications {
 static void send(Context c,String title,String text){
  if(!c.getSharedPreferences("recorder-settings",0).getBoolean("restNotify",true)||!NotificationManagerCompat.from(c).areNotificationsEnabled())return;
  try{NotificationManager nm=c.getSystemService(NotificationManager.class);NotificationChannel channel=new NotificationChannel("rest_transitions","녹음 휴식·재개",NotificationManager.IMPORTANCE_LOW);channel.setName("녹음 휴식·재개");channel.setSound(null,null);channel.enableVibration(false);nm.createNotificationChannel(channel);
   PendingIntent pi=PendingIntent.getActivity(c,2402,new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
   nm.notify(2402,new NotificationCompat.Builder(c,"rest_transitions").setSmallIcon(c.getResources().getIdentifier("ic_stat_wave","drawable",c.getPackageName())).setContentTitle(title).setContentText(text).setContentIntent(pi).setSilent(true).setAutoCancel(true).build());
  }catch(SecurityException denied){/* System permission remains user controlled. */}
 }
}
