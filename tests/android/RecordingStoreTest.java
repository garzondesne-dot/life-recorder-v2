package com.lifeblackbox.recorder;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.provider.MediaStore;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=29)
public class RecordingStoreTest {
    private Context context;
    private Provider provider;
    public static class Provider extends ContentProvider {
        boolean missing, unavailable, nullCursor;
        int deletes, writes;
        public boolean onCreate(){return true;}
        public Cursor query(Uri u,String[] p,String s,String[] a,String order){
            if(unavailable)throw new SecurityException("temporary permission failure");
            if(nullCursor)return null;
            MatrixCursor cursor=new MatrixCursor(new String[]{MediaStore.MediaColumns._ID});
            if(!missing)cursor.addRow(new Object[]{1});
            return cursor;
        }
        public String getType(Uri u){return "audio/mp4";}
        public Uri insert(Uri u,ContentValues v){writes++;throw new AssertionError("audio insert");}
        public int update(Uri u,ContentValues v,String s,String[] a){writes++;throw new AssertionError("audio update");}
        public int delete(Uri u,String s,String[] a){deletes++;throw new AssertionError("audio delete");}
    }
    @Before public void setup()throws Exception{
        context=RuntimeEnvironment.getApplication();
        new android.util.AtomicFile(new java.io.File(context.getFilesDir(),"recordings-v2.json")).delete();
        provider=new Provider();ShadowContentResolver.registerProviderInternal("recording-test",provider);
        RecordingStore.add(context,"existing.m4a","content://recording-test/1",123,456,false,1000);
    }
    @Test public void missingFileIsPrunedAndStaysPruned()throws Exception{
        provider.missing=true;
        assertEquals(0,RecordingStore.list(context).length());
        provider.missing=false;
        assertEquals(0,RecordingStore.list(context).length());
        assertEquals(0,provider.deletes);assertEquals(0,provider.writes);
    }
    @Test public void unavailableProviderOrPermissionDoesNotPrune()throws Exception{
        provider.unavailable=true;assertEquals(1,RecordingStore.list(context).length());
        provider.unavailable=false;provider.nullCursor=true;assertEquals(1,RecordingStore.list(context).length());
        provider.nullCursor=false;assertEquals(1,RecordingStore.list(context).length());
    }
    @Test public void interruptedAtomicIndexRestoresBackup()throws Exception{
        java.io.File base=new java.io.File(context.getFilesDir(),"recordings-v2.json");
        assertTrue(base.renameTo(new java.io.File(base.getPath()+".bak")));
        assertEquals(1,RecordingStore.list(context).length());
    }
    @Test public void indexingSameUriTwiceIsIdempotent()throws Exception{
        RecordingStore.add(context,"existing.m4a","content://recording-test/1",123,456,true,1000);
        assertEquals(1,RecordingStore.list(context).length());
    }
}

