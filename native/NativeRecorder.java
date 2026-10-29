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
    private final java.util.concurrent.ExecutorService publisher=java.util.concurrent.Executors.newSingleThreadExecutor(task->new Thread(()->{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);task.run();},"Recording-Publisher"));
    private MediaCodec codec;
    private FileOutputStream out, timeline;
    private TimelineSegments spans;
    private final ArrayDeque<Long> ringTimes=new ArrayDeque<>();
    private long clockWall, clockMono;
    private File journal;
    private volatile long samples, fileStart, nextSplit, lastVoice, totalMs;
    private long lastSync;
    private volatile double db = -100;
    private volatile boolean sleeping, voice, interrupted;
    private volatile String interruptionReason="";
    private boolean resumeNotice;
    private int inputRetries;
    private long retryAt, normalSince;
    private static final class InputUnavailable extends IOException {
        final boolean busy;
        InputUnavailable(String message){this(message,false);}
        InputUnavailable(String message,boolean busy){super(message);this.busy=busy;}
    }
    private boolean callActive(){
        int mode=((AudioManager)ctx.getSystemService(Context.AUDIO_SERVICE)).getMode();
        return mode==AudioManager.MODE_IN_CALL||mode==AudioManager.MODE_IN_COMMUNICATION
            ||mode==AudioManager.MODE_RINGTONE||mode==5||mode==6;
    }
    private void suspendInput(String reason) throws Exception {
        // Keep the current AAC journal/encoder across calls: only the microphone is released.
        // Its sample clock stays continuous while wall-clock spans retain the call gap.
        closeInput();checkpoint();ring.clear();ringTimes.clear();voice=false;above=0;db=-100;
        interrupted=true;resumeNotice=true;interruptionReason=reason;normalSince=0;
        service.waitNative(reason);
    }
    private void deferInputRecovery(InputUnavailable e)throws IOException{
        if(callActiveCached()||e.busy){inputRetries=0;retryAt=SystemClock.elapsedRealtime()+5000;return;}
        if(++inputRetries>=10)throw new IOException("마이크 자동 재개 실패: "+e.getMessage()+" · 앱에서 다시 시작해 주세요");
        retryAt=SystemClock.elapsedRealtime()+Math.min(10000,1000L*inputRetries);
    }
    private final Object restSignal=new Object();
    void wakeForSchedule(){restCacheAt=0;synchronized(restSignal){restSignal.notifyAll();}}
    private volatile long restUntil, restCacheAt;
    private volatile long[] restRange=new long[]{0,0};
    private final float[] levels=new float[48];private int levelAt;
    private volatile String error = "", route = "확인 중";
    private int above;
    private long modeChecked=-1000, routeChecked;
    private boolean cachedCall;
    private boolean callActiveCached(){long now=SystemClock.elapsedRealtime();if(now-modeChecked>=200||now<modeChecked){cachedCall=callActive();modeChecked=now;}return cachedCall;}
    private final short[][] preRoll=new short[12][FRAME];
    private int preRollAt;
    private static final int RATE = 16000, FRAME = 800;
    private final ArrayDeque<short[]> ring = new ArrayDeque<>();
    private static final class ExportRequest {
        final String date;final java.util.concurrent.CompletableFuture<JSONObject> result=new java.util.concurrent.CompletableFuture<>();
        final boolean snapshot;ExportRequest(String d,boolean s){date=d;snapshot=s;}
    }
    private final java.util.concurrent.atomic.AtomicReference<ExportRequest> exportRequest=new java.util.concurrent.atomic.AtomicReference<>();
    private volatile ExportRequest activeExport;
    private volatile long lastWallEnd;
    private final long sessionStarted=System.currentTimeMillis();
    synchronized java.util.concurrent.CompletableFuture<JSONObject> prepareExport(String date){return prepareRequest(date,true);}
    synchronized java.util.concurrent.CompletableFuture<JSONObject> prepareBackup(){return prepareRequest(DailyLog.day(System.currentTimeMillis()),false);}
    private java.util.concurrent.CompletableFuture<JSONObject> prepareRequest(String date,boolean snapshot){
        ExportRequest request=new ExportRequest(date,snapshot);
        if(stop||activeExport!=null||(!sleeping&&!interrupted&&input==null)){request.result.completeExceptionally(new IOException("녹음 초기화·종료 또는 내보내기 준비 중입니다. 잠시 후 다시 시도해 주세요"));return request.result;}
        activeExport=request;
        // Even during a call, finalize the open window on the audio thread before snapshotting.
        exportRequest.set(request);
        return request.result;
    }
    private void queueSnapshot(ExportRequest request,long cutoff,File current){
        publisher.submit(()->{try{
            File[] pending=new File(ctx.getFilesDir(),"pending").listFiles((d,n)->n.endsWith(".aac"));
            if(pending!=null)for(File f:pending)if((current==null||!f.equals(current))&&f.length()>0)throw new IOException("저장이 끝나지 않은 녹음이 있습니다. 저장 오류를 확인해 주세요");
            WindowMerger.reconcile(ctx);JSONObject result=request.snapshot?ExportSnapshot.create(ctx,request.date,cutoff,RecordingStore.list(ctx)):new JSONObject().put("cutoff",cutoff);request.result.complete(result);
        }catch(Exception e){request.result.completeExceptionally(e);}finally{activeExport=null;}});
    }
    private void sleepReminderBarrier(long cutoff){
        android.content.SharedPreferences settings=ctx.getSharedPreferences("recorder-settings",0);if(!settings.getBoolean("reminderOn",false))return;
        final String reminderDate=DailyLog.day(cutoff-1);
        PowerManager.WakeLock saveLock=((PowerManager)ctx.getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"LifeRecorder::reminder-save");saveLock.acquire(120000);
        publisher.submit(()->{try{
            File[] pending=new File(ctx.getFilesDir(),"pending").listFiles((d,n)->n.endsWith(".aac"));
            if(pending!=null)for(File f:pending)if(f.length()>0)return;
            android.content.SharedPreferences p=ctx.getSharedPreferences("recorder-settings",0);
            if(!p.getBoolean("reminderOn",false)||!p.getBoolean("sleepOn",false))return;
            String date=reminderDate;WindowMerger.reconcile(ctx);JSONObject snapshot=ExportSnapshot.create(ctx,date,cutoff,RecordingStore.list(ctx));ExportReminder.afterSaved(ctx,snapshot);
        }catch(Exception e){android.util.Log.e("LifeRecorder","Reminder preparation failed",e);}finally{if(saveLock.isHeld())saveLock.release();}});
    }
    NativeRecorder(BlackboxService s, JSONObject config) { service=s; ctx=s.getApplicationContext(); cfg=validated(config); }
    void start() { instance=this; thread=new Thread(this,"LifeRecorder-Audio"); thread.start(); }
    void configure(JSONObject c) { cfg=validated(c);restCacheAt=0;wakeForSchedule(); if(sleeping){if(isSleep())service.sleepNative(wakeAt());else service.wakeNative();} }
    private static JSONObject validated(JSONObject source) {
        try {
            JSONObject c=new JSONObject(source.toString());
            c.put("splitMin",120);int rate=c.optInt("bitrate",24000);
            if(rate!=16000&&rate!=24000&&rate!=32000&&rate!=48000)rate=24000;c.put("bitrate",rate);
            c.put("threshold",Math.max(-80,Math.min(-10,c.optDouble("threshold",-50))));
            c.put("hangover",Math.max(1,Math.min(30,c.optInt("hangover",3))));
            for(String key:new String[]{"sleepStart","sleepEnd"})if(!c.optString(key).matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]"))c.put(key,key.equals("sleepStart")?"23:00":"07:00");
            c.put("restSchedules",RestSchedule.normalize(c));
            return c;
        }catch(Exception e){return new JSONObject();}
    }
    synchronized void stop() { if(stop)return;service.alarmWake(); stop=true; Thread t=thread; if(t!=null)t.interrupt(); }
    JSONObject status() {return status(true);}
    JSONObject status(boolean visual) {
        JSONObject o=new JSONObject(); try {
            o.put("sessionStarted",sessionStarted).put("filesVersion",RecordingStore.version).put("running", true).put("stopping",stop).put("sleeping",sleeping).put("voice",voice)
             .put("db",db).put("recMs",samples*1000/RATE).put("totalMs",totalMs+samples*1000/RATE)
             .put("maintenance",WindowMerger.lastError).put("merging",WindowMerger.working).put("interrupted",interrupted).put("interruptionReason",interruptionReason).put("restUntil",restUntil).put("levels",visual?visualLevels():new JSONArray()).put("threshold",cfg.optDouble("threshold",-50)).put("vadOn",cfg.optBoolean("vadOn",true)).put("nextSplit",nextSplit).put("route",route).put("error",error);
        } catch(Exception ignored) {} return o;
    }
    private synchronized void level(double value){levels[levelAt]=(float)Math.max(0,Math.min(1,(value+80)/55));levelAt=(levelAt+1)%levels.length;}
    private synchronized JSONArray visualLevels() throws JSONException{JSONArray a=new JSONArray();for(int n=0;n<levels.length;n++)a.put((double)levels[(levelAt+n)%levels.length]);return a;}
    private String stamp(long t,String f) {return new SimpleDateFormat(f,Locale.US).format(new Date(t));}
    private int minute(String s) {String[] a=s.split(":"); return Integer.parseInt(a[0])*60+Integer.parseInt(a[1]);}
    private long[] restNow(){
        long now=System.currentTimeMillis();if(restCacheAt==0||now-restCacheAt>=1000||now<restCacheAt||(restRange[1]>0&&now>=restRange[1])){
            try{restRange=RestSchedule.current(cfg.optJSONArray("restSchedules"),now);}catch(Exception e){restRange=new long[]{0,0};}
            restCacheAt=now;
        }restUntil=restRange[1];return restRange;
    }
    private boolean isSleep(){return restNow()[1]>0;}
    private long wakeAt(){return restNow()[1];}
    private void openInput() throws Exception {
        try { openInputInternal(); }
        catch(SecurityException e){throw e;}
        catch(IllegalArgumentException|IllegalStateException e){throw new InputUnavailable("마이크 초기화 오류: "+e.getMessage());}
    }
    private void openInputInternal() throws Exception {
        if(androidx.core.content.ContextCompat.checkSelfPermission(ctx,android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED)throw new SecurityException("마이크 권한이 필요합니다");
        int min=AudioRecord.getMinBufferSize(RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
        input=new AudioRecord(MediaRecorder.AudioSource.MIC,RATE,AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,Math.max(min,FRAME*8));
        if(input.getState()!=AudioRecord.STATE_INITIALIZED)throw new InputUnavailable("마이크 초기화 실패");
        AudioManager am=(AudioManager)ctx.getSystemService(Context.AUDIO_SERVICE);
        AudioDeviceInfo built=null;
        for(AudioDeviceInfo d:am.getDevices(AudioManager.GET_DEVICES_INPUTS))
            if(d.getType()==AudioDeviceInfo.TYPE_BUILTIN_MIC){built=d;break;}
        if(built==null || !input.setPreferredDevice(built))throw new InputUnavailable("내장 마이크 선택 실패");
        // Do not change global audio mode, SCO, speakerphone or audio focus.
        input.startRecording();
        clockWall=System.currentTimeMillis();clockMono=SystemClock.elapsedRealtime();
        if(input.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new InputUnavailable("마이크 시작 실패");
        checkRoute();
    }
    private void checkRoute() throws IOException {
        AudioDeviceInfo d=input.getRoutedDevice();
        if(d==null){route="내장 마이크 요청 중";return;}
        route=d.getType()==AudioDeviceInfo.TYPE_BUILTIN_MIC?"휴대폰 내장 마이크":"다른 입력: "+d.getType();
        if(d.getType()!=AudioDeviceInfo.TYPE_BUILTIN_MIC)throw new InputUnavailable("내장 마이크 경로 변경");
    }
    private void closeInput(){AudioRecord a=input; input=null; if(a!=null){try{a.stop();}catch(Exception ignored){} a.release();}}
    private void beginFile() throws Exception {
        fileStart=System.currentTimeMillis();samples=0;
        long window=RecordingWindow.start(fileStart);nextSplit=RecordingWindow.end(fileStart);
        File dir=new File(ctx.getFilesDir(),"pending");dir.mkdirs();
        journal=new File(dir,"LBB_"+stamp(fileStart,"yyyyMMdd_HHmmss_SSS")+"_W"+window+".aac");
        out=new FileOutputStream(journal);
        timeline=new FileOutputStream(journal.getAbsolutePath()+".timeline");spans=new TimelineSegments();
        MediaFormat f=MediaFormat.createAudioFormat("audio/mp4a-latm",RATE,1);
        f.setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        f.setInteger(MediaFormat.KEY_BIT_RATE,cfg.optInt("bitrate",24000));
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,FRAME*2);
        codec=MediaCodec.createEncoderByType("audio/mp4a-latm");codec.configure(f,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);codec.start();
    }
    private void encode(short[] pcm,long wall) throws Exception {
        int i;long inputDeadline=SystemClock.elapsedRealtime()+5000;
        while((i=codec.dequeueInputBuffer(10000))<0){if(SystemClock.elapsedRealtime()>inputDeadline)throw new IOException("AAC 입력 버퍼 시간 초과");drain(false);if(stop&&Thread.currentThread().isInterrupted())Thread.interrupted();}
        ByteBuffer b=codec.getInputBuffer(i);b.clear(); b.order(ByteOrder.LITTLE_ENDIAN);
        for(short s:pcm)b.putShort(s);
        codec.queueInputBuffer(i,0,pcm.length*2,samples*1000000/RATE,0);timeline.write(spans.accept(samples*1000/RATE,pcm.length*1000/RATE,wall).getBytes(java.nio.charset.StandardCharsets.UTF_8));samples+=pcm.length;drain(false);
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
    private void checkpoint() throws Exception {
        if(codec!=null)drain(false);
        if(out!=null){
            timeline.write(spans.flush().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.getFD().sync();timeline.getFD().sync();lastSync=System.currentTimeMillis();
        }
    }
    private void splitIfDue(long now) throws Exception {
        if(codec!=null&&now>=nextSplit){
            finishFile();ring.clear();ringTimes.clear();voice=false;above=0;
        }
    }
    private void ensureFile() throws Exception {if(codec==null)beginFile();}
    private void finishFile() throws Exception {
        if(codec==null)return;
        Exception failure=null;
        try{int i=codec.dequeueInputBuffer(100000);if(i<0)throw new IOException("AAC 종료 버퍼 없음");
            codec.queueInputBuffer(i,0,0,samples*1000000/RATE,MediaCodec.BUFFER_FLAG_END_OF_STREAM);drain(true);
        }catch(Exception e){failure=e;}
        finally{try{codec.stop();}catch(Exception ignored){}codec.release();codec=null;timeline.write(spans.flush().getBytes(java.nio.charset.StandardCharsets.UTF_8));timeline.getFD().sync();timeline.close();out.getFD().sync();out.close();out=null;}
        totalMs+=samples*1000/RATE;samples=0;
        if(journal.length()==0){journal.delete();new File(journal.getAbsolutePath()+".timeline").delete();} else {
            final File ready=journal;
            publisher.submit(()->{
                PowerManager pm=(PowerManager)ctx.getSystemService(Context.POWER_SERVICE);
                PowerManager.WakeLock lock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"LifeRecorder::save");
                lock.acquire(120000);
                try{publish(ctx,ready,false);WindowMerger.reconcile(ctx);RetentionManager.request(ctx);DriveBackup.request(ctx);}catch(Exception e){service.recordError("파일 저장 보류: "+e.getMessage());}
                finally{if(lock.isHeld())lock.release();}
            });
        }
        if(failure!=null)throw failure;
    }
    @Override public void run(){
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        try{
            try { recover(ctx);WindowMerger.request(ctx); } catch(Exception e) { service.recordError("일부 이전 파일 복구 보류: "+e.getMessage()); }
            boolean resumedPending=cfg.optBoolean("visibleRecovery",false);
            short[] pcm=new short[FRAME];
            while(!stop){
              try {
                // Advance clock-aligned windows even if a call spans a split boundary.
                splitIfDue(System.currentTimeMillis());
                ExportRequest request=exportRequest.getAndSet(null);
                if(request!=null){
                    long cutoff=lastWallEnd>0?lastWallEnd:System.currentTimeMillis();
                    finishFile();ring.clear();ringTimes.clear();
                    if(!isSleep()&&!interrupted&&!callActiveCached()){beginFile();queueSnapshot(request,cutoff,journal);}else queueSnapshot(request,cutoff,null);
                }

                if(isSleep()){
                    if(!sleeping){long cutoff=System.currentTimeMillis();finishFile();sleepReminderBarrier(cutoff);closeInput();ring.clear();ringTimes.clear();voice=false;sleeping=true;service.sleepNative(wakeAt());RestNotifications.send(ctx,"녹음을 잠시 쉽니다",stamp(wakeAt(),"M월 d일 HH:mm")+" 재개 예정");}
                    synchronized(restSignal){if(!stop)restSignal.wait(1000);}continue;
                }
                if(sleeping){sleeping=false;resumedPending=true;service.wakeNative();}
                if(callActiveCached()){
                    if(!interrupted||input!=null)suspendInput("통화로 녹음 대기 중");
                    inputRetries=0;normalSince=0;Thread.sleep(500);continue;
                }
                if(interrupted){
                    long mono=SystemClock.elapsedRealtime();
                    if(normalSince==0)normalSince=mono;
                    if(mono-normalSince<1500||mono<retryAt){Thread.sleep(250);continue;}
                }
                if(input==null){resumedPending=resumedPending||sleeping;service.wakeNative();sleeping=false;openInput();ring.clear();ringTimes.clear();voice=false;above=0;}
                int n=0;
                long readDeadline=SystemClock.elapsedRealtime()+3000;
                while(n<FRAME&&!stop){
                    if(callActiveCached())throw new InputUnavailable("통화 시작");
                    int k;
                    try{k=input.read(pcm,n,FRAME-n,AudioRecord.READ_NON_BLOCKING);}
                    catch(IllegalStateException e){throw new InputUnavailable("마이크 입력 상태 변경");}
                    if(k<0)throw new InputUnavailable("마이크 읽기 오류 "+k);
                    if(k==0){if(SystemClock.elapsedRealtime()>readDeadline)throw new InputUnavailable("마이크 입력 시간 초과");Thread.sleep(Math.max(5,Math.min(50,(FRAME-n)*1000L/RATE)));}else n+=k;
                }
                if(stop)break;
                if(interrupted){
                    // A successful read plus an unsilenced, built-in route confirms recovery.
                    if(android.os.Build.VERSION.SDK_INT>=29){AudioRecordingConfiguration c=input.getActiveRecordingConfiguration();
                        if(c!=null&&c.isClientSilenced())throw new InputUnavailable("다른 통화·앱이 마이크를 사용 중입니다",true);}
                    checkRoute();interrupted=false;inputRetries=0;interruptionReason="";error="";
                    service.wakeNative();
                    if(resumeNotice){if(resumedPending)RecordingSession.resumed(ctx);RestNotifications.send(ctx,"녹음이 다시 시작되었습니다","통화·마이크 대기 후 입력을 확인했습니다");resumeNotice=false;resumedPending=false;}
                }
                if(resumedPending){RecordingSession.resumed(ctx);RestNotifications.send(ctx,"녹음이 다시 시작되었습니다","휴식 시간이 끝나 마이크 입력을 확인했습니다");resumedPending=false;}
                splitIfDue(System.currentTimeMillis());ensureFile();
                double sum=0;for(short x:pcm){double v=x/32768.0;sum+=v*v;}db=sum>0?10*Math.log10(sum/FRAME):-100;level(db);
                long now=System.currentTimeMillis();
                long frameWall=clockWall+SystemClock.elapsedRealtime()-clockMono-50;lastWallEnd=frameWall+50;
                if(db>cfg.optDouble("threshold",-50)){above++;if(above>=2){lastVoice=now;voice=true;}}else above=0;
                if(voice&&now-lastVoice>cfg.optInt("hangover",3)*1000L)voice=false;
                if(!cfg.optBoolean("vadOn",true)||voice){while(!ring.isEmpty())encode(ring.removeFirst(),ringTimes.removeFirst());encode(pcm,frameWall);}
                else{while(ring.size()>=12){ring.removeFirst();ringTimes.removeFirst();}short[] saved=preRoll[preRollAt];preRollAt=(preRollAt+1)%preRoll.length;System.arraycopy(pcm,0,saved,0,FRAME);ring.addLast(saved);ringTimes.addLast(frameWall);}
                if(now-routeChecked>=1000){routeChecked=now;checkRoute();
                    if(android.os.Build.VERSION.SDK_INT>=29){AudioRecordingConfiguration c=input.getActiveRecordingConfiguration();
                        if(c!=null&&c.isClientSilenced())throw new InputUnavailable("다른 통화·앱이 마이크를 사용 중입니다",true);}
                }
                if(now-lastSync>=10000)checkpoint();
              } catch(InputUnavailable e){
                if(stop)break;
                suspendInput(callActiveCached()?"통화로 녹음 대기 중":"마이크 복구 대기 중");
                deferInputRecovery(e);
              }
            }
        }catch(InterruptedException ignored){}catch(Exception e){error=e.getMessage();RecordingSession.reason(ctx,error);service.recordError(error);RestNotifications.send(ctx,"녹음을 계속하지 못했습니다",error==null?"앱을 열어 확인해 주세요":error);}
        finally{try{finishFile();}catch(Exception e){error=e.getMessage();service.recordError(error);}closeInput();
            publisher.shutdown();
            try { publisher.awaitTermination(120,java.util.concurrent.TimeUnit.SECONDS); } catch(InterruptedException ignored) { }
            ExportRequest unfinished=activeExport;if(unfinished!=null&&!unfinished.result.isDone())unfinished.result.completeExceptionally(new IOException("녹음 종료로 준비가 중단됐습니다. 다시 내보내기를 눌러 주세요"));
            stop=true;instance=null;service.recordingFinished();}
    }
    static synchronized void recover(Context c)throws Exception{
        File[] files=new File(c.getFilesDir(),"pending").listFiles((d,n)->n.endsWith(".aac"));
        if(files!=null)for(File f:files){if(f.length()==0)f.delete();else publish(c,f,true);}
    }
    static synchronized void publish(Context c,File journal,boolean recovered)throws Exception{
        final android.util.AtomicFile receipt=new android.util.AtomicFile(new File(journal.getPath()+".publish.json"));
        JSONObject loaded;
        try{loaded=new JSONObject(new String(receipt.readFully(),java.nio.charset.StandardCharsets.UTF_8));}
        catch(FileNotFoundException e){
            if(receipt.getBaseFile().exists()||new File(receipt.getBaseFile().getPath()+".bak").exists())throw e;
            loaded=new JSONObject();
        }
        final JSONObject state=loaded;
        if(state.optBoolean("done")){
            if(journal.delete()||!journal.exists()){new File(journal.getPath()+".timeline").delete();receipt.delete();}
            return;
        }
        // Recovery uses the same identity as normal publication, including older recovery names.
        String base=state.optString("name",journal.getName().replace(".aac",".m4a"));
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
        final ContentResolver cr=c.getContentResolver();long created=journal.lastModified();
        try { created=new SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.US).parse(journal.getName().substring(4,23)).getTime(); } catch(Exception ignored) { }
        final long timestamp=created, duration=last/1000+64;
        final String relative="Documents/LifeRecorder/"+new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date(created))+"/";
        final Uri collection=MediaStore.setIncludePending(MediaStore.Files.getContentUri("external"));
        RecordingPublication.publish(new RecordingPublication.Backend(){
            void saveState()throws Exception{
                FileOutputStream stream=null;
                try{stream=receipt.startWrite();stream.write(state.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));receipt.finishWrite(stream);}
                catch(Exception e){if(stream!=null)receipt.failWrite(stream);throw e;}
            }
            public boolean done(){return state.optBoolean("done");}
            public String destination(){return state.has("uri")?state.optString("uri"):null;}
            public void prepare()throws Exception{
                state.put("name",base);saveState();
            }
            RecordingPublication.Target readTarget(android.database.Cursor cursor)throws Exception{
                String owner=cursor.getString(2);
                if(!c.getPackageName().equals(owner))throw new IOException("다른 앱의 파일은 변경하지 않습니다");
                return new RecordingPublication.Target(android.content.ContentUris.withAppendedId(
                    MediaStore.Files.getContentUri("external"),cursor.getLong(0)).toString(),cursor.getInt(1)!=0);
            }
            final String[] columns={MediaStore.MediaColumns._ID,MediaStore.MediaColumns.IS_PENDING,MediaStore.MediaColumns.OWNER_PACKAGE_NAME};
            public RecordingPublication.Target find()throws Exception{
                // Covers process death between insert() and persisting its returned URI.
                String oldName=journal.getName().replace(".aac","_복구.m4a");
                String where=MediaStore.MediaColumns.RELATIVE_PATH+"=? AND ("+MediaStore.MediaColumns.DISPLAY_NAME+"=? OR "+MediaStore.MediaColumns.DISPLAY_NAME+"=?)";
                try(android.database.Cursor cursor=cr.query(collection,columns,where,new String[]{relative,base,oldName},null)){
                    if(cursor==null)throw new IOException("저장 대상 조회 실패");
                    if(!cursor.moveToFirst())return null;
                    RecordingPublication.Target result=readTarget(cursor);
                    if(cursor.moveToNext())throw new IOException("동일 녹음 후보가 여러 개여서 복구를 보류합니다");
                    return result;
                }
            }
            public RecordingPublication.Target inspect(String uri)throws Exception{
                try(android.database.Cursor cursor=cr.query(MediaStore.setIncludePending(Uri.parse(uri)),columns,null,null,null)){
                    if(cursor==null)throw new IOException("저장 대상 조회 실패");
                    return cursor.moveToFirst()?readTarget(cursor):null;
                }
            }
            public RecordingPublication.Target create()throws Exception{
                ContentValues v=new ContentValues();v.put(MediaStore.MediaColumns.DISPLAY_NAME,base);
                v.put(MediaStore.MediaColumns.MIME_TYPE,"audio/mp4");v.put(MediaStore.MediaColumns.RELATIVE_PATH,relative);
                v.put(MediaStore.MediaColumns.IS_PENDING,1);
                Uri uri=cr.insert(MediaStore.Files.getContentUri("external"),v);
                if(uri==null)throw new IOException("저장 위치 생성 실패");
                return new RecordingPublication.Target(uri.toString(),true);
            }
            public void remember(String uri)throws Exception{state.put("uri",uri);saveState();}
            public void writePending(RecordingPublication.Target target)throws Exception{
                // Only this recording's unfinished row is retried. Published audio is never overwritten.
                try(InputStream in=new FileInputStream(tmp);android.os.ParcelFileDescriptor fd=cr.openFileDescriptor(Uri.parse(target.uri),"rwt")){
                    if(fd==null)throw new IOException("저장 스트림 없음");
                    try(FileOutputStream output=new android.os.ParcelFileDescriptor.AutoCloseOutputStream(fd)){
                        byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)output.write(b,0,n);
                        output.flush();output.getFD().sync();
                    }
                }
            }
            public void finish(RecordingPublication.Target target)throws Exception{
                ContentValues v=new ContentValues();v.put(MediaStore.MediaColumns.IS_PENDING,0);
                if(cr.update(Uri.parse(target.uri),v,null,null)!=1)throw new IOException("저장 완료 처리 실패");
            }
            public void index(RecordingPublication.Target target)throws Exception{
                String name=base;
                try(android.database.Cursor cursor=cr.query(Uri.parse(target.uri),new String[]{MediaStore.MediaColumns.DISPLAY_NAME},null,null,null)){
                    if(cursor==null||!cursor.moveToFirst())throw new IOException("저장 파일 확인 실패");
                    name=cursor.getString(0);
                }
                // Publish the complete timeline before making the row visible to merge/export.
                DailyLog.record(c,journal,name,timestamp,duration);
                RecordingStore.add(c,name,target.uri,tmp.length(),duration,recovered,timestamp);
            }
            public void complete()throws Exception{state.put("done",true);saveState();}
            public void cleanup(){
                // Never delete MediaStore audio; only discard the successfully published private journal.
                if(journal.delete()||!journal.exists()){new File(journal.getPath()+".timeline").delete();receipt.delete();}
                tmp.delete();
            }
        });
    }
}

