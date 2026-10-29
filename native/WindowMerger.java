package com.lifeblackbox.recorder;

import android.content.*;
import android.database.Cursor;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded packet-copy AAC merge. Durable receipt precedes publication and metadata replacement. */
final class WindowMerger {
 static volatile String lastError="";
 static volatile boolean working;
 private static final AtomicBoolean requested=new AtomicBoolean();
 static void request(Context context){Context c=context.getApplicationContext();if(!requested.compareAndSet(false,true))return;
  new Thread(()->{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);try{reconcile(c);}finally{requested.set(false);DriveBackup.request(c);}},"Window-Merge").start();}
 static File root(Context c){File f=new File(c.getFilesDir(),"window-merges");f.mkdirs();return f;}
 static void reconcile(Context c){synchronized(ExportSnapshot.class){working=true;lastError="";try{
  ExportSnapshot.prune(c,System.currentTimeMillis());
  File[] receipts=root(c).listFiles((d,n)->n.endsWith(".json")||n.endsWith(".json.bak"));Set<String> seenReceipts=new HashSet<>();
  if(receipts!=null)for(File f:receipts)try{File base=new File(f.getPath().replaceFirst("\\.bak$",""));if(!seenReceipts.add(base.getPath()))continue;resume(c,new AtomicFile(base));}catch(Exception e){lastError="파일 정리 보류: "+e.getMessage();return;}
  // Batches bound open extractors/FDs; every successful pass reduces the visible file count.
  for(int pass=0;pass<128;pass++){
   Map<Long,List<JSONObject>> groups=new LinkedHashMap<>();JSONArray files=RecordingStore.list(c);
   for(int i=0;i<files.length();i++){JSONObject row=files.getJSONObject(i);long key=RecordingWindow.key(row.optString("name"));if(key<=0)continue;groups.computeIfAbsent(key,k->new ArrayList<>()).add(row);}
   boolean changed=false;
   for(Map.Entry<Long,List<JSONObject>> group:groups.entrySet()){
    List<JSONObject> rows=group.getValue();if(rows.size()<2)continue;rows.sort(Comparator.comparingLong(o->o.optLong("created")));
    if(rows.size()>16)rows=new ArrayList<>(rows.subList(0,16));
    try{AtomicFile receipt=prepare(c,group.getKey(),rows);resume(c,receipt);changed=true;}
    catch(Exception e){lastError="파일 정리 보류: "+e.getMessage();return;}
   }
   if(!changed)break;
  }
 }catch(Exception e){lastError="파일 정리 보류: "+e.getMessage();}finally{working=false;}}}
 static String hex(byte[] data){StringBuilder b=new StringBuilder();for(byte v:data)b.append(String.format(Locale.ROOT,"%02x",v&255));return b.toString();}
 static AtomicFile prepare(Context c,long window,List<JSONObject> sources)throws Exception{
  MessageDigest hash=MessageDigest.getInstance("SHA-256");List<String> ids=new ArrayList<>();for(JSONObject row:sources)ids.add(row.getString("uri"));Collections.sort(ids);for(String id:ids)hash.update((id+"\n").getBytes(StandardCharsets.UTF_8));String id=hex(hash.digest()).substring(0,24);
  AtomicFile receipt=new AtomicFile(new File(root(c),id+".json"));if(receipt.getBaseFile().exists()||new File(receipt.getBaseFile().getPath()+".bak").exists())return receipt;
  String date=sources.get(0).getString("name").substring(4,12);date=date.substring(0,4)+"-"+date.substring(4,6)+"-"+date.substring(6,8);
  JSONObject all=DailyLog.read(c,date);JSONArray inputs=new JSONArray(), spansToCheck=new JSONArray();long created=Long.MAX_VALUE;
  for(JSONObject row:sources){if(RecordingWindow.key(row.getString("name"))!=window)throw new IOException("시간 구간 불일치");assertOwned(c,Uri.parse(row.getString("uri")),row.getString("name"),date,false);
   JSONArray spans=new JSONArray();Iterator<String> keys=all.keys();while(keys.hasNext()){JSONObject entry=all.getJSONObject(keys.next());if(!row.getString("name").equals(entry.optString("name")))continue;JSONArray a=entry.getJSONArray("spans");for(int i=0;i<a.length();i++)spans.put(a.getJSONObject(i));}
   if(spans.length()==0)throw new IOException("실제 시각 정보가 없는 녹음은 자동 병합하지 않습니다");
   for(int i=0;i<spans.length();i++){JSONObject p=spans.getJSONObject(i);if(p.getLong("start")<window-100||p.getLong("end")>window+7200000L+100)throw new IOException("시간 경계를 넘는 조각 확인 필요");spansToCheck.put(p);}
   inputs.put(new JSONObject(row.toString()).put("spans",spans));created=Math.min(created,row.getLong("created"));
  }
  List<JSONObject> check=new ArrayList<>();for(int i=0;i<spansToCheck.length();i++)check.add(spansToCheck.getJSONObject(i));check.sort(Comparator.comparingLong(p->p.optLong("start")));long previous=0;for(JSONObject p:check){if(p.getLong("start")<previous-100)throw new IOException("녹음 시각이 겹쳐 원본 확인이 필요합니다");previous=Math.max(previous,p.getLong("end"));}
  String stamp=new SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.US).format(new Date(created));String name="LBB_"+stamp+"_W"+window+"_M"+id+".m4a";
  JSONObject state=new JSONObject().put("sources",inputs).put("date",date).put("window",window).put("created",created).put("name",name).put("id",id);
  DailyLog.write(receipt,state);return receipt;
 }
 static void assertOwned(Context c,Uri uri,String name,String date,boolean pending)throws Exception{
  if(!"content".equals(uri.getScheme())||!"media".equals(uri.getAuthority()))throw new IOException("앱 소유 녹음만 정리할 수 있습니다");
  try(Cursor cur=c.getContentResolver().query(pending?MediaStore.setIncludePending(uri):uri,new String[]{"owner_package_name","relative_path","_display_name"},null,null,null)){
   if(cur==null||!cur.moveToFirst()||!c.getPackageName().equals(cur.getString(0))||!("Documents/LifeRecorder/"+date+"/").equals(cur.getString(1))||!name.equals(cur.getString(2)))throw new IOException("파일 소유권·위치 확인 실패");}
 }
 static JSONObject load(AtomicFile f)throws Exception{return new JSONObject(new String(f.readFully(),StandardCharsets.UTF_8));}
 static void resume(Context c,AtomicFile receipt)throws Exception{
  JSONObject state=load(receipt);File tmp=new File(root(c),state.getString("id")+".m4a");
  if(!state.optBoolean("done")&&!state.has("entry")){
   JSONObject result=build(c,state.getJSONArray("sources"),tmp);
   state.put("duration",result.getLong("duration")).put("size",tmp.length()).put("packets",result.getLong("packets")).put("digest",result.getString("digest"));
   state.put("entry",new JSONObject().put("name",state.getString("name")).put("created",state.getLong("created")).put("duration",state.getLong("duration")).put("spans",result.getJSONArray("spans")));
   DailyLog.write(receipt,state);
  }
  ContentResolver cr=c.getContentResolver();String date=state.getString("date"),name=state.getString("name"),path="Documents/LifeRecorder/"+date+"/";
  RecordingPublication.publish(new RecordingPublication.Backend(){
   public boolean done(){return state.optBoolean("done");}
   public String destination(){return state.optString("uri",null);}
   public void prepare()throws Exception{if(!tmp.exists()){
     JSONObject result=build(c,state.getJSONArray("sources"),tmp);if(!state.getString("digest").equals(result.getString("digest")))throw new IOException("복구된 병합 내용이 달라 확인이 필요합니다");} }
   RecordingPublication.Target target(Cursor cur)throws Exception{Uri uri=ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"),cur.getLong(0));assertOwned(c,uri,name,date,true);return new RecordingPublication.Target(uri.toString(),cur.getInt(1)!=0);}
   public RecordingPublication.Target find()throws Exception{try(Cursor cur=cr.query(MediaStore.setIncludePending(MediaStore.Files.getContentUri("external")),new String[]{"_id","is_pending"},"_display_name=? AND relative_path=? AND owner_package_name=?",new String[]{name,path,c.getPackageName()},null)){if(cur==null)throw new IOException("병합 대상 조회 실패");if(!cur.moveToFirst())return null;RecordingPublication.Target t=target(cur);if(cur.moveToNext())throw new IOException("병합 후보 중복 확인 필요");return t;}}
   public RecordingPublication.Target inspect(String value)throws Exception{try(Cursor cur=cr.query(MediaStore.setIncludePending(Uri.parse(value)),new String[]{"_id","is_pending"},null,null,null)){if(cur==null)throw new IOException("병합 대상 조회 실패");return cur.moveToFirst()?target(cur):null;}}
   public RecordingPublication.Target create()throws Exception{ContentValues v=new ContentValues();v.put("_display_name",name);v.put("relative_path",path);v.put("mime_type","audio/mp4");v.put("is_pending",1);Uri uri=cr.insert(MediaStore.Files.getContentUri("external"),v);if(uri==null)throw new IOException("병합 저장 공간 확보 실패");return new RecordingPublication.Target(uri.toString(),true);}
   public void remember(String uri)throws Exception{state.put("uri",uri);DailyLog.write(receipt,state);}
   public void writePending(RecordingPublication.Target t)throws Exception{assertOwned(c,Uri.parse(t.uri),name,date,true);try(InputStream in=new FileInputStream(tmp);ParcelFileDescriptor fd=cr.openFileDescriptor(Uri.parse(t.uri),"rwt")){if(fd==null)throw new IOException("병합 저장 스트림 없음");try(FileOutputStream out=new ParcelFileDescriptor.AutoCloseOutputStream(fd)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);out.flush();out.getFD().sync();}}
    verify(c,Uri.parse(t.uri),null,state.getLong("packets"),state.getString("digest"));}
   public void finish(RecordingPublication.Target t)throws Exception{ContentValues v=new ContentValues();v.put("is_pending",0);if(cr.update(Uri.parse(t.uri),v,null,null)!=1)throw new IOException("병합 저장 완료 실패");}
   public void index(RecordingPublication.Target t)throws Exception{commit(c,state,t.uri);}
   public void complete()throws Exception{state.put("done",true);DailyLog.write(receipt,state);}
   public void cleanup()throws Exception{tmp.delete();if(cleanSources(c,state,ExportSnapshot.protectedUris(c,System.currentTimeMillis())))receipt.delete();}
  });
 }
 static void commit(Context c,JSONObject state,String uri)throws Exception{
  Set<String> names=new HashSet<>(),uris=new HashSet<>();JSONArray a=state.getJSONArray("sources");for(int i=0;i<a.length();i++){names.add(a.getJSONObject(i).getString("name"));uris.add(a.getJSONObject(i).getString("uri"));}
  JSONObject output=new JSONObject().put("name",state.getString("name")).put("uri",uri).put("size",state.getLong("size")).put("durMs",state.getLong("duration")).put("created",state.getLong("created")).put("merged",true).put("windowStart",state.getLong("window")).put("recovered",false);
  DailyLog.replaceMerged(c,state.getString("date"),names,state.getJSONObject("entry"));RecordingStore.replace(c,uris,output);
 }
 static boolean cleanSources(Context c,JSONObject state,Set<String> protectedUris)throws Exception{
  if(!state.optBoolean("done"))return false;boolean complete=true;JSONArray sources=state.getJSONArray("sources");ContentResolver cr=c.getContentResolver();
  for(int i=0;i<sources.length();i++){JSONObject row=sources.getJSONObject(i);String value=row.getString("uri");if(value.equals(state.optString("uri")))throw new IOException("원본·결과 식별자 충돌");if(protectedUris.contains(value)){complete=false;continue;}
   Uri uri=Uri.parse(value);try(Cursor cur=cr.query(uri,new String[]{"_id"},null,null,null)){if(cur==null){complete=false;continue;}if(!cur.moveToFirst())continue;}
   assertOwned(c,uri,row.getString("name"),state.getString("date"),false);if(cr.delete(uri,"owner_package_name=? AND _display_name=?",new String[]{c.getPackageName(),row.getString("name")})!=1)complete=false;
  }return complete;
 }
 static int firstOverlap(JSONArray spans,long offset)throws Exception{int a=0,b=spans.length();while(a<b){int m=(a+b)>>>1;if(spans.getJSONObject(m).getLong("to")<=offset)a=m+1;else b=m;}return a;}
 static long wallAt(JSONArray spans,long offset)throws Exception{int a=0,b=spans.length();while(a<b){int m=(a+b)>>>1;if(spans.getJSONObject(m).getLong("from")<=offset)a=m+1;else b=m;}JSONObject p=spans.getJSONObject(Math.max(0,a-1));return p.getLong("start")+offset-p.getLong("from");}
 static void mapPacket(JSONArray output,JSONArray source,long sourceFrom,long sourceTo,long dest)throws Exception{
  for(int i=firstOverlap(source,sourceFrom);i<source.length();i++){JSONObject p=source.getJSONObject(i);if(p.getLong("from")>=sourceTo)break;long a=Math.max(sourceFrom,p.getLong("from")),b=Math.min(sourceTo,p.getLong("to"));if(a>=b)continue;append(output,dest+a-sourceFrom,dest+b-sourceFrom,p.getLong("start")+a-p.getLong("from"));}
 }
 static void append(JSONArray spans,long from,long to,long wall)throws Exception{
  if(to<=from)return;JSONObject previous=spans.length()>0?spans.getJSONObject(spans.length()-1):null;
  if(previous!=null&&previous.getLong("to")==from&&previous.getLong("end")==wall){previous.put("to",to).put("end",wall+to-from);return;}
  spans.put(new JSONObject().put("from",from).put("to",to).put("start",wall).put("end",wall+to-from));
 }
 static final class Source implements AutoCloseable {
  final MediaExtractor extractor=new MediaExtractor();final JSONArray spans;final MediaFormat format;final int id;long origin,wall;
  Source(Context c,JSONObject row,int id)throws Exception{this.id=id;spans=row.getJSONArray("spans");try{extractor.setDataSource(c,Uri.parse(row.getString("uri")),null);if(extractor.getTrackCount()!=1)throw new IOException("단일 AAC 트랙만 병합합니다");format=extractor.getTrackFormat(0);if(!"audio/mp4a-latm".equals(format.getString(MediaFormat.KEY_MIME)))throw new IOException("AAC 녹음만 병합합니다");extractor.selectTrack(0);origin=extractor.getSampleTime();if(origin<0)throw new IOException("빈 오디오 트랙");update();}catch(Exception e){extractor.release();throw e;}}
  void update()throws Exception{wall=wallAt(spans,(extractor.getSampleTime()-origin)/1000);}
  public void close(){extractor.release();}
 }
 static boolean sameFormat(MediaFormat a,MediaFormat b){for(String key:new String[]{MediaFormat.KEY_SAMPLE_RATE,MediaFormat.KEY_CHANNEL_COUNT})if(a.getInteger(key)!=b.getInteger(key))return false;for(String key:new String[]{"csd-0","csd-1"}){ByteBuffer x=a.getByteBuffer(key),y=b.getByteBuffer(key);if(x==null?y!=null:y==null||!x.duplicate().equals(y.duplicate()))return false;}return true;}
 static JSONObject build(Context c,JSONArray rows,File target)throws Exception{
  List<Source> sources=new ArrayList<>();MediaMuxer muxer=null;boolean started=false;long packets=0;JSONArray spans=new JSONArray();MessageDigest digest=MessageDigest.getInstance("SHA-256");
  try{
   for(int i=0;i<rows.length();i++)sources.add(new Source(c,rows.getJSONObject(i),i));MediaFormat format=sources.get(0).format;
   for(Source s:sources)if(!sameFormat(format,s.format))throw new IOException("오디오 형식이 달라 원본을 유지합니다");int rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE);if(rate!=16000||format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)!=1)throw new IOException("16kHz 모노 AAC만 자동 정리합니다");
   // AAC-LC uses 1024 samples per packet. Copy compressed payloads; no decode/re-encode.
   ByteBuffer csd=format.getByteBuffer("csd-0");if(csd==null||!csd.hasRemaining()||((csd.get(csd.position())&255)>>3)!=2)throw new IOException("AAC-LC 프로파일 확인 필요");
   muxer=new MediaMuxer(target.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);int track=muxer.addTrack(format);muxer.start();started=true;
   PriorityQueue<Source> queue=new PriorityQueue<>(Comparator.<Source>comparingLong(s->s.wall).thenComparingInt(s->s.id));queue.addAll(sources);
   ByteBuffer buffer=ByteBuffer.allocate(65536);MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
   while(!queue.isEmpty()){Source s=queue.remove();buffer.clear();int size=s.extractor.readSampleData(buffer,0);if(size<=0)throw new IOException("AAC 패킷 읽기 실패");long src=(s.extractor.getSampleTime()-s.origin)/1000,dest=packets*1024000L/rate;
    buffer.position(0);buffer.limit(size);digest.update(buffer.duplicate());info.set(0,size,packets*1024000000L/rate,MediaCodec.BUFFER_FLAG_KEY_FRAME);muxer.writeSampleData(track,buffer,info);mapPacket(spans,s.spans,src,src+1024000L/rate,dest);packets++;
    if(s.extractor.advance()){s.update();queue.add(s);}
   }
   if(packets==0||spans.length()==0)throw new IOException("병합할 음성·타임로그 없음");muxer.stop();started=false;muxer.release();muxer=null;
   try(RandomAccessFile sync=new RandomAccessFile(target,"rw")){sync.getFD().sync();}String hash=hex(digest.digest());verify(c,null,target,packets,hash);
   return new JSONObject().put("spans",spans).put("packets",packets).put("digest",hash).put("duration",packets*1024000L/rate);
  }finally{for(Source s:sources)s.close();if(muxer!=null){if(started)try{muxer.stop();}catch(Exception ignored){}muxer.release();}}
 }
 static void verify(Context c,Uri uri,File file,long expected,String hash)throws Exception{
  MediaExtractor e=new MediaExtractor();try{if(file!=null)e.setDataSource(file.getAbsolutePath());else e.setDataSource(c,uri,null);if(e.getTrackCount()!=1)throw new IOException("병합 결과 트랙 검증 실패");e.selectTrack(0);ByteBuffer buffer=ByteBuffer.allocate(65536);MessageDigest digest=MessageDigest.getInstance("SHA-256");long count=0,previous=-1;
   while(true){buffer.clear();int size=e.readSampleData(buffer,0);if(size<0)break;long pts=e.getSampleTime();if(pts<=previous)throw new IOException("병합 재생 시각 검증 실패");previous=pts;buffer.position(0);buffer.limit(size);digest.update(buffer);count++;e.advance();}
   if(count!=expected||!hash.equals(hex(digest.digest())))throw new IOException("병합 음성 내용 검증 실패");
  }finally{e.release();}
 }
}
