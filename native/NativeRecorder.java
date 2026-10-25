package com.lifeblackbox.recorder;

import android.content.*;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import org.json.*;
import java.io.*;
import java.nio.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Service-owned PCM capture, 50ms VAD, 600ms pre-roll, durable ADTS journal. */
public final class NativeRecorder implements Runnable {
    static volatile NativeRecorder instance;
    private final Context ctx;
    private final BlackboxService service;
    private volatile boolean stop;
    private volatile JSONObject cfg;
    private volatile AudioRecord input;
    private Thread thread;
    private final java.util.concurrent.ExecutorService publisher=java.util.concurrent.Executors.newSingleThreadExecutor();
    private MediaCodec codec;
    private FileOutputStream out;
    private File journal;
    private volatile long samples, fileStart, nextSplit, lastVoice, totalMs;
    private long lastSync;
    private volatile double db = -100;
    private volatile boolean sleeping, voice;
    private volatile String error = "", route = "확인 중";
    private int above;
    private static final int RATE = 16000, FRAME = 800;
    private final ArrayDeque<short[]> ring = new ArrayDeque<>();
    NativeRecorder(BlackboxService s, JSONObject config) { service=s; ctx=s.getApplicationContext(); cfg=validated(config); }
    void start() { instance=this; thread=new Thread(this,"LifeRecorder-Audio"); thread.start(); }
    void configure(JSONObject c) { cfg=validated(c); if(sleeping){if(isSleep())service.sleepNative(wakeAt());else service.wakeNative();} }
    private static JSONObject validated(JSONObject source) {
        try {
            JSONObject c=new JSONObject(source.toString());
            int split=c.optInt("splitMin",30);if(split!=1&&split!=15&&split!=30&&split!=60)split=30;
            c.put("splitMin",split);int rate=c.optInt("bitrate",24000);
            if(rate!=16000&&rate!=24000&&rate!=32000&&rate!=48000)rate=24000;c.put("bitrate",rate);
            c.put("threshold",Math.max(-80,Math.min(-10,c.optDouble("threshold",-50))));
            c.put("hangover",Math.max(1,Math.min(30,c.optInt("hangover",3))));
            for(String key:new String[]{"sleepStart","sleepEnd"})if(!c.optString(key).matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]"))c.put(key,key.equals("sleepStart")?"23:00":"07:00");
            return c;
        }catch(Exception e){return new JSONObject();}
    }
    void stop() { service.wakeNative(); stop=true; Thread t=thread; if(t!=null)t.interrupt(); }
    JSONObject status() {
        JSONObject o=new JSONObject(); try {
            o.put("running", true).put("stopping",stop).put("sleeping",sleeping).put("voice",voice)
             .put("db",db).put("recMs",samples*1000/RATE).put("totalMs",totalMs+samples*1000/RATE)
             .put("nextSplit",nextSplit).put("route",route).put("error",error);
        } catch(Exception ignored) {} return o;
    }
    private String stamp(long t,String f) {return new SimpleDateFormat(f,Locale.US).format(new Date(t));}
    private int minute(String s) {String[] a=s.split(":"); return Integer.parseInt(a[0])*60+Integer.parseInt(a[1]);}
    private boolean isSleep() {
        if(!cfg.optBoolean("sleepOn"))return false;
        Calendar c=Calendar.getInstance(); int n=c.get(Calendar.HOUR_OF_DAY)*60+c.get(Calendar.MINUTE);
        int a=minute(cfg.optString("sleepStart","23:00")),b=minute(cfg.optString("sleepEnd","07:00"));
        return a!=b && (a<b ? n>=a&&n<b : n>=a||n<b);
    }
    private long wakeAt() {
        Calendar c=Calendar.getInstance(); int m=minute(cfg.optString("sleepEnd","07:00"));
        c.set(Calendar.HOUR_OF_DAY,m/60);c.set(Calendar.MINUTE,m%60);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);
        if(c.getTimeInMillis()<=System.currentTimeMillis()) c.add(Calendar.DATE,1); return c.getTimeInMillis();
    }
    private void openInput() throws Exception {
        if(androidx.core.content.ContextCompat.checkSelfPermission(ctx,android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED)throw new SecurityException("마이크 권한이 필요합니다");
        int min=AudioRecord.getMinBufferSize(RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
        input=new AudioRecord(MediaRecorder.AudioSource.MIC,RATE,AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,Math.max(min,FRAME*8));
        if(input.getState()!=AudioRecord.STATE_INITIALIZED)throw new IOException("마이크 초기화 실패");
        AudioManager am=(AudioManager)ctx.getSystemService(Context.AUDIO_SERVICE);
        AudioDeviceInfo built=null;
        for(AudioDeviceInfo d:am.getDevices(AudioManager.GET_DEVICES_INPUTS))
            if(d.getType()==AudioDeviceInfo.TYPE_BUILTIN_MIC){built=d;break;}
        if(built==null || !input.setPreferredDevice(built))throw new IOException("내장 마이크 선택 실패");
        // Do not change global audio mode, SCO, speakerphone or audio focus.
        input.startRecording();
        if(input.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IOException("마이크 시작 실패");
        checkRoute();
    }
    private void checkRoute() throws IOException {
        AudioDeviceInfo d=input.getRoutedDevice();
        if(d==null){route="내장 마이크 요청 중";return;}
        route=d.getType()==AudioDeviceInfo.TYPE_BUILTIN_MIC?"휴대폰 내장 마이크":"다른 입력: "+d.getType();
        if(d.getType()!=AudioDeviceInfo.TYPE_BUILTIN_MIC)throw new IOException("내장 마이크 경로가 해제되어 녹음을 중지했습니다");
    }
    private void closeInput(){AudioRecord a=input; input=null; if(a!=null){try{a.stop();}catch(Exception ignored){} a.release();}}
    private void beginFile() throws Exception {
        fileStart=System.currentTimeMillis();samples=0;
        Calendar c=Calendar.getInstance(); int step=cfg.optInt("splitMin",30),m=c.get(Calendar.HOUR_OF_DAY)*60+c.get(Calendar.MINUTE);
        c.set(Calendar.HOUR_OF_DAY,0);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);
        c.add(Calendar.MINUTE,(m/step+1)*step);nextSplit=c.getTimeInMillis();
        File dir=new File(ctx.getFilesDir(),"pending");dir.mkdirs();
        journal=new File(dir,"LBB_"+stamp(fileStart,"yyyyMMdd_HHmmss_SSS")+".aac");
        out=new FileOutputStream(journal);
        MediaFormat f=MediaFormat.createAudioFormat("audio/mp4a-latm",RATE,1);
        f.setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        f.setInteger(MediaFormat.KEY_BIT_RATE,cfg.optInt("bitrate",24000));
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,FRAME*2);
        codec=MediaCodec.createEncoderByType("audio/mp4a-latm");codec.configure(f,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);codec.start();
    }
    private void encode(short[] pcm) throws Exception {
        int i;
        while((i=codec.dequeueInputBuffer(10000))<0){drain(false);if(stop&&Thread.currentThread().isInterrupted())Thread.interrupted();}
        ByteBuffer b=codec.getInputBuffer(i);b.clear(); b.order(ByteOrder.LITTLE_ENDIAN);
        for(short s:pcm)b.putShort(s);
        codec.queueInputBuffer(i,0,pcm.length*2,samples*1000000/RATE,0);samples+=pcm.length;drain(false);
    }
    private void drain(boolean eos) throws Exception {
        MediaCodec.BufferInfo bi=new MediaCodec.BufferInfo(); long deadline=SystemClock.elapsedRealtime()+5000;
        while(true){int i=codec.dequeueOutputBuffer(bi,eos?10000:0);
            if(i>=0){
                if(bi.size>0&&(bi.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0){
                    ByteBuffer b=codec.getOutputBuffer(i);b.position(bi.offset);b.limit(bi.offset+bi.size);
                    int len=bi.size+7; byte[] h={(byte)0xff,(byte)0xf1,(byte)0x60,(byte)(0x40|(len>>11)),(byte)(len>>3),(byte)(((len&7)<<5)|0x1f),(byte)0xfc};
                    out.write(h);byte[] data=new byte[bi.size];b.get(data);out.write(data);
                }
                boolean done=(bi.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;codec.releaseOutputBuffer(i,false);if(done)return;
            }else if(i==MediaCodec.INFO_TRY_AGAIN_LATER){if(!eos)return;if(SystemClock.elapsedRealtime()>deadline)throw new IOException("AAC 종료 시간 초과");}
        }
    }
    private void finishFile() throws Exception {
        if(codec==null)return;
        Exception failure=null;
        try{int i=codec.dequeueInputBuffer(100000);if(i<0)throw new IOException("AAC 종료 버퍼 없음");
            codec.queueInputBuffer(i,0,0,samples*1000000/RATE,MediaCodec.BUFFER_FLAG_END_OF_STREAM);drain(true);
        }catch(Exception e){failure=e;}
        finally{try{codec.stop();}catch(Exception ignored){}codec.release();codec=null;out.getFD().sync();out.close();out=null;}
        totalMs+=samples*1000/RATE;samples=0;
        if(journal.length()==0)journal.delete(); else {
            final File ready=journal;
            publisher.submit(()->{
                PowerManager pm=(PowerManager)ctx.getSystemService(Context.POWER_SERVICE);
                PowerManager.WakeLock lock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"LifeRecorder::save");
                lock.acquire(120000);
                try{publish(ctx,ready,false);}catch(Exception e){service.recordError("파일 저장 보류: "+e.getMessage());}
                finally{if(lock.isHeld())lock.release();}
            });
        }
        if(failure!=null)throw failure;
    }
    @Override public void run(){
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        try{
            try { recover(ctx); } catch(Exception e) { service.recordError("일부 이전 파일 복구 보류: "+e.getMessage()); }
            while(!stop){
                if(isSleep()){
                    if(!sleeping){finishFile();closeInput();ring.clear();voice=false;sleeping=true;service.sleepNative(wakeAt());}
                    Thread.sleep(1000);continue;
                }
                if(input==null){service.wakeNative();sleeping=false;openInput();ring.clear();voice=false;above=0;beginFile();}
                short[] pcm=new short[FRAME];int n=0;
                while(n<FRAME&&!stop){int k=input.read(pcm,n,FRAME-n,AudioRecord.READ_BLOCKING);if(k<=0)throw new IOException("마이크 읽기 오류 "+k);n+=k;}
                if(stop)break;
                double sum=0;for(short x:pcm){double v=x/32768.0;sum+=v*v;}db=sum>0?10*Math.log10(sum/FRAME):-100;
                long now=System.currentTimeMillis();
                if(db>cfg.optDouble("threshold",-50)){above++;if(above>=2){lastVoice=now;voice=true;}}else above=0;
                if(voice&&now-lastVoice>cfg.optInt("hangover",3)*1000L)voice=false;
                if(!cfg.optBoolean("vadOn",true)||voice){while(!ring.isEmpty())encode(ring.removeFirst());encode(pcm);}
                else{ring.addLast(pcm);while(ring.size()>12)ring.removeFirst();}
                if(now>=nextSplit){finishFile();beginFile();}
                if(now%1000<50)checkRoute();
                if(now-lastSync>=10000){out.getFD().sync();lastSync=now;}
            }
        }catch(InterruptedException ignored){}catch(Exception e){error=e.getMessage();service.recordError(error);}
        finally{try{finishFile();}catch(Exception e){error=e.getMessage();service.recordError(error);}closeInput();
            publisher.shutdown();
            try { publisher.awaitTermination(120,java.util.concurrent.TimeUnit.SECONDS); } catch(InterruptedException ignored) { }
            stop=true;instance=null;service.recordingFinished();}
    }
    static synchronized void recover(Context c)throws Exception{
        File[] files=new File(c.getFilesDir(),"pending").listFiles((d,n)->n.endsWith(".aac"));
        if(files!=null)for(File f:files){if(f.length()==0)f.delete();else publish(c,f,true);}
    }
    static void publish(Context c,File journal,boolean recovered)throws Exception{
        String base=journal.getName().replace(".aac",recovered?"_복구.m4a":".m4a");
        File tmp=new File(c.getCacheDir(),base);
        MediaExtractor ex=new MediaExtractor();MediaMuxer mux=null;boolean started=false;long last=0;
        try{
            ex.setDataSource(journal.getAbsolutePath());if(ex.getTrackCount()==0)throw new IOException("복구 가능한 AAC 트랙 없음");
            ex.selectTrack(0);mux=new MediaMuxer(tmp.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int ti=mux.addTrack(ex.getTrackFormat(0));mux.start();started=true;
            ByteBuffer b=ByteBuffer.allocate(65536);MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            while(true){b.clear();int n=ex.readSampleData(b,0);if(n<0)break;
                last=ex.getSampleTime();info.set(0,n,last,MediaCodec.BUFFER_FLAG_KEY_FRAME);mux.writeSampleData(ti,b,info);ex.advance();}
            mux.stop();started=false;
        }finally{ex.release();if(mux!=null){if(started)try{mux.stop();}catch(Exception ignored){}mux.release();}}
        if(last==0 && tmp.length()<100)throw new IOException("빈 녹음 파일");
        ContentResolver cr=c.getContentResolver();long created=journal.lastModified();
        try { created=new SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.US).parse(journal.getName().substring(4,23)).getTime(); } catch(Exception ignored) { }
        String date=new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date(created));
        ContentValues v=new ContentValues();v.put(MediaStore.MediaColumns.DISPLAY_NAME,base);v.put(MediaStore.MediaColumns.MIME_TYPE,"audio/mp4");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH,"Documents/LifeRecorder/"+date);v.put(MediaStore.MediaColumns.IS_PENDING,1);
        Uri uri=cr.insert(MediaStore.Files.getContentUri("external"),v);if(uri==null)throw new IOException("저장 위치 생성 실패");
        try(InputStream in=new FileInputStream(tmp);OutputStream out=cr.openOutputStream(uri,"w")){
            if(out==null)throw new IOException("저장 스트림 없음");byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);
        }catch(Exception e){cr.delete(uri,null,null);throw e;}
        v.clear();v.put(MediaStore.MediaColumns.IS_PENDING,0);cr.update(uri,v,null,null);
        RecordingStore.add(c,base,uri.toString(),tmp.length(),last/1000+64,recovered,created);
        journal.delete();tmp.delete();DriveSync.schedule(c);
    }
}
