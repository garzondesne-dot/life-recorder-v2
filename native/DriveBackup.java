package com.lifeblackbox.recorder;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.util.AtomicFile;
import androidx.work.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Device→Drive only. Durable per-account IDs and resumable sessions; retention is independent of local existence. */
final class DriveBackup {
 static final String SCOPE="https://www.googleapis.com/auth/drive.file",PREF="drive-backup-v1",WORK="life-drive-upload",PERIODIC="life-drive-periodic";
 static volatile boolean working;static volatile String progress="";
 static SharedPreferences prefs(Context c){return c.getSharedPreferences(PREF,0);}
 static synchronized String instance(Context c){SharedPreferences p=prefs(c);String id=p.getString("instance","");if(id.isEmpty()){id=UUID.randomUUID().toString();if(!p.edit().putString("instance",id).commit())throw new IllegalStateException("백업 기기 ID 저장 실패");}return id;}
 static void schedule(Context c){if(!prefs(c).getBoolean("enabled",false))return;Constraints net=new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();WorkManager wm=WorkManager.getInstance(c);
  wm.enqueueUniquePeriodicWork(PERIODIC,ExistingPeriodicWorkPolicy.KEEP,new PeriodicWorkRequest.Builder(DriveBackupWorker.Tick.class,15,TimeUnit.MINUTES).setConstraints(net).build());request(c);
 }
 static void request(Context c){if(!prefs(c).getBoolean("enabled",false))return;WorkManager.getInstance(c).enqueueUniqueWork(WORK,ExistingWorkPolicy.KEEP,new OneTimeWorkRequest.Builder(DriveBackupWorker.class).setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build());}
 static void stop(Context c){prefs(c).edit().putBoolean("enabled",false).putString("generation",UUID.randomUUID().toString()).apply();WorkManager.getInstance(c).cancelUniqueWork(WORK);WorkManager.getInstance(c).cancelUniqueWork(PERIODIC);}
 static JSONObject status(Context c)throws Exception{SharedPreferences p=prefs(c);JSONObject o=new JSONObject().put("enabled",p.getBoolean("enabled",false)).put("email",p.getString("email","")).put("error",p.getString("error","")).put("lastSuccess",p.getLong("lastSuccess",0)).put("working",working).put("progress",progress).put("path",p.getString("path","LifeRecorder/기기별/날짜별"));
  if(!p.getString("account","").isEmpty()){JSONObject ledger=read(c,p.getString("account",""));int done=0;Iterator<String> keys=ledger.keys();while(keys.hasNext()){JSONObject row=ledger.getJSONObject(keys.next());if(!row.optBoolean("deleted")&&!row.optString("done").isEmpty())done++;}o.put("backedUp",done);}return o;
 }
 static String hash(String value)throws Exception{return WindowMerger.hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
 static AtomicFile file(Context c,String account)throws Exception{File dir=new File(c.getFilesDir(),"drive-backup");dir.mkdirs();return new AtomicFile(new File(dir,hash(account)+".json"));}
 static synchronized JSONObject read(Context c,String account)throws Exception{AtomicFile f=file(c,account);try{return new JSONObject(new String(f.readFully(),StandardCharsets.UTF_8));}catch(FileNotFoundException e){if(f.getBaseFile().exists()||new File(f.getBaseFile().getPath()+".bak").exists())throw e;return new JSONObject();}}
 static synchronized void write(Context c,String account,JSONObject ledger)throws Exception{DailyLog.write(file(c,account),ledger);}
 static int days(Context c){return c.getSharedPreferences("recorder-settings",0).getInt("retentionDays",0);}
 static boolean expired(Context c,String day){return DrivePolicy.expired(day,days(c),System.currentTimeMillis());}
 static JSONObject tags(String instance,String key,String day)throws Exception{return new JSONObject().put("app","LifeRecorder").put("instance",instance).put("key",key).put("day",day);}
 static final class Candidate {
  String key,name,date,lastDay,signature,uri,mime;byte[] text;
 }
 /** Only canonical, published, owned rows. Never traverse user-picked external folders. */
 static List<Candidate> candidates(Context c)throws Exception{synchronized(ExportSnapshot.class){
  JSONArray rows=RecordingStore.list(c);Map<String,JSONObject> summary=DailyLog.summaries(c);Map<String,Integer> counts=new HashMap<>();for(int i=0;i<rows.length();i++){String k=DrivePolicy.key(rows.getJSONObject(i).getString("name"));counts.put(k,counts.getOrDefault(k,0)+1);}
  List<Candidate> result=new ArrayList<>();Set<String> dates=new TreeSet<>();
  for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);String name=row.getString("name");if(!name.matches("LBB_\\d{8}_\\d{6}.*\\.m4a"))continue;String key=DrivePolicy.key(name);if(counts.get(key)>1)continue;String d=name.substring(4,8)+"-"+name.substring(8,10)+"-"+name.substring(10,12);Uri uri=Uri.parse(row.getString("uri"));
   try{WindowMerger.assertOwned(c,uri,name,d,false);}catch(Exception unknown){continue;}
   try(Cursor cur=c.getContentResolver().query(uri,new String[]{"is_pending"},null,null,null)){if(cur==null||!cur.moveToFirst()||cur.getInt(0)!=0)continue;}
   Candidate item=new Candidate();item.key=key;item.name=name;item.date=d;item.lastDay=DrivePolicy.lastDay(row,summary.get(name));item.signature=hash(row.getString("uri")+":"+row.optLong("size"));item.uri=uri.toString();item.mime="audio/mp4";
   if(expired(c,item.lastDay))continue;result.add(item);JSONObject s=summary.get(name);if(s!=null){JSONArray ds=s.getJSONArray("days");for(int j=0;j<ds.length();j++)dates.add(ds.getString(j));}
  }
  for(String d:dates){if(expired(c,d))continue;Candidate item=new Candidate();item.key="txt:"+d;item.name="LifeRecorder_"+d+"_timelog.txt";item.date=d;item.lastDay=d;item.text=DailyLog.render(c,d).getBytes(StandardCharsets.UTF_8);item.signature=hash(new String(item.text,StandardCharsets.UTF_8));item.mime="text/plain";result.add(item);}
  result.sort(Comparator.comparing(x->x.date));return result;
 }}
 static File freeze(Context c,Candidate item)throws Exception{File dir=new File(c.getCacheDir(),"drive-upload");dir.mkdirs();File target=new File(dir,hash(item.key)+".bin");
  synchronized(ExportSnapshot.class){if(expired(c,item.lastDay))throw new IOException("보관 기간 만료 · 업로드 취소");try(OutputStream out=new FileOutputStream(target)){if(item.text!=null)out.write(item.text);else{
    boolean current=false;JSONArray rows=RecordingStore.list(c);for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(item.uri.equals(row.optString("uri"))&&item.signature.equals(hash(row.optString("uri")+":"+row.optLong("size"))))current=true;}if(!current)throw new IOException("병합으로 파일이 변경됨 · 다시 준비합니다");
    WindowMerger.assertOwned(c,Uri.parse(item.uri),item.name,item.date,false);try(InputStream in=c.getContentResolver().openInputStream(Uri.parse(item.uri))){if(in==null)throw new FileNotFoundException();byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
   }}}
  return target;
 }
 static String md5(File file)throws Exception{MessageDigest md=MessageDigest.getInstance("MD5");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)md.update(b,0,n);}return WindowMerger.hex(md.digest());}
 static void pruneCache(Context c){File[] files=new File(c.getCacheDir(),"drive-upload").listFiles();if(files!=null)for(File f:files)if(f.getName().matches("[a-f0-9]{64}\\.bin"))f.delete();}
 static boolean hasWork(Context c,String account)throws Exception{JSONObject ledger=read(c,account);Iterator<String> it=ledger.keys();while(it.hasNext()){JSONObject row=ledger.getJSONObject(it.next());if(!row.optBoolean("deleted")&&!row.optString("id").isEmpty()&&expired(c,row.optString("lastDay")))return true;}for(Candidate item:candidates(c)){JSONObject row=ledger.optJSONObject(item.key);if(row==null||DrivePolicy.needsUpload(row,item.signature))return true;}return false;}
 static boolean run(Context c,DriveHttp api,String account,Runnable guard)throws Exception{
  String inst=instance(c);JSONObject ledger=read(c,account);long deadline=android.os.SystemClock.elapsedRealtime()+6*60000L;boolean more=false;
  // Ledger rows are retained after local deletion, so offline expiry never becomes an upload.
  List<String> keys=new ArrayList<>();Iterator<String> it=ledger.keys();while(it.hasNext())keys.add(it.next());
  for(String key:keys){guard.run();JSONObject row=ledger.getJSONObject(key);if(row.optBoolean("deleted")||!expired(c,row.optString("lastDay")))continue;String id=row.optString("id");if(id.isEmpty())continue;
   progress="보관 기간이 지난 Drive 파일 정리 중";JSONObject remote=api.get(id);guard.run();if(!expired(c,row.optString("lastDay")))continue;
   if(remote!=null){if(!DrivePolicy.owned(remote,inst,key))throw new IOException("Drive 파일 소유 표식 불일치 · 삭제 보류");api.call("DELETE",DriveHttp.API+"files/"+DriveHttp.enc(id),null);}
   row.put("deleted",true).remove("session");write(c,account,ledger);if(android.os.SystemClock.elapsedRealtime()>deadline)return true;
  }
  List<Candidate> candidates=candidates(c);String root=null,device=null;Map<String,String> folders=new HashMap<>();int handled=0;
  for(Candidate item:candidates){guard.run();if(expired(c,item.lastDay))continue;JSONObject row=ledger.optJSONObject(item.key);if(row==null){row=new JSONObject();ledger.put(item.key,row);}if(!DrivePolicy.needsUpload(row,item.signature))continue;
   if(android.os.SystemClock.elapsedRealtime()>deadline||handled++>=24){more=true;break;}
   if(root==null){root=api.folder("LifeRecorder",null,tags("shared","root","") );String label=Build.MODEL+"-"+inst.substring(0,8);device=api.folder(label,root,tags(inst,"device:"+inst,""));prefs(c).edit().putString("path","LifeRecorder/"+label+"/날짜별").apply();}
   String parent=folders.get(item.date);if(parent==null){parent=api.folder(item.date,device,tags(inst,"date:"+inst+":"+item.date,item.date));folders.put(item.date,parent);}
   row.put("lastDay",item.lastDay).put("name",item.name);String id=row.optString("id");if(id.isEmpty()||row.optBoolean("deleted")){id=api.newId();row.put("id",id).put("deleted",false).put("done","").remove("session");write(c,account,ledger);}
   JSONObject remote=api.get(id);if(remote!=null&&!DrivePolicy.owned(remote,inst,item.key))throw new IOException("Drive 파일 소유 표식 불일치 · 갱신 보류");
   // Respect remote trash; create a fresh ID rather than restoring a user-deleted file.
   if(remote!=null&&remote.optBoolean("trashed")){id=api.newId();row.put("id",id).put("done","").remove("session");write(c,account,ledger);remote=null;}
   File frozen=freeze(c,item);try{String checksum=md5(frozen);long total=frozen.length();if(total<=0)throw new IOException("빈 녹음은 업로드하지 않습니다");
    if(remote!=null&&checksum.equals(remote.optString("md5Checksum"))&&item.name.equals(remote.optString("name"))){row.put("done",item.signature).remove("session");write(c,account,ledger);continue;}
    progress=item.name+" 업로드 중";
    String session=row.optString("session");if(!item.signature.equals(row.optString("pending"))||!checksum.equals(row.optString("md5"))){session="";row.remove("session");}
    long offset=0;
    if(!session.isEmpty()){DriveHttp.Reply probe=api.request("PUT",session,null,null,0,0,Collections.singletonMap("Content-Range","bytes */"+total));if(probe.code==200||probe.code==201)offset=total;else if(probe.code==308)offset=DriveHttp.acknowledged(probe.range);else if(probe.code==404||probe.code==410)session="";else throw new DriveHttp.Failure(probe.code);}
    if(session.isEmpty()){
     JSONObject meta=new JSONObject().put("name",item.name).put("appProperties",tags(inst,item.key,item.lastDay));if(remote==null)meta.put("id",id).put("parents",new JSONArray().put(parent));
     Map<String,String> headers=new HashMap<>();headers.put("X-Upload-Content-Type",item.mime);headers.put("X-Upload-Content-Length",""+total);
     DriveHttp.Reply start=api.request(remote==null?"POST":"PATCH",DriveHttp.UPLOAD+"files"+(remote==null?"":"/"+DriveHttp.enc(id))+"?uploadType=resumable&fields=id",meta,null,0,0,headers);
     if(start.code==409)return true;if(start.code<200||start.code>=300)throw new DriveHttp.Failure(start.code);session=start.location;if(session==null)throw new IOException("Drive 업로드 세션 없음");
     row.put("session",session).put("pending",item.signature).put("md5",checksum);write(c,account,ledger);offset=0;
    }
    while(offset<total){guard.run();if(expired(c,item.lastDay))throw new IOException("보관 기간 만료 · 업로드 중단");if(android.os.SystemClock.elapsedRealtime()>deadline)return true;
     int count=(int)Math.min(1024*1024,total-offset);Map<String,String> h=new HashMap<>();h.put("Content-Type",item.mime);h.put("Content-Range","bytes "+offset+"-"+(offset+count-1)+"/"+total);DriveHttp.Reply chunk=api.request("PUT",session,null,frozen,offset,count,h);
     if(chunk.code==200||chunk.code==201)offset=total;else if(chunk.code==308){long next=DriveHttp.acknowledged(chunk.range);if(next<=offset||next>total)throw new IOException("Drive 진행 위치 확인 실패");offset=next;}else if(chunk.code==404||chunk.code==410){row.remove("session");write(c,account,ledger);return true;}else throw new DriveHttp.Failure(chunk.code);
    }
    guard.run();JSONObject check=api.get(id);if(check==null||!DrivePolicy.owned(check,inst,item.key)||!checksum.equals(check.optString("md5Checksum"))||total!=check.optLong("size"))throw new IOException("Drive 업로드 검증 실패 · 재시도 대기");
    row.put("done",item.signature).remove("session");write(c,account,ledger);
   }finally{frozen.delete();}
  }
  return more;
 }
}
