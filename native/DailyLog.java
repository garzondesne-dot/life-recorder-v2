package com.lifeblackbox.recorder;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/** Canonical per-day metadata. Rebuild one UTF-8 TXT per date, never one TXT per audio. */
final class DailyLog {
    static String day(long t){return new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date(t));}
    static String dateTime(long t){return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS XXX",Locale.US).format(new Date(t));}
    static String offset(long n){return String.format(Locale.US,"%02d:%02d:%02d.%03d",n/3600000,(n/60000)%60,(n/1000)%60,n%1000);}
    static AtomicFile manifest(Context c,String date){File dir=new File(c.getFilesDir(),"daily-timeline");dir.mkdirs();return new AtomicFile(new File(dir,date+".json"));}
    static JSONObject read(Context c,String date)throws Exception{AtomicFile f=manifest(c,date);return f.getBaseFile().exists()?new JSONObject(new String(f.readFully(),StandardCharsets.UTF_8)):new JSONObject();}
    static void write(AtomicFile f,JSONObject o)throws Exception{FileOutputStream out=null;try{out=f.startWrite();out.write(o.toString().getBytes(StandardCharsets.UTF_8));f.finishWrite(out);}catch(Exception e){if(out!=null)f.failWrite(out);throw e;}}
    static synchronized void record(Context c,File journal,String name,long created,long duration)throws Exception{
        File map=new File(journal.getAbsolutePath()+".timeline");Map<String,JSONArray> days=new TreeMap<>();
        if(map.exists())try(BufferedReader r=new BufferedReader(new InputStreamReader(new FileInputStream(map),StandardCharsets.UTF_8))){String line;
            while((line=r.readLine())!=null){JSONObject span;try{span=new JSONObject(line);}catch(Exception truncated){continue;}
                long a=span.getLong("start"),b=span.getLong("end"),from=span.getLong("from"),to=Math.min(duration,span.getLong("to"));
                if(from>=to)continue;b=Math.min(b,a+to-from);
                while(a<b){Calendar midnight=Calendar.getInstance();midnight.setTimeInMillis(a);midnight.add(Calendar.DATE,1);midnight.set(Calendar.HOUR_OF_DAY,0);midnight.set(Calendar.MINUTE,0);midnight.set(Calendar.SECOND,0);midnight.set(Calendar.MILLISECOND,0);
                    long end=Math.min(b,midnight.getTimeInMillis());String day=day(a);
                    JSONArray arr=days.get(day);if(arr==null){arr=new JSONArray();days.put(day,arr);}arr.put(new JSONObject().put("start",a).put("end",end).put("from",from).put("to",from+end-a));from+=end-a;a=end;
                }
            }
        }
        if(days.isEmpty())days.put(day(created),new JSONArray());
        for(Map.Entry<String,JSONArray> e:days.entrySet()){
            JSONObject all=read(c,e.getKey());all.put(journal.getName(),new JSONObject().put("name",name).put("created",created).put("duration",duration).put("spans",e.getValue()));write(manifest(c,e.getKey()),all);
            publish(c,e.getKey());
        }
    }
    static synchronized String render(Context c,String date)throws Exception{
        JSONObject all=read(c,date);StringBuilder text=new StringBuilder("Life Blackbox 하루 타임로그 — "+date+"\n기기 로컬 시간대: "+TimeZone.getDefault().getID()+"\n이 문서는 전사문이 아닙니다. 대화 내용은 음성 또는 별도 전사문을 분석하세요.\n재생 위치는 PCM 기준 근사값이며 AAC 인코딩 지연·기기 버퍼로 차이가 날 수 있습니다.\n과거 파일의 정확한 무음 구간은 추정하지 않습니다.\n\n");
        List<JSONObject> entries=new ArrayList<>();Iterator<String> keys=all.keys();while(keys.hasNext())entries.add(all.getJSONObject(keys.next()));entries.sort(Comparator.comparingLong(o->o.optLong("created")));
        for(JSONObject entry:entries){text.append("파일: ").append(entry.getString("name")).append('\n');JSONArray spans=entry.getJSONArray("spans");
            if(spans.length()==0)text.append("구간별 시각 정보 없음 (이전 버전 또는 복구 기록)\n");
            for(int i=0;i<spans.length();i++){JSONObject p=spans.getJSONObject(i);text.append("재생 ").append(offset(p.getLong("from"))).append(" ~ ").append(offset(p.getLong("to"))).append(" → 실제 ").append(dateTime(p.getLong("start"))).append(" ~ ").append(dateTime(p.getLong("end"))).append('\n');}text.append('\n');
        }return text.toString();
    }
    static synchronized Uri publish(Context c,String date)throws Exception{
        if(!date.matches("\\d{4}-\\d{2}-\\d{2}"))throw new Exception("날짜 형식 오류");
        String name="LifeRecorder_"+date+"_timelog.txt",path="Documents/LifeRecorder/"+date+"/";ContentResolver cr=c.getContentResolver();Uri collection=MediaStore.Files.getContentUri("external"),uri=null;
        try(Cursor rows=cr.query(collection,new String[]{"_id"},"_display_name=? AND relative_path=? AND owner_package_name=?",new String[]{name,path,c.getPackageName()},"_id ASC")){if(rows!=null&&rows.moveToFirst())uri=ContentUris.withAppendedId(collection,rows.getLong(0));}
        if(uri==null){ContentValues v=new ContentValues();v.put("_display_name",name);v.put("mime_type","text/plain");v.put("relative_path",path);uri=cr.insert(collection,v);}
        if(uri==null)throw new IOException("하루 타임로그 생성 실패");
        try(OutputStream out=cr.openOutputStream(uri,"wt")){if(out==null)throw new IOException("타임로그 열기 실패");out.write(render(c,date).getBytes(StandardCharsets.UTF_8));}return uri;
    }
    static synchronized void repairPending(Context c)throws Exception {
        File[] maps=new File(c.getFilesDir(),"pending").listFiles((d,n)->n.endsWith(".aac.timeline"));
        if(maps==null)return;JSONArray recordings=RecordingStore.list(c);
        for(File map:maps){File journal=new File(map.getAbsolutePath().replaceFirst("\\.timeline$",""));
            if(journal.exists())continue;String normal=journal.getName().replace(".aac",".m4a"),recovered=journal.getName().replace(".aac","_복구.m4a");
            for(int i=0;i<recordings.length();i++){JSONObject f=recordings.getJSONObject(i);String name=f.optString("name");if(normal.equals(name)||recovered.equals(name)){
                record(c,journal,name,f.optLong("created"),f.optLong("durMs"));map.delete();break;
            }}
        }
    }
    static synchronized long[] bounds(Context c,String name)throws Exception{
        long from=Long.MAX_VALUE,to=0;JSONArray days=datesFor(c,name);
        for(int i=0;i<days.length();i++){JSONObject all=read(c,days.getString(i));Iterator<String> keys=all.keys();while(keys.hasNext()){
            JSONObject entry=all.getJSONObject(keys.next());if(!name.equals(entry.optString("name")))continue;
            JSONArray spans=entry.getJSONArray("spans");for(int j=0;j<spans.length();j++){JSONObject span=spans.getJSONObject(j);from=Math.min(from,span.getLong("start"));to=Math.max(to,span.getLong("end"));}
        }}return new long[]{from==Long.MAX_VALUE?0:from,to};
    }
    static synchronized JSONArray datesFor(Context c,String name)throws Exception{
        JSONArray result=new JSONArray();File[] files=new File(c.getFilesDir(),"daily-timeline").listFiles((d,n)->n.endsWith(".json"));
        if(files!=null)for(File f:files){String date=f.getName().replace(".json","");JSONObject all=read(c,date);Iterator<String> keys=all.keys();while(keys.hasNext())if(name.equals(all.getJSONObject(keys.next()).optString("name"))){result.put(date);break;}}
        return result;
    }
}
