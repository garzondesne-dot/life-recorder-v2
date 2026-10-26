package com.lifeblackbox.recorder;
import android.media.MediaCodec;
import java.io.*;
import java.lang.reflect.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=29)
public class CallWindowTest {
    NativeRecorder recorder;
    Object field(String name)throws Exception{Field f=NativeRecorder.class.getDeclaredField(name);f.setAccessible(true);return f.get(recorder);}
    void set(String name,Object value)throws Exception{Field f=NativeRecorder.class.getDeclaredField(name);f.setAccessible(true);f.set(recorder,value);}
    void invoke(String name,Class<?>[] types,Object... values)throws Exception{Method m=NativeRecorder.class.getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(recorder,values);}
    @Before public void setup()throws Exception{
        CallInterruptionTest.TestService service=Robolectric.buildService(CallInterruptionTest.TestService.class).create().get();
        recorder=new NativeRecorder(service,new JSONObject().put("splitMin",90));
    }
    @After public void release()throws Exception{
        MediaCodec codec=(MediaCodec)field("codec");if(codec!=null){try{codec.stop();}finally{codec.release();}}
        for(String name:new String[]{"out","timeline"}){Closeable stream=(Closeable)field(name);if(stream!=null)stream.close();}
        ((ExecutorService)field("publisher")).shutdownNow();
    }
    @Test public void repeatedCallsKeepOneJournalAndPlaybackClock()throws Exception{
        invoke("ensureFile",new Class<?>[]{});
        Object encoder=field("codec"),journal=field("journal"),stream=field("out"),boundary=field("nextSplit");
        set("samples",32000L);
        for(int i=0;i<3;i++){
            invoke("suspendInput",new Class<?>[]{String.class},"통화로 녹음 대기 중");
            invoke("ensureFile",new Class<?>[]{});
            assertSame("Calls must not create a new file",journal,field("journal"));
            assertSame("Keep AAC state; do not introduce a new encoder delay per call",encoder,field("codec"));
            assertSame(stream,field("out"));
            assertEquals(32000L,field("samples"));
            assertEquals(boundary,field("nextSplit"));
        }
    }
    @Test public void checkpointPreservesWallClockGapInSameTimeline()throws Exception{
        invoke("ensureFile",new Class<?>[]{});
        TimelineSegments spans=(TimelineSegments)field("spans");long t=System.currentTimeMillis();
        spans.accept(0,50,t);
        invoke("suspendInput",new Class<?>[]{String.class},"통화로 녹음 대기 중");
        spans.accept(50,50,t+600000);
        invoke("suspendInput",new Class<?>[]{String.class},"통화로 녹음 대기 중");
        File timeline=new File(((File)field("journal")).getPath()+".timeline");
        java.util.List<String> lines=java.nio.file.Files.readAllLines(timeline.toPath());
        assertEquals(2,lines.size());JSONObject a=new JSONObject(lines.get(0)),b=new JSONObject(lines.get(1));
        assertEquals(a.getLong("to"),b.getLong("from"));
        assertEquals(599950,b.getLong("start")-a.getLong("end"));
    }
    @Test public void exportDuringCallQueuesFinalizationInsteadOfSkippingOpenAudio()throws Exception{
        invoke("ensureFile",new Class<?>[]{});set("interrupted",true);
        CompletableFuture<JSONObject> future=recorder.prepareExport("2026-10-26");
        assertFalse("Audio thread must finalize the current file before snapshot",future.isDone());
        assertNotNull(((AtomicReference<?>)field("exportRequest")).get());
        assertTrue("Concurrent exports remain rejected",recorder.prepareExport("2026-10-26").isCompletedExceptionally());
    }
}
