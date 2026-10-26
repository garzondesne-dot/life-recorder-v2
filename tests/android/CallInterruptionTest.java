package com.lifeblackbox.recorder;
import android.content.Context;
import android.media.AudioManager;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=29)
public class CallInterruptionTest {
    public static class TestService extends BlackboxService {
        volatile boolean waiting,finished,resting;
        // Test double isolates recorder lifecycle from notification/receiver platform plumbing.
        @Override public void onCreate(){}
        @Override void waitNative(String text){waiting=true;}
        @Override void wakeNative(){}
        @Override void sleepNative(long at){resting=true;}
        @Override void recordingFinished(){finished=true;}
        @Override void recordError(String error){throw new AssertionError(error);}
    }
    TestService service;
    AudioManager audio;
    @Before public void setup(){service=Robolectric.buildService(TestService.class).create().get();audio=(AudioManager)service.getSystemService(Context.AUDIO_SERVICE);}
    @Test public void callModesIncludeHandsetBluetoothAndVoip()throws Exception{
        NativeRecorder recorder=new NativeRecorder(service,new JSONObject());
        java.lang.reflect.Method active=NativeRecorder.class.getDeclaredMethod("callActive");active.setAccessible(true);
        for(int mode:new int[]{AudioManager.MODE_IN_CALL,AudioManager.MODE_IN_COMMUNICATION,AudioManager.MODE_RINGTONE}){
            audio.setMode(mode);assertEquals(true,active.invoke(recorder));
        }
        audio.setMode(AudioManager.MODE_NORMAL);assertEquals(false,active.invoke(recorder));
    }
    @Test public void callWaitPreservesSessionAndManualStopEndsIt()throws Exception{
        audio.setMode(AudioManager.MODE_IN_CALL);
        NativeRecorder recorder=new NativeRecorder(service,new JSONObject());
        recorder.start();
        try{
            long end=System.currentTimeMillis()+3000;
            while(!service.waiting&&System.currentTimeMillis()<end)Thread.sleep(10);
            assertTrue("Call must enter wait instead of ending the service",service.waiting);
            assertFalse(service.finished);
            assertTrue(recorder.status().getBoolean("running"));
            assertTrue(recorder.status().getBoolean("interrupted"));
            assertEquals("통화로 녹음 대기 중",recorder.status().getString("interruptionReason"));
        }finally{
            recorder.stop();
            java.lang.reflect.Field f=NativeRecorder.class.getDeclaredField("thread");f.setAccessible(true);((Thread)f.get(recorder)).join(3000);
        }
        assertTrue("Manual stop during a call must finish",service.finished);
        assertNull(NativeRecorder.instance);
    }
}
