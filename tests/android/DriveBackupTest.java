package com.lifeblackbox.recorder;
import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=29)
public class DriveBackupTest {
 Context c;Media media;Fake api;long stamp;String day,name;Uri uri;
 public static class Media extends ContentProvider {
  Map<Long,ContentValues> rows=new LinkedHashMap<>();Map<Long,File> files=new HashMap<>();long seq=1;
  public boolean onCreate(){return true;}
  public Cursor query(Uri u,String[] cols,String selection,String[] args,String order){MatrixCursor out=new MatrixCursor(cols);long id=0;try{id=ContentUris.parseId(u);}catch(Exception ignored){}for(Map.Entry<Long,ContentValues> e:rows.entrySet()){if(id>0&&id!=e.getKey())continue;ContentValues v=e.getValue();if(id==0&&args!=null&&args.length>0&&!args[0].equals(v.getAsString("_display_name")))continue;Object[] values=new Object[cols.length];for(int i=0;i<cols.length;i++)values[i]=cols[i].equals("_id")?e.getKey():cols[i].equals("owner_package_name")?getContext().getPackageName():cols[i].equals("is_pending")?0:v.get(cols[i]);out.addRow(values);}return out;}
  public Uri insert(Uri u,ContentValues v){long id=seq++;rows.put(id,new ContentValues(v));try{File f=File.createTempFile("media",".bin",getContext().getCacheDir());files.put(id,f);}catch(Exception e){throw new IllegalStateException(e);}return ContentUris.withAppendedId(Uri.parse("content://media/external/file"),id);}
  public String getType(Uri u){return "audio/mp4";}
  public int update(Uri u,ContentValues v,String s,String[] a){return 0;}
  public int delete(Uri u,String s,String[] a){long id=ContentUris.parseId(u);File f=files.remove(id);if(f!=null)f.delete();return rows.remove(id)==null?0:1;}
  public ParcelFileDescriptor openFile(Uri u,String mode)throws FileNotFoundException{return ParcelFileDescriptor.open(files.get(ContentUris.parseId(u)),ParcelFileDescriptor.parseMode(mode));}
 }
 static final class Fake extends DriveHttp {
  Map<String,JSONObject> remote=new HashMap<>(),meta=new HashMap<>();Map<String,ByteArrayOutputStream> data=new HashMap<>();int ids,uploads,chunks,deletes;boolean loseCompletion,interruptSecond;List<String> deleted=new ArrayList<>();
  Fake(){super("not-a-real-token",()->{});}
  @Override String folder(String n,String p,JSONObject t){return "folder";}
  @Override String newId(){return "id"+(++ids);}
  @Override JSONObject get(String id)throws Exception{JSONObject o=remote.get(id);return o==null?null:new JSONObject(o.toString());}
  @Override Reply call(String method,String url,JSONObject body)throws Exception {if(method.equals("DELETE")){String id=url.substring(url.lastIndexOf('/')+1);remote.remove(id);deleted.add(id);deletes++;Reply r=new Reply();r.code=204;r.body="";return r;}throw new AssertionError(method+url);}
  @Override Reply request(String method,String url,JSONObject json,File file,long offset,int length,Map<String,String> headers)throws Exception{
   Reply r=new Reply();r.body="";
   if(json!=null){uploads++;String id=json.optString("id");if(id.isEmpty())id=url.substring(url.indexOf("files/")+6,url.indexOf('?'));String session="https://www.googleapis.com/upload/drive/v3/files?session="+id;JSONObject m=new JSONObject(json.toString()).put("id",id);meta.put(session,m);data.put(session,new ByteArrayOutputStream());r.location=session;r.code=200;return r;}
   ByteArrayOutputStream bytes=data.get(url);assertNotNull(bytes);
   if(file==null){r.code=308;if(bytes.size()>0)r.range="bytes=0-"+(bytes.size()-1);return r;}
   chunks++;if(interruptSecond&&offset>0){interruptSecond=false;throw new IOException("network interruption");}assertEquals(bytes.size(),offset);try(RandomAccessFile in=new RandomAccessFile(file,"r")){in.seek(offset);byte[] b=new byte[length];in.readFully(b);bytes.write(b);}
   long total=Long.parseLong(headers.get("Content-Range").split("/")[1]);if(bytes.size()==total){JSONObject m=meta.get(url);File temp=File.createTempFile("md5","bin");Files.write(temp.toPath(),bytes.toByteArray());m.put("md5Checksum",DriveBackup.md5(temp)).put("size",temp.length());temp.delete();remote.put(m.getString("id"),m);r.code=200;if(loseCompletion){loseCompletion=false;throw new IOException("response lost after commit");}}else{r.code=308;r.range="bytes=0-"+(bytes.size()-1);}return r;
  }
 }
 @Before public void setup()throws Exception{c=RuntimeEnvironment.getApplication();for(File f:c.getFilesDir().listFiles())delete(f);for(String pref:new String[]{DriveBackup.PREF,"recorder-settings"})c.getSharedPreferences(pref,0).edit().clear().commit();media=new Media();android.content.pm.ProviderInfo info=new android.content.pm.ProviderInfo();info.authority="media";info.packageName=c.getPackageName();info.applicationInfo=c.getApplicationInfo();info.exported=true;media.attachInfo(c,info);ShadowContentResolver.registerProviderInternal("media",media);api=new Fake();stamp=System.currentTimeMillis()-60000;day=DailyLog.day(stamp);addAudio(128);}
 static void delete(File f){if(f.isDirectory())for(File k:f.listFiles())delete(k);f.delete();}
 void addAudio(int count)throws Exception{name="LBB_"+new java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.US).format(new Date(stamp))+"_W"+RecordingWindow.start(stamp)+".m4a";ContentValues v=new ContentValues();v.put("_display_name",name);v.put("relative_path","Documents/LifeRecorder/"+day+"/");uri=c.getContentResolver().insert(Uri.parse("content://media/external/file"),v);byte[] b=new byte[count];new Random(4).nextBytes(b);Files.write(media.files.get(ContentUris.parseId(uri)).toPath(),b);RecordingStore.add(c,name,uri.toString(),count,1000,false,stamp);}
 @Test public void lostResponseUsesDurableIdWithoutDuplicate()throws Exception{api.loseCompletion=true;try{DriveBackup.run(c,api,"a",()->{});fail();}catch(IOException expected){}assertEquals(1,api.remote.size());DriveBackup.run(c,api,"a",()->{});assertEquals(1,api.ids);assertEquals(1,api.uploads);assertEquals(1,api.remote.size());DriveBackup.run(c,api,"a",()->{});assertEquals(1,api.uploads);}
 @Test public void interruptedUploadResumesFromServerOffset()throws Exception{c.getContentResolver().delete(uri,null,null);RecordingStore.list(c);addAudio(2500000);api.interruptSecond=true;try{DriveBackup.run(c,api,"a",()->{});fail();}catch(IOException expected){}assertEquals(1,api.uploads);DriveBackup.run(c,api,"a",()->{});assertEquals(1,api.uploads);assertEquals(2500000,api.remote.values().iterator().next().getLong("size"));}
 @Test public void mergedRevisionUpdatesSameRemoteId()throws Exception{DriveBackup.run(c,api,"a",()->{});String id=api.remote.keySet().iterator().next();String old=uri.toString();addAudio(256);Set<String> source=new HashSet<>();source.add(old);JSONObject replacement=RecordingStore.list(c).getJSONObject(1);RecordingStore.replace(c,source,replacement);DriveBackup.run(c,api,"a",()->{});assertEquals(1,api.ids);assertEquals(1,api.remote.size());assertEquals(256,api.remote.get(id).getLong("size"));}
 @Test public void remoteExpirySurvivesLocalDeletionAndNeverUploadsExpired()throws Exception{DriveBackup.run(c,api,"a",()->{});String key=DrivePolicy.key(name);JSONObject ledger=DriveBackup.read(c,"a");Calendar cal=Calendar.getInstance();cal.add(Calendar.DATE,-4);ledger.getJSONObject(key).put("lastDay",DailyLog.day(cal.getTimeInMillis()));DriveBackup.write(c,"a",ledger);c.getContentResolver().delete(uri,null,null);RecordingStore.list(c);c.getSharedPreferences("recorder-settings",0).edit().putInt("retentionDays",2).commit();DriveBackup.run(c,api,"a",()->{});assertEquals(1,api.deletes);assertEquals(0,api.remote.size());stamp=cal.getTimeInMillis();day=DailyLog.day(stamp);addAudio(500);DriveBackup.run(c,api,"a",()->{});assertEquals(1,api.uploads);}
 @Test public void unrelatedRemoteIsNeverDeleted()throws Exception{DriveBackup.run(c,api,"a",()->{});JSONObject ledger=DriveBackup.read(c,"a");JSONObject entry=ledger.getJSONObject(DrivePolicy.key(name));entry.put("lastDay","2000-01-01");DriveBackup.write(c,"a",ledger);api.remote.get(entry.getString("id")).getJSONObject("appProperties").put("instance","someone-else");c.getSharedPreferences("recorder-settings",0).edit().putInt("retentionDays",2).commit();try{DriveBackup.run(c,api,"a",()->{});fail();}catch(IOException expected){}assertEquals(0,api.deletes);}
 @Test public void accountLedgersAreIsolatedAndGuardStopsNetwork()throws Exception{DriveBackup.run(c,api,"a",()->{});assertEquals(0,DriveBackup.read(c,"b").length());int uploaded=api.uploads;try{DriveBackup.run(c,api,"b",()->{throw new IllegalStateException("disconnect");});fail();}catch(IllegalStateException expected){}assertEquals(uploaded,api.uploads);}
 @Test public void retentionBoundaryAndNoDeleteMode()throws Exception{long now=System.currentTimeMillis();String first=RetentionManager.oldestKeptDay(now,2);assertFalse(DrivePolicy.expired(first,2,now));assertFalse(DrivePolicy.expired("2000-01-01",0,now));assertTrue(DrivePolicy.expired("2000-01-01",2,now));}
 @Test public void dailyTextUpdatesInPlaceAndUnmergedFragmentsNeverOverwrite()throws Exception{
  JSONObject entry=new JSONObject().put("name",name).put("created",stamp).put("duration",1000).put("spans",new JSONArray());
  DailyLog.write(DailyLog.manifest(c,day),new JSONObject().put("audio",entry));
  DriveBackup.run(c,api,"a",()->{});assertEquals(2,api.remote.size());assertEquals(2,api.ids);
  JSONObject ledger=DriveBackup.read(c,"a");String textId=ledger.getJSONObject("txt:"+day).getString("id");
  entry.put("spans",new JSONArray().put(new JSONObject().put("start",stamp).put("end",stamp+1000).put("from",0).put("to",1000)));
  DailyLog.write(DailyLog.manifest(c,day),new JSONObject().put("audio",entry));DriveBackup.run(c,api,"a",()->{});
  assertEquals(2,api.remote.size());assertEquals(2,api.ids);assertEquals(textId,DriveBackup.read(c,"a").getJSONObject("txt:"+day).getString("id"));
  int uploads=api.uploads;addAudio(600);DriveBackup.run(c,api,"a",()->{});assertEquals(uploads,api.uploads);
 }

 @Test public void newPublicationCannotLookFullyBackedUpBeforeWorkerChecks()throws Exception{
  DriveBackup.prefs(c).edit().putLong("requestedVersion",2).putLong("checkedVersion",1).putLong("lastSuccess",123).commit();assertTrue(DriveBackup.status(c).getBoolean("needsCheck"));
  DriveBackup.prefs(c).edit().putLong("checkedVersion",2).commit();assertFalse(DriveBackup.status(c).getBoolean("needsCheck"));
 }

 @Test public void economyAndManualRequestsUseDifferentConstraints()throws Exception{
  androidx.work.Constraints saved=DriveBackup.uploadConstraints(true,false),immediate=DriveBackup.uploadConstraints(false,false),manual=DriveBackup.uploadConstraints(true,true);
  assertEquals(androidx.work.NetworkType.UNMETERED,saved.getRequiredNetworkType());assertTrue(saved.requiresBatteryNotLow());
  assertEquals(androidx.work.NetworkType.CONNECTED,immediate.getRequiredNetworkType());assertFalse(immediate.requiresBatteryNotLow());
  assertEquals(androidx.work.NetworkType.CONNECTED,manual.getRequiredNetworkType());assertFalse(manual.requiresBatteryNotLow());assertEquals(60,DriveBackup.intervalMinutes(true));assertEquals(15,DriveBackup.intervalMinutes(false));assertTrue(DriveBackup.economy(c));
 }
 @Test public void cleanupJobNeverUploadsNewAudioEvenWithPendingLocalFiles()throws Exception{assertFalse(DriveBackup.hasWork(c,"a",true));DriveBackup.run(c,api,"a",()->{},true);assertEquals(0,api.uploads);}

}
