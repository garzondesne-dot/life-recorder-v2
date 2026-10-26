package com.lifeblackbox.recorder;
import android.content.*;import android.database.*;import android.net.Uri;import org.junit.*;import org.junit.runner.RunWith;import org.robolectric.*;import org.robolectric.annotation.Config;import org.robolectric.shadows.ShadowContentResolver;import java.io.*;import java.util.*;import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=29)
public class RetentionManagerTest {
 Context c;Provider provider;long now;TimeZone old;
 static class Row {String name,owner,path;Row(String n,String o,String p){name=n;owner=o;path=p;}}
 public static class Provider extends ContentProvider {
  Map<String,Row> rows=new HashMap<>();int deletes,logDeletes;
  public boolean onCreate(){return true;}
  public Cursor query(Uri u,String[] cols,String s,String[] args,String order){MatrixCursor a=new MatrixCursor(cols);Row r=rows.get(u.toString());if(r!=null){Object[] vals=new Object[cols.length];for(int i=0;i<cols.length;i++)vals[i]=cols[i].equals("_id")?1:cols[i].equals("owner_package_name")?r.owner:cols[i].equals("relative_path")?r.path:r.name;a.addRow(vals);}return a;}
  public int delete(Uri u,String s,String[] args){if(s!=null&&s.contains("relative_path")){assertTrue(s.contains("owner_package_name"));assertEquals(RuntimeEnvironment.getApplication().getPackageName(),args[2]);logDeletes++;return 1;}Row r=rows.get(u.toString());if(r==null)return 0;assertNotNull(s);assertTrue(s.contains("owner_package_name"));assertEquals(r.owner,args[0]);rows.remove(u.toString());deletes++;return 1;}
  public String getType(Uri u){return "audio/mp4";}public Uri insert(Uri u,ContentValues v){throw new AssertionError();}public int update(Uri u,ContentValues v,String s,String[] a){throw new AssertionError();}
 }
 @Before public void setup()throws Exception{old=TimeZone.getDefault();TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));c=RuntimeEnvironment.getApplication();now=java.time.ZonedDateTime.parse("2026-10-26T13:00:00+09:00").toInstant().toEpochMilli();new android.util.AtomicFile(new File(c.getFilesDir(),"recordings-v2.json")).delete();c.getSharedPreferences("recorder-settings",0).edit().clear().putInt("retentionDays",2).commit();provider=new Provider();ShadowContentResolver.registerProviderInternal("media",provider);}
 @After public void restore(){TimeZone.setDefault(old);}
 void add(int id,int days,String owner,String path)throws Exception{String name="LBB_20261020_120000_"+id+".m4a",uri="content://media/external/file/"+id;provider.rows.put(uri,new Row(name,owner,path));RecordingStore.add(c,name,uri,1000,1000,false,now-days*86400000L);}
 @Test public void retainsTodayAndYesterdayDeletesOnlyOwnedPublishedOlderMedia()throws Exception{
  String pkg=c.getPackageName(),path="Documents/LifeRecorder/2026-10-20/";add(1,6,pkg,path);add(2,1,pkg,path);add(3,6,"other.package",path);add(4,6,pkg,"Documents/Other/");RetentionManager.sweep(c,2,now);assertEquals(1,provider.deletes);assertEquals(3,RecordingStore.list(c).length());
 }
 @Test public void removesLastDailyLogAndCompletesDurableReceipt()throws Exception{
  String name="LBB_20261020_120000_1.m4a",date="2026-10-20";add(1,6,c.getPackageName(),"Documents/LifeRecorder/2026-10-20/");
  org.json.JSONObject manifest=new org.json.JSONObject().put("old.aac",new org.json.JSONObject().put("name",name).put("created",now-6*86400000L).put("spans",new org.json.JSONArray()));DailyLog.write(DailyLog.manifest(c,date),manifest);
  RetentionManager.sweep(c,2,now);assertEquals(1,provider.deletes);assertEquals(1,provider.logDeletes);assertFalse(DailyLog.manifest(c,date).getBaseFile().exists());assertEquals("{}",c.getSharedPreferences("recorder-settings",0).getString("cleanupReceipts",""));
 }
 @Test public void resumesLogCleanupAfterAudioWasDeleted()throws Exception{
  String name="LBB_20261020_120000_1.m4a",date="2026-10-20";
  DailyLog.write(DailyLog.manifest(c,date),new org.json.JSONObject().put("old",new org.json.JSONObject().put("name",name).put("spans",new org.json.JSONArray())));
  org.json.JSONObject receipt=new org.json.JSONObject().put(name,new org.json.JSONObject().put("uri","content://media/external/file/99").put("days",new org.json.JSONArray().put(date)));c.getSharedPreferences("recorder-settings",0).edit().putString("cleanupReceipts",receipt.toString()).commit();
  RetentionManager.finishLogCleanup(c,c.getSharedPreferences("recorder-settings",0));assertEquals(1,provider.logDeletes);assertFalse(DailyLog.manifest(c,date).getBaseFile().exists());
 }
 @Test public void cleanupOffNeverDeletes()throws Exception{add(1,6,c.getPackageName(),"Documents/LifeRecorder/2026-10-20/");RetentionManager.sweep(c,0,now);assertEquals(0,provider.deletes);}
 @Test public void calendarRetentionAcrossMonthBoundary(){assertEquals("2026-10-25",RetentionManager.oldestKeptDay(now,2));assertEquals("2026-09-27",RetentionManager.oldestKeptDay(now,30));}
}
