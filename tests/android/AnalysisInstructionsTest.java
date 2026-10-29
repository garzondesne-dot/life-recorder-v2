package com.lifeblackbox.recorder;
import android.content.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=29)
public class AnalysisInstructionsTest {
 Context c;
 @Before public void setup(){c=RuntimeEnvironment.getApplication();c.getSharedPreferences("analysis-profile-v3",0).edit().clear().commit();}
 @Test public void preferencesAppearInDailyTextInOrderWithoutInventedTranscript()throws Exception{
  AnalysisInstructions.save(c,new JSONObject().put("priorities",new JSONArray("[\"todo\",\"speech\",\"todo\",\"invalid\"]")).put("length","short").put("extra","부탁하는 표현"));String text=DailyLog.render(c,"2026-10-30");
  assertTrue(text.indexOf("1순위: 할 일·약속")<text.indexOf("2순위: 언어습관·말하기"));assertTrue(text.contains("핵심만 짧게"));assertTrue(text.contains("전사문이 아닙니다"));assertTrue(text.contains("부탁하는 표현"));assertFalse(text.contains("서환"));assertFalse(text.contains("3순위"));
 }
 @Test public void perSharePromptNeverMutatesExistingFrozenText()throws Exception{
  String token=java.util.UUID.randomUUID().toString(),name="LifeRecorder_2026-10-30_timelog.txt";File dir=ExportSnapshot.dir(c,token);dir.mkdirs();File original=new File(dir,name);java.nio.file.Files.write(original.toPath(),"original timeline".getBytes(StandardCharsets.UTF_8));
  JSONObject snapshot=new JSONObject().put("token",token).put("logName",name).put("date","2026-10-30").put("files",new JSONArray());
  android.net.Uri a=ExportSnapshot.logWithPrompt(c,snapshot,"request A"),b=ExportSnapshot.logWithPrompt(c,snapshot,"request B");assertNotEquals(a,b);assertEquals("original timeline",new String(java.nio.file.Files.readAllBytes(original.toPath()),StandardCharsets.UTF_8));
  int found=0;for(File folder:ExportSnapshot.root(c).listFiles()){File context=new File(folder,"context.json");if(!context.exists())continue;File log=new File(folder,name);if(!log.exists())continue;String text=new String(java.nio.file.Files.readAllBytes(log.toPath()),StandardCharsets.UTF_8);if(text.contains("request A")||text.contains("request B")){found++;assertTrue(text.contains("original timeline"));assertFalse(text.contains("request A")&&text.contains("request B"));}}
  assertEquals(2,found);
 }
}
