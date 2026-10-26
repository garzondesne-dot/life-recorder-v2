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
  JSONArray selected=new JSONArray();Set<String> names=new HashSet<>();
  for(int i=0;i<source.length();i++){JSONObject f=new JSONObject(source.getJSONObject(i).toString());String name=f.optString("name");
   JSONArray days=DailyLog.datesFor(c,name);boolean include=DailyLog.day(f.optLong("created")).equals(date);
   for(int j=0;j<days.length();j++)if(date.equals(days.getString(j)))include=true;
   if(!include||f.optLong("created")>cutoff)continue;
   long[] bounds=DailyLog.bounds(c,name);f.put("wallStart",bounds[0]);f.put("wallEnd",bounds[1]);selected.put(f);names.add(name);
  }
  String token=UUID.randomUUID().toString();File folder=dir(c,token);folder.mkdirs();
  String logName="LifeRecorder_"+date+"_timelog.txt";
  try(FileOutputStream out=new FileOutputStream(new File(folder,logName))){out.write(DailyLog.renderFrozen(c,date,cutoff,names).getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
  JSONObject data=new JSONObject().put("token",token).put("date",date).put("cutoff",cutoff).put("files",selected).put("logName",logName);
  try(FileOutputStream out=new FileOutputStream(new File(folder,"context.json"))){out.write(data.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}return data;
 }
 static JSONObject read(Context c,String token)throws Exception{
  try(InputStream in=new FileInputStream(new File(dir(c,token),"context.json"))){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return new JSONObject(out.toString("UTF-8"));}
 }
 static Uri logUri(Context c,JSONObject snapshot)throws Exception{return FileProvider.getUriForFile(c,c.getPackageName()+".exports",new File(dir(c,snapshot.getString("token")),snapshot.getString("logName")));}
}
