package com.lifeblackbox.recorder;

import android.content.*;
import android.database.Cursor;
import android.media.*;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import static org.junit.Assert.*;

/** Runs only in a disposable emulator; real Android codecs, muxer and MediaStore. */
@RunWith(AndroidJUnit4.class)
public class AudioMergeIntegrationTest {
 Context c;
 @Before public void setup(){assertEquals("true",InstrumentationRegistry.getArguments().getString("disposableEmulator"));c=InstrumentationRegistry.getInstrumentation().getTargetContext();}
 boolean exists(String value){try(Cursor cur=c.getContentResolver().query(Uri.parse(value),new String[]{"_id"},null,null,null)){return cur!=null&&cur.moveToFirst();}}
 File journal(long wall,double hz)throws Exception{
  File dir=new File(c.getFilesDir(),"pending");dir.mkdirs();String stamp=new SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.US).format(new Date(wall));File file=new File(dir,"LBB_"+stamp+"_W"+RecordingWindow.start(wall)+".aac");
  MediaCodec codec=MediaCodec.createEncoderByType("audio/mp4a-latm");MediaFormat format=MediaFormat.createAudioFormat("audio/mp4a-latm",16000,1);format.setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC);format.setInteger(MediaFormat.KEY_BIT_RATE,24000);codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);codec.start();
  int samples=0;boolean inputDone=false,outputDone=false;long deadline=android.os.SystemClock.elapsedRealtime()+15000;MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
  try(FileOutputStream out=new FileOutputStream(file)){
   while(!outputDone){if(android.os.SystemClock.elapsedRealtime()>deadline)throw new IOException("AAC encode timeout");
    if(!inputDone){int i=codec.dequeueInputBuffer(1000);if(i>=0){ByteBuffer b=codec.getInputBuffer(i).order(ByteOrder.LITTLE_ENDIAN);b.clear();int count=Math.min(800,Math.min(b.remaining()/2,16000-samples));for(int j=0;j<count;j++)b.putShort((short)(10000*Math.sin(2*Math.PI*hz*(samples+j)/16000)));boolean end=samples+count==16000;codec.queueInputBuffer(i,0,count*2,samples*1000000L/16000,end?MediaCodec.BUFFER_FLAG_END_OF_STREAM:0);samples+=count;inputDone=end;}}
    int i=codec.dequeueOutputBuffer(info,1000);if(i>=0){if(info.size>0&&(info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0){ByteBuffer b=codec.getOutputBuffer(i);b.position(info.offset);b.limit(info.offset+info.size);int len=info.size+7;out.write(new byte[]{(byte)255,(byte)241,(byte)96,(byte)(64|(len>>11)),(byte)(len>>3),(byte)(((len&7)<<5)|31),(byte)252});byte[] bytes=new byte[info.size];b.get(bytes);out.write(bytes);}outputDone=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;codec.releaseOutputBuffer(i,false);}
   }out.getFD().sync();
  }finally{codec.stop();codec.release();}
  JSONObject span=new JSONObject().put("from",0).put("to",1000).put("start",wall).put("end",wall+1000);try(FileOutputStream out=new FileOutputStream(file.getPath()+".timeline")){out.write((span+"\n").getBytes(StandardCharsets.UTF_8));out.getFD().sync();}return file;
 }
 long decodedSamples(String value)throws Exception{
  MediaExtractor extractor=new MediaExtractor();MediaCodec decoder=null;try{extractor.setDataSource(c,Uri.parse(value),null);extractor.selectTrack(0);MediaFormat format=extractor.getTrackFormat(0);decoder=MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));decoder.configure(format,null,null,0);decoder.start();boolean inDone=false,outDone=false;long samples=0,energy=0,deadline=android.os.SystemClock.elapsedRealtime()+15000;MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
   while(!outDone){if(android.os.SystemClock.elapsedRealtime()>deadline)throw new IOException("AAC decode timeout");if(!inDone){int i=decoder.dequeueInputBuffer(1000);if(i>=0){ByteBuffer b=decoder.getInputBuffer(i);b.clear();int n=extractor.readSampleData(b,0);if(n<0){decoder.queueInputBuffer(i,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inDone=true;}else{decoder.queueInputBuffer(i,0,n,extractor.getSampleTime(),0);extractor.advance();}}}
    int i=decoder.dequeueOutputBuffer(info,1000);if(i>=0){ByteBuffer b=decoder.getOutputBuffer(i).order(ByteOrder.LITTLE_ENDIAN);b.position(info.offset);b.limit(info.offset+info.size);while(b.remaining()>=2){short v=b.getShort();energy+=(long)v*v;samples++;}outDone=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;decoder.releaseOutputBuffer(i,false);}}
   assertTrue("Decoded signal must contain real audio",energy>1000000);return samples;
  }finally{extractor.release();if(decoder!=null){decoder.stop();decoder.release();}}
 }
 @Test public void actualAacMergeDecodeLateRecoveryAndFrozenShare()throws Exception{
  long window=RecordingWindow.start(System.currentTimeMillis()),first=window+60000,late=window+120000,last=window+180000;String date=DailyLog.day(first);
  NativeRecorder.publish(c,journal(first,300),false);NativeRecorder.publish(c,journal(last,700),false);JSONArray before=RecordingStore.list(c);assertEquals(2,before.length());long expected=0;for(int i=0;i<before.length();i++)expected+=decodedSamples(before.getJSONObject(i).getString("uri"));
  WindowMerger.reconcile(c);assertEquals("",WindowMerger.lastError);JSONArray rows=RecordingStore.list(c);assertEquals(1,rows.length());String original=rows.getJSONObject(0).getString("uri");long decoded=decodedSamples(original);assertTrue("Merged samples="+decoded+", separately decoded="+expected,decoded>=expected-2048&&decoded<=expected+4096);
  JSONObject snapshot=ExportSnapshot.create(c,date,window+7200000L,rows);File text=new File(ExportSnapshot.dir(c,snapshot.getString("token")),snapshot.getString("logName"));byte[] frozen=java.nio.file.Files.readAllBytes(text.toPath());
  NativeRecorder.publish(c,journal(late,500),true);JSONArray extra=RecordingStore.list(c);for(int i=0;i<extra.length();i++)if(!original.equals(extra.getJSONObject(i).getString("uri")))expected+=decodedSamples(extra.getJSONObject(i).getString("uri"));WindowMerger.reconcile(c);assertEquals("",WindowMerger.lastError);rows=RecordingStore.list(c);assertEquals(1,rows.length());JSONObject result=rows.getJSONObject(0);assertNotEquals(original,result.getString("uri"));assertTrue("Frozen share keeps previous physical revision",exists(original));long finalSamples=decodedSamples(result.getString("uri"));assertTrue("Final samples="+finalSamples+", separately decoded="+expected,finalSamples>=expected-4096&&finalSamples<=expected+6144);
  assertArrayEquals(frozen,java.nio.file.Files.readAllBytes(text.toPath()));assertEquals(original,ExportSnapshot.read(c,snapshot.getString("token")).getJSONArray("files").getJSONObject(0).getString("uri"));
  JSONObject manifest=DailyLog.read(c,date);assertEquals(1,manifest.length());JSONObject entry=manifest.getJSONObject(manifest.keys().next());JSONArray spans=entry.getJSONArray("spans");assertEquals(3,spans.length());assertEquals(first,spans.getJSONObject(0).getLong("start"));assertEquals(late,spans.getJSONObject(1).getLong("start"));assertEquals(last,spans.getJSONObject(2).getLong("start"));assertTrue(spans.getJSONObject(1).getLong("from")>spans.getJSONObject(0).getLong("from"));
 }
}
