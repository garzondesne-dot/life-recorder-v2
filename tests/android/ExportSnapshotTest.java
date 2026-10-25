package com.lifeblackbox.recorder;
import android.content.Context;import org.json.*;import org.junit.*;import org.junit.runner.RunWith;import org.robolectric.*;import org.robolectric.annotation.Config;import java.io.*;import java.nio.charset.StandardCharsets;import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=29)
public class ExportSnapshotTest {
 Context c;long start;String date="2026-10-25";
 @Before public void setup()throws Exception{c=RuntimeEnvironment.getApplication();start=java.time.Instant.parse("2026-10-25T10:00:00Z").toEpochMilli();File dir=new File(c.getFilesDir(),"daily-timeline");if(dir.exists())for(File f:dir.listFiles())f.delete();}
 JSONObject audio(String name,long created)throws Exception{return new JSONObject().put("name",name).put("uri","content://test/"+name).put("size",100).put("created",created);}
 @Test public void frozenTextAndFileSetNeverAcquireNewRecordings()throws Exception{
  JSONObject entry=new JSONObject().put("name","old.m4a").put("created",start).put("spans",new JSONArray().put(new JSONObject().put("start",start).put("end",start+2000).put("from",0).put("to",2000)));
  JSONObject all=new JSONObject().put("old.aac",entry);DailyLog.write(DailyLog.manifest(c,date),all);
  JSONObject snapshot=ExportSnapshot.create(c,date,start+1000,new JSONArray().put(audio("old.m4a",start)).put(audio("future.m4a",start+2000)));
  assertEquals("content",ExportSnapshot.logUri(c,snapshot).getScheme());
  assertEquals(1,snapshot.getJSONArray("files").length());String token=snapshot.getString("token");
  File textFile=new File(ExportSnapshot.dir(c,token),snapshot.getString("logName"));byte[] before=java.nio.file.Files.readAllBytes(textFile.toPath());String text=new String(before,StandardCharsets.UTF_8);
  assertTrue(text.contains("00:00:01.000"));assertFalse(text.contains("00:00:02.000"));
  all.put("future.aac",new JSONObject().put("name","future.m4a").put("created",start+2000).put("spans",new JSONArray()));DailyLog.write(DailyLog.manifest(c,date),all);
  assertArrayEquals(before,java.nio.file.Files.readAllBytes(textFile.toPath()));assertEquals(1,ExportSnapshot.read(c,token).getJSONArray("files").length());
 }
 @Test public void invalidTokenCannotTraversePrivateFiles()throws Exception{try{ExportSnapshot.read(c,"../../recordings-v2.json");fail("accepted traversal");}catch(IOException expected){}}
}
