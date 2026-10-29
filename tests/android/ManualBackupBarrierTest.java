package com.lifeblackbox.recorder;
import android.content.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.io.*;
import java.lang.reflect.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=29)
public class ManualBackupBarrierTest {
 @Test public void backupBarrierDoesNotCreateShareSnapshotOrLease()throws Exception{
  Context c=RuntimeEnvironment.getApplication();for(File f:c.getFilesDir().listFiles())remove(f);File exports=new File(c.getCacheDir(),"exports");remove(exports);c.getSharedPreferences("share-leases",0).edit().clear().commit();
  BlackboxService service=Robolectric.buildService(BlackboxService.class).get();NativeRecorder recorder=new NativeRecorder(service,new JSONObject());Field sleeping=NativeRecorder.class.getDeclaredField("sleeping");sleeping.setAccessible(true);sleeping.setBoolean(recorder,true);
  CompletableFuture<JSONObject> done=recorder.prepareBackup();Field pending=NativeRecorder.class.getDeclaredField("exportRequest");pending.setAccessible(true);Object request=((java.util.concurrent.atomic.AtomicReference<?>)pending.get(recorder)).getAndSet(null);assertNotNull(request);
  Method queue=NativeRecorder.class.getDeclaredMethod("queueSnapshot",request.getClass(),long.class,File.class);queue.setAccessible(true);queue.invoke(recorder,request,1234L,null);JSONObject result=done.get(15,TimeUnit.SECONDS);assertEquals(1234,result.getLong("cutoff"));assertFalse(result.has("token"));assertTrue(ExportSnapshot.protectedUris(c,System.currentTimeMillis()).isEmpty());assertEquals(0,ExportSnapshot.root(c).listFiles().length);
  Field pub=NativeRecorder.class.getDeclaredField("publisher");pub.setAccessible(true);((ExecutorService)pub.get(recorder)).shutdownNow();
 }
 static void remove(File f){if(!f.exists())return;if(f.isDirectory())for(File k:f.listFiles())remove(k);f.delete();}
}
