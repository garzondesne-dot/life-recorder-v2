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
  finishLogCleanup(c,p);if(runKey.equals(p.getString("lastCleanup","")))return;
  Set<String> protectedNames=new HashSet<>();File[] snapshots=new File(c.getCacheDir(),"exports").listFiles();
  if(snapshots!=null)for(File dir:snapshots){if(!dir.isDirectory()||!dir.getName().matches("[a-f0-9-]{36}"))continue;File f=new File(dir,"context.json");
   if(f.exists()&&now-f.lastModified()>=24*3600000L){File[] cacheFiles=dir.listFiles();if(cacheFiles!=null)for(File cache:cacheFiles)if(cache.isFile()&&(cache.getName().equals("context.json")||cache.getName().matches("LifeRecorder_\\d{4}-\\d{2}-\\d{2}_timelog\\.txt")))cache.delete();dir.delete();continue;}
if(f.exists()&&now-f.lastModified()<24*3600000L)try{JSONArray a=new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8)).getJSONArray("files");for(int i=0;i<a.length();i++)protectedNames.add(a.getJSONObject(i).optString("name"));}catch(Exception unknown){return;}}
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
    if(p.getInt("retentionDays",0)!=days)return;JSONObject receipts=new JSONObject(p.getString("cleanupReceipts","{}"));receipts.put(name,new JSONObject().put("uri",uriText).put("days",DailyLog.datesFor(c,name)));if(!p.edit().putString("cleanupReceipts",receipts.toString()).commit())throw new IOException("정리 기록 저장 실패");int count=c.getContentResolver().delete(uri,"owner_package_name=? AND _display_name=?",new String[]{c.getPackageName(),name});if(count>0)removed.add(name);else failed=true;
   }catch(Exception denied){failed=true;}
  }
  if(!removed.isEmpty()){finishLogCleanup(c,p);RecordingStore.list(c);RecordingStore.version++;}
  if(!failed)p.edit().putString("lastCleanup",runKey).apply();
 }
 static void finishLogCleanup(Context c,android.content.SharedPreferences p)throws Exception{
  JSONObject receipts=new JSONObject(p.getString("cleanupReceipts","{}"));Set<String> removed=new HashSet<>(),dates=new HashSet<>();List<String> done=new ArrayList<>();Iterator<String> it=receipts.keys();
  while(it.hasNext()){String name=it.next();JSONObject record=receipts.getJSONObject(name);try(Cursor cur=c.getContentResolver().query(Uri.parse(record.getString("uri")),new String[]{"_id"},null,null,null)){
   if(cur==null)continue;if(!cur.moveToFirst()){removed.add(name);JSONArray a=record.getJSONArray("days");for(int i=0;i<a.length();i++)dates.add(a.getString(i));}done.add(name);
  }catch(SecurityException unavailable){/* Retry once provider access returns. */}}
  if(!removed.isEmpty()){DailyLog.removeNames(c,removed);for(String date:dates){android.util.AtomicFile file=DailyLog.manifest(c,date);if(file.getBaseFile().exists()||new File(file.getBaseFile().getPath()+".bak").exists())DailyLog.publish(c,date);}}
  for(String name:done)receipts.remove(name);if(!p.edit().putString("cleanupReceipts",receipts.toString()).commit())throw new IOException("정리 완료 기록 저장 실패");
 }

}
