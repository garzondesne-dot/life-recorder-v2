package com.lifeblackbox.recorder;
import android.content.Context;
import android.net.Uri;
import androidx.core.content.FileProvider;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Frozen private export context; public daily TXT remains one file per date. */
final class ExportSnapshot {
 static File root(Context c){File dir=new File(c.getCacheDir(),"exports");dir.mkdirs();return dir;}
 static File dir(Context c,String token)throws IOException{if(token==null||!token.matches("[a-f0-9-]{36}"))throw new IOException("내보내기 식별자 오류");return new File(root(c),token);}
 static synchronized JSONObject create(Context c,String date,long cutoff,JSONArray source)throws Exception{
  if(!date.matches("\\d{4}-\\d{2}-\\d{2}"))throw new IOException("날짜 오류");
  prune(c,System.currentTimeMillis());
  // Refresh owned URIs while holding the same lock as merge/cleanup; retain selected external files.
  JSONArray fresh=RecordingStore.list(c);Set<String> seen=new HashSet<>();for(int i=0;i<fresh.length();i++)seen.add(fresh.getJSONObject(i).optString("uri"));
  for(int i=0;i<source.length();i++){JSONObject row=source.getJSONObject(i);String uri=row.optString("uri");if(!uri.startsWith("content://media/")&&seen.add(uri))fresh.put(row);}source=fresh;
  Map<String,JSONObject> summaries=DailyLog.summaries(c);
  JSONArray selected=new JSONArray();Set<String> names=new HashSet<>();
  for(int i=0;i<source.length();i++){JSONObject f=new JSONObject(source.getJSONObject(i).toString());String name=f.optString("name");
   JSONObject summary=summaries.get(name);JSONArray days=summary==null?new JSONArray():summary.getJSONArray("days");boolean include=DailyLog.day(f.optLong("created")).equals(date);
   for(int j=0;j<days.length();j++)if(date.equals(days.getString(j)))include=true;
   if(!include||f.optLong("created")>cutoff)continue;
   if(RecordingWindow.key(name)>0&&summary!=null&&summary.optLong("wallEnd")>cutoff)throw new IOException("준비 기준 이후 녹음이 추가되었습니다. 다시 준비해 주세요");
   f.put("wallStart",summary==null?0:summary.optLong("wallStart")).put("wallEnd",summary==null?0:summary.optLong("wallEnd"));selected.put(f);names.add(name);
  }
  String token=UUID.randomUUID().toString();File folder=dir(c,token);folder.mkdirs();
  String logName="LifeRecorder_"+date+"_timelog.txt";
  try(FileOutputStream out=new FileOutputStream(new File(folder,logName))){out.write(DailyLog.renderFrozen(c,date,cutoff,names).getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
  JSONObject data=new JSONObject().put("token",token).put("date",date).put("cutoff",cutoff).put("files",selected).put("logName",logName);
  try(FileOutputStream out=new FileOutputStream(new File(folder,"context.json"))){out.write(data.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}return data;
 }
 static void prune(Context c,long now){File[] dirs=root(c).listFiles();if(dirs==null)return;for(File dir:dirs){if(!dir.isDirectory()||!dir.getName().matches("[a-f0-9-]{36}"))continue;File context=new File(dir,"context.json");long modified=context.exists()?context.lastModified():dir.lastModified();if(now-modified<24*3600000L)continue;
   File[] files=dir.listFiles();if(files!=null)for(File f:files)if(f.isFile()&&(f.getName().equals("context.json")||f.getName().matches("LifeRecorder_\\d{4}-\\d{2}-\\d{2}_timelog\\.txt")))f.delete();dir.delete();}}
 static synchronized void protectUri(Context c,String uri)throws Exception{android.content.SharedPreferences p=c.getSharedPreferences("share-leases",0);JSONObject leases=new JSONObject(p.getString("uris","{}"));leases.put(uri,System.currentTimeMillis()+24*3600000L);if(!p.edit().putString("uris",leases.toString()).commit())throw new IOException("파일 공유 보호 기록 저장 실패");}
 static Set<String> protectedUris(Context c,long now)throws Exception{Set<String> result=new HashSet<>();android.content.SharedPreferences p=c.getSharedPreferences("share-leases",0);JSONObject leases=new JSONObject(p.getString("uris","{}")),kept=new JSONObject();Iterator<String> keys=leases.keys();while(keys.hasNext()){String uri=keys.next();long until=leases.getLong(uri);if(until>now){result.add(uri);kept.put(uri,until);}}if(kept.length()!=leases.length())p.edit().putString("uris",kept.toString()).apply();File[] dirs=root(c).listFiles();if(dirs!=null)for(File dir:dirs){if(!dir.isDirectory()||!dir.getName().matches("[a-f0-9-]{36}"))continue;File f=new File(dir,"context.json");if(!f.exists()||now-f.lastModified()>=24*3600000L)continue;JSONArray a=read(c,dir.getName()).getJSONArray("files");for(int i=0;i<a.length();i++)result.add(a.getJSONObject(i).getString("uri"));}return result;}
 static JSONObject read(Context c,String token)throws Exception{
  try(InputStream in=new FileInputStream(new File(dir(c,token),"context.json"))){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return new JSONObject(out.toString("UTF-8"));}
 }
 static Uri logUri(Context c,JSONObject snapshot)throws Exception{return FileProvider.getUriForFile(c,c.getPackageName()+".exports",new File(dir(c,snapshot.getString("token")),snapshot.getString("logName")));}
}
