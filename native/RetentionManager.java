package com.lifeblackbox.recorder;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Opt-in cleanup. Only indexed, published media owned by this package. Never folder-wide. */
final class RetentionManager {
 private static final AtomicBoolean busy=new AtomicBoolean();
 static boolean supported(int days){return days==2||days==7||days==15||days==30||days==60;}
 static String oldestKeptDay(long now,int days){Calendar c=Calendar.getInstance();c.setTimeInMillis(now);c.add(Calendar.DATE,-days+1);return DailyLog.day(c.getTimeInMillis());}
 static void request(Context context){Context c=context.getApplicationContext();int days=c.getSharedPreferences("recorder-settings",0).getInt("retentionDays",0);if(!supported(days)||!busy.compareAndSet(false,true))return;
  new Thread(()->{try{sweep(c,days,System.currentTimeMillis());}catch(Exception e){android.util.Log.e("LifeRecorder","Cleanup deferred",e);}finally{busy.set(false);}},"Recording-Cleanup").start();
 }
 static synchronized void sweep(Context c,int days,long now)throws Exception{
  if(!supported(days))return;android.content.SharedPreferences p=c.getSharedPreferences("recorder-settings",0);String runKey=DailyLog.day(now)+":"+days;
  if(runKey.equals(p.getString("lastCleanup","")))return;
  Set<String> protectedNames=new HashSet<>();File[] snapshots=new File(c.getCacheDir(),"exports").listFiles();
  if(snapshots!=null)for(File dir:snapshots){File f=new File(dir,"context.json");if(f.exists()&&now-f.lastModified()<24*3600000L)try{JSONArray a=new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8)).getJSONArray("files");for(int i=0;i<a.length();i++)protectedNames.add(a.getJSONObject(i).optString("name"));}catch(Exception unknown){return;}}
  String oldest=oldestKeptDay(now,days);JSONArray all=RecordingStore.list(c);Set<String> removed=new HashSet<>();boolean failed=false;
  for(int i=0;i<all.length();i++){
   if(p.getInt("retentionDays",0)!=days)return;JSONObject row=all.getJSONObject(i);String name=row.optString("name"),uriText=row.optString("uri");long created=row.optLong("created");
   if(created<=0||DailyLog.day(created).compareTo(oldest)>=0||protectedNames.contains(name)||!name.matches("LBB_\\d{8}_\\d{6}.*\\.m4a"))continue;
   File pending=new File(c.getFilesDir(),"pending/"+name.replace("_복구.m4a",".aac").replace(".m4a",".aac"));if(pending.exists())continue;
   Uri uri=Uri.parse(uriText);if(!"content".equals(uri.getScheme())||!"media".equals(uri.getAuthority()))continue;
   long[] bounds=DailyLog.bounds(c,name);if(bounds[1]>0&&DailyLog.day(bounds[1]-1).compareTo(oldest)>=0)continue;
   try(Cursor cur=c.getContentResolver().query(uri,new String[]{"owner_package_name","relative_path","_display_name"},null,null,null)){
    if(cur==null||!cur.moveToFirst())continue;String path=cur.getString(1);
    if(!c.getPackageName().equals(cur.getString(0))||path==null||!path.matches("Documents/LifeRecorder/\\d{4}-\\d{2}-\\d{2}/")||!name.equals(cur.getString(2)))continue;
    if(p.getInt("retentionDays",0)!=days)return;int count=c.getContentResolver().delete(uri,"owner_package_name=? AND _display_name=?",new String[]{c.getPackageName(),name});if(count>0)removed.add(name);else failed=true;
   }catch(Exception denied){failed=true;}
  }
  if(!removed.isEmpty()){DailyLog.removeNames(c,removed);RecordingStore.list(c);RecordingStore.version++;}
  if(!failed)p.edit().putString("lastCleanup",runKey).apply();
 }
}
