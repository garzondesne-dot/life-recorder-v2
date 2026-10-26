package com.lifeblackbox.recorder;
import android.content.*;
import android.database.*;
import android.net.Uri;
import android.util.AtomicFile;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=29)
public class WindowMaintenanceTest {
 Context c;Provider provider;
 public static class Provider extends ContentProvider {
  String owner,path="Documents/LifeRecorder/2026-10-26/";Map<Long,String> names=new HashMap<>();long next=10;int deletes;
  public boolean onCreate(){return true;}
  public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){
   String[] columns=projection==null?new String[]{"_id"}:projection;MatrixCursor out=new MatrixCursor(columns);long id=0;try{id=ContentUris.parseId(uri);}catch(Exception ignored){}
   if(id>0){if(names.containsKey(id))add(out,columns,id,names.get(id));return out;}
   for(Map.Entry<Long,String> e:names.entrySet()){if(args!=null&&args.length>0&&!args[0].equals(e.getValue()))continue;add(out,columns,e.getKey(),e.getValue());}return out;
  }
  void add(MatrixCursor out,String[] columns,long id,String name){Object[] row=new Object[columns.length];for(int i=0;i<columns.length;i++)row[i]=columns[i].equals("owner_package_name")?owner:columns[i].equals("relative_path")?path:columns[i].equals("_display_name")?name:columns[i].equals("is_pending")?0:id;out.addRow(row);}
  public String getType(Uri uri){return "audio/mp4";}
  public Uri insert(Uri uri,ContentValues values){long id=next++;names.put(id,values.getAsString("_display_name"));return ContentUris.withAppendedId(Uri.parse("content://media/external/file"),id);}
  public int delete(Uri uri,String where,String[] args){long id=ContentUris.parseId(uri);if(names.remove(id)!=null){deletes++;return 1;}return 0;}
  public int update(Uri uri,ContentValues values,String where,String[] args){return 1;}
  @Override public android.os.ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{try{return android.os.ParcelFileDescriptor.open(File.createTempFile("log",".txt",getContext().getCacheDir()),android.os.ParcelFileDescriptor.MODE_READ_WRITE);}catch(IOException e){throw new FileNotFoundException(e.toString());}}
 }
 @Before public void setup()throws Exception{c=RuntimeEnvironment.getApplication();new AtomicFile(new File(c.getFilesDir(),"recordings-v2.json")).delete();provider=new Provider();provider.owner=c.getPackageName();android.content.pm.ProviderInfo info=new android.content.pm.ProviderInfo();info.authority="media";provider.attachInfo(c,info);ShadowContentResolver.registerProviderInternal("media",provider);}
 JSONObject span(long from,long to,long start)throws Exception{return new JSONObject().put("from",from).put("to",to).put("start",start).put("end",start+to-from);}
 @Test public void fixedWindowsAreSharedAcrossRestartAndMidnight(){TimeZone old=TimeZone.getDefault();try{TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));Calendar a=Calendar.getInstance();a.set(2026,9,26,7,0,0);a.set(Calendar.MILLISECOND,0);long t=a.getTimeInMillis();assertEquals(6,calendarHour(RecordingWindow.start(t)));assertEquals(8,calendarHour(RecordingWindow.end(t)));assertEquals(RecordingWindow.start(t),RecordingWindow.start(t+35*60000));a.set(2026,9,26,23,59,0);assertEquals(22,calendarHour(RecordingWindow.start(a.getTimeInMillis())));assertEquals(0,calendarHour(RecordingWindow.end(a.getTimeInMillis())));}finally{TimeZone.setDefault(old);}}
 int calendarHour(long t){Calendar c=Calendar.getInstance();c.setTimeInMillis(t);return c.get(Calendar.HOUR_OF_DAY);}
 @Test public void lateRecoveredAudioRepositionsExistingTimelineWithoutInventingGap()throws Exception{
  JSONArray old=new JSONArray().put(span(0,128,1000)).put(span(128,256,9000)),late=new JSONArray().put(span(0,64,5000)),out=new JSONArray();
  assertTrue(WindowMerger.wallAt(old,64)<WindowMerger.wallAt(late,0));assertTrue(WindowMerger.wallAt(late,0)<WindowMerger.wallAt(old,128));
  WindowMerger.mapPacket(out,old,0,64,0);WindowMerger.mapPacket(out,old,64,128,64);WindowMerger.mapPacket(out,late,0,64,128);WindowMerger.mapPacket(out,old,128,192,192);WindowMerger.mapPacket(out,old,192,256,256);
  assertEquals(3,out.length());assertEquals(128,out.getJSONObject(1).getLong("from"));assertEquals(5000,out.getJSONObject(1).getLong("start"));assertEquals(192,out.getJSONObject(2).getLong("from"));assertEquals(9000,out.getJSONObject(2).getLong("start"));assertEquals(320,out.getJSONObject(2).getLong("to"));
 }
 @Test public void commitIsReplayableAndPreservesConcurrentRecording()throws Exception{
  String date="2026-10-26",a="LBB_20261026_100000_000_W1792976400000.m4a",b="LBB_20261026_110000_000_W1792976400000.m4a",merged="LBB_20261026_100000_000_W1792976400000_Mtest.m4a";
  provider.names.put(1L,a);provider.names.put(2L,b);provider.names.put(3L,merged);provider.names.put(4L,"concurrent.m4a");
  RecordingStore.add(c,a,"content://media/external/file/1",10,64,false,1000);RecordingStore.add(c,b,"content://media/external/file/2",10,64,false,2000);RecordingStore.add(c,"concurrent.m4a","content://media/external/file/4",10,64,false,3000);
  JSONObject daily=new JSONObject().put("a",new JSONObject().put("name",a).put("spans",new JSONArray().put(span(0,64,1000)))).put("b",new JSONObject().put("name",b).put("spans",new JSONArray().put(span(0,64,2000))));DailyLog.write(DailyLog.manifest(c,date),daily);
  JSONObject state=new JSONObject().put("sources",new JSONArray().put(new JSONObject().put("name",a).put("uri","content://media/external/file/1")).put(new JSONObject().put("name",b).put("uri","content://media/external/file/2"))).put("name",merged).put("date",date).put("size",20).put("duration",128).put("created",1000).put("window",1792976400000L).put("entry",new JSONObject().put("name",merged).put("created",1000).put("spans",new JSONArray().put(span(0,64,1000)).put(span(64,128,2000))));
  DailyLog.replaceMerged(c,date,new HashSet<>(Arrays.asList(a,b)),state.getJSONObject("entry"));
  WindowMerger.commit(c,state,"content://media/external/file/3");WindowMerger.commit(c,state,"content://media/external/file/3");assertEquals(2,RecordingStore.list(c).length());assertEquals(1,DailyLog.read(c,date).length());assertEquals(0,provider.deletes);
  assertFalse(WindowMerger.cleanSources(c,state,Collections.emptySet()));state.put("done",true).put("uri","content://media/external/file/3");assertFalse(WindowMerger.cleanSources(c,state,Collections.singleton("content://media/external/file/1")));assertTrue(provider.names.containsKey(1L));assertFalse(provider.names.containsKey(2L));assertTrue(WindowMerger.cleanSources(c,state,Collections.emptySet()));assertEquals(2,provider.deletes);assertTrue(provider.names.containsKey(3L));assertTrue(provider.names.containsKey(4L));
 }
 @Test public void summaryFindsBackupOnlyTimelineAfterInterruptedWrite()throws Exception{String date="2026-10-26";JSONObject entries=new JSONObject().put("a",new JSONObject().put("name","old.m4a").put("spans",new JSONArray().put(span(0,64,1000))));DailyLog.write(DailyLog.manifest(c,date),entries);File base=DailyLog.manifest(c,date).getBaseFile();assertTrue(base.renameTo(new File(base.getPath()+".bak")));assertEquals(1000,DailyLog.summaries(c).get("old.m4a").getLong("wallStart"));}
 @Test public void expectedMicContentionDoesNotExhaustRecoveryRetries()throws Exception{
  CallInterruptionTest.TestService service=Robolectric.buildService(CallInterruptionTest.TestService.class).create().get();NativeRecorder recorder=new NativeRecorder(service,new JSONObject());
  try{Class<?> error=Class.forName(NativeRecorder.class.getName()+"$InputUnavailable");java.lang.reflect.Constructor<?> constructor=error.getDeclaredConstructor(String.class,boolean.class);constructor.setAccessible(true);java.lang.reflect.Method defer=NativeRecorder.class.getDeclaredMethod("deferInputRecovery",error);defer.setAccessible(true);
   for(int i=0;i<20;i++)defer.invoke(recorder,constructor.newInstance("camera busy",true));java.lang.reflect.Field retries=NativeRecorder.class.getDeclaredField("inputRetries");retries.setAccessible(true);assertEquals(0,retries.getInt(recorder));
   for(int i=0;i<9;i++)defer.invoke(recorder,constructor.newInstance("device failed",false));try{defer.invoke(recorder,constructor.newInstance("device failed",false));fail("real input faults must remain bounded");}catch(java.lang.reflect.InvocationTargetException expected){assertTrue(expected.getCause() instanceof IOException);}
  }finally{java.lang.reflect.Field publisher=NativeRecorder.class.getDeclaredField("publisher");publisher.setAccessible(true);((java.util.concurrent.ExecutorService)publisher.get(recorder)).shutdownNow();}
 }
 @Test public void playbackLeaseProtectsAnOlderRevision()throws Exception{ExportSnapshot.protectUri(c,"content://media/external/file/1");assertTrue(ExportSnapshot.protectedUris(c,System.currentTimeMillis()).contains("content://media/external/file/1"));}
 @Test public void oldShareCachesExpireEvenWhenRetentionIsDisabled()throws Exception{
  c.getSharedPreferences("recorder-settings",0).edit().putInt("retentionDays",0).commit();File folder=ExportSnapshot.dir(c,"00000000-0000-0000-0000-000000000001");folder.mkdirs();File context=new File(folder,"context.json"),txt=new File(folder,"LifeRecorder_2026-10-26_timelog.txt");java.nio.file.Files.write(context.toPath(),"{}".getBytes(StandardCharsets.UTF_8));java.nio.file.Files.write(txt.toPath(),"log".getBytes(StandardCharsets.UTF_8));long now=System.currentTimeMillis();assertTrue(context.setLastModified(now-25*3600000L));ExportSnapshot.prune(c,now);assertFalse(context.exists());assertFalse(txt.exists());assertFalse(folder.exists());
 }
}
