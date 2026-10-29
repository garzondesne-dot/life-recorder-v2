package com.lifeblackbox.recorder;
import android.app.*;
import android.content.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=29)
public class RecordingSessionTest {
 Context c;
 @Before public void setup(){c=RuntimeEnvironment.getApplication();RecordingSession.prefs(c).edit().clear().commit();NativeRecorder.instance=null;BlackboxService.live=null;BlackboxService.running=false;}
 @Test public void explicitAlarmAndManualStopCannotReviveOldSession()throws Exception{
  long at=System.currentTimeMillis()+60000;RecordingSession.begin(c,new JSONObject().put("restSchedules",new JSONArray()));RecordingSession.schedule(c,at);
  android.content.Intent i=Shadows.shadowOf(RecordingSession.pending(c,at)).getSavedIntent();assertEquals(RestAlarmReceiver.class.getName(),i.getComponent().getClassName());
  assertFalse(RecordingSession.due(c,at-1));assertTrue(RecordingSession.due(c,at));RecordingSession.stop(c);RecordingSession.fired(c,at);assertFalse(RecordingSession.due(c,at+1000));assertEquals(0,RecordingSession.prefs(c).getLong("firedAt",0));
 }
 @Test public void processLossLeavesActionablePendingStateInsteadOfFalseRecording()throws Exception{
  long at=System.currentTimeMillis()-1000;RecordingSession.begin(c,new JSONObject());RecordingSession.schedule(c,at);RecordingSession.fired(c,at);
  assertTrue(RecordingSession.prefs(c).getBoolean("awaitingRest",false));assertTrue(RecordingSession.prefs(c).getString("reason","").contains("프로세스 종료"));assertFalse(BlackboxService.running);assertNull(NativeRecorder.instance);
  RecordingSession.resumed(c);assertFalse(RecordingSession.prefs(c).getBoolean("awaitingRest",true));assertTrue(RecordingSession.prefs(c).getLong("resumedAt",0)>0);
 }
 @Test public void staleAlarmAndRebootCannotAutomaticallyResume()throws Exception{
  long at=System.currentTimeMillis()-1000;RecordingSession.begin(c,new JSONObject());RecordingSession.schedule(c,at);RecordingSession.fired(c,at-10000);assertEquals(0,RecordingSession.prefs(c).getLong("firedAt",0));
  RecordingSession.prefs(c).edit().putInt("boot",RecordingSession.boot(c)+1).commit();assertFalse(RecordingSession.due(c,at+10000));
 }
 @Test public void savedConfigIsUpdatedOnlyForActiveSession()throws Exception{
  RecordingSession.configure(c,new JSONObject().put("bitrate",16000));assertEquals("",RecordingSession.prefs(c).getString("config",""));RecordingSession.begin(c,new JSONObject().put("bitrate",24000));RecordingSession.configure(c,new JSONObject().put("bitrate",32000));assertEquals(32000,new JSONObject(RecordingSession.prefs(c).getString("config","{}")).getInt("bitrate"));
 }
}
