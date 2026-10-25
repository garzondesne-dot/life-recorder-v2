package com.lifeblackbox.recorder;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=29)
public class DailyLogTest {
    Context context;Provider provider;TimeZone previous;
    public static class Provider extends ContentProvider {
        static File directory;long sequence;Map<Long,ContentValues> rows=new HashMap<>();
        public boolean onCreate(){return true;}
        public Cursor query(Uri u,String[] p,String where,String[] args,String sort){
            MatrixCursor result=new MatrixCursor(new String[]{"_id"});
            for(Map.Entry<Long,ContentValues> entry:rows.entrySet()){
                ContentValues v=entry.getValue();if(args==null||(args[0].equals(v.getAsString("_display_name"))&&args[1].equals(v.getAsString("relative_path"))))result.addRow(new Object[]{entry.getKey()});
            }return result;
        }
        public String getType(Uri u){return "text/plain";}
        public Uri insert(Uri u,ContentValues values){long id=++sequence;rows.put(id,new ContentValues(values));return ContentUris.withAppendedId(u,id);}
        public int update(Uri u,ContentValues v,String s,String[] a){throw new AssertionError("unexpected update");}
        public int delete(Uri u,String s,String[] a){throw new AssertionError("no deletion");}
        public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{return ParcelFileDescriptor.open(new File(directory,uri.getLastPathSegment()+".txt"),ParcelFileDescriptor.parseMode(mode));}
    }
    @Before public void setup()throws Exception{
        context=RuntimeEnvironment.getApplication();previous=TimeZone.getDefault();TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        File data=new File(context.getFilesDir(),"daily-timeline");if(data.exists())for(File f:data.listFiles())f.delete();
        Provider.directory=new File(context.getCacheDir(),"daily-log-tests");Provider.directory.mkdirs();
        provider=new Provider();android.content.pm.ProviderInfo info=new android.content.pm.ProviderInfo();
        info.authority="media";info.packageName=context.getPackageName();info.applicationInfo=context.getApplicationInfo();info.exported=true;
        provider.attachInfo(context,info);ShadowContentResolver.registerProviderInternal("media",provider);
    }
    @After public void teardown(){TimeZone.setDefault(previous);}
    File timeline(String name,long start,long end)throws Exception{
        File file=new File(context.getCacheDir(),name);
        try(FileOutputStream out=new FileOutputStream(file.getPath()+".timeline")){
            out.write(("{\"from\":0,\"to\":"+(end-start)+",\"start\":"+start+",\"end\":"+end+"}\n").getBytes(StandardCharsets.UTF_8));
        }return file;
    }
    @Test public void repeatedExportUpdatesOneDailyTxtAndRetainsAllRecordings()throws Exception{
        long start=java.time.Instant.parse("2026-10-25T10:00:00Z").toEpochMilli();
        File first=timeline("one.aac",start,start+1000);DailyLog.record(context,first,"one.m4a",start,1000);
        DailyLog.record(context,first,"one.m4a",start,1000);
        DailyLog.record(context,timeline("two.aac",start+2000,start+3000),"two.m4a",start+2000,1000);
        DailyLog.publish(context,"2026-10-25");
        assertEquals(1,provider.rows.size());assertEquals(2,DailyLog.read(context,"2026-10-25").length());
        String text=DailyLog.render(context,"2026-10-25");assertTrue(text.contains("one.m4a"));assertTrue(text.contains("two.m4a"));
    }
    @Test public void midnightSplitsSpansWithoutDuplicatingAudio()throws Exception{
        long start=java.time.Instant.parse("2026-10-25T23:59:59.950Z").toEpochMilli();
        DailyLog.record(context,timeline("midnight.aac",start,start+100),"midnight.m4a",start,100);
        assertEquals(2,provider.rows.size());
        assertTrue(DailyLog.render(context,"2026-10-25").contains("00:00:00.050"));
        assertTrue(DailyLog.render(context,"2026-10-26").contains("00:00:00.050 ~ 00:00:00.100"));
    }
    @Test public void atomicBackupIsRestoredInsteadOfDiscarded()throws Exception{
        long start=java.time.Instant.parse("2026-10-25T10:00:00Z").toEpochMilli();
        DailyLog.record(context,timeline("backup.aac",start,start+50),"backup.m4a",start,50);
        File file=DailyLog.manifest(context,"2026-10-25").getBaseFile();assertTrue(file.renameTo(new File(file.getPath()+".bak")));
        assertEquals(1,DailyLog.read(context,"2026-10-25").length());
    }
}
