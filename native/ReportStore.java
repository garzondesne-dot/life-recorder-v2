package com.lifeblackbox.recorder;

import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

/** Private, atomic, opt-in report library. No audio, network, retention or background work. */
final class ReportStore {
 static String scope(Context c)throws Exception {
  android.content.SharedPreferences p=DriveBackup.prefs(c);
  String owner=p.getBoolean("enabled",false)?"google:"+p.getString("account",""):"device-local";
  if(owner.equals("google:"))throw new IOException("Google 계정을 확인해 주세요");
  byte[] bytes=MessageDigest.getInstance("SHA-256").digest(owner.getBytes(StandardCharsets.UTF_8));StringBuilder h=new StringBuilder();for(byte b:bytes)h.append(String.format(Locale.ROOT,"%02x",b&255));return h.toString();
 }
 static File root(Context c,String scope)throws Exception {if(!scope(c).equals(scope))throw new IOException("계정이 변경되었습니다. 보고서를 다시 열어 주세요");File d=new File(c.getFilesDir(),"reports-v1/"+scope);if(!d.exists()&&!d.mkdirs())throw new IOException("보고서 폴더 생성 실패");return d;}
 static String date(String s)throws Exception {if(s==null||!s.matches("\\d{4}-\\d{2}-\\d{2}")||!LocalDate.parse(s).toString().equals(s))throw new IOException("날짜를 확인해 주세요");return s;}
 static String id(String s)throws Exception {if(s==null||!s.matches("[0-9a-f-]{36}"))throw new IOException("잘못된 기록 ID");UUID.fromString(s);return s;}
 static String text(JSONObject o,String k,int max)throws Exception {Object v=o.opt(k);if(v==null||v==JSONObject.NULL)return "";if(!(v instanceof String)||((String)v).length()>max)throw new IOException("입력 길이 또는 형식을 확인해 주세요: "+k);return (String)v;}
 static JSONObject read(File f)throws Exception {try{return new JSONObject(new String(new AtomicFile(f).readFully(),StandardCharsets.UTF_8));}catch(FileNotFoundException e){if(f.exists()||new File(f+".bak").exists())throw e;return new JSONObject();}}
 static void write(File f,JSONObject o)throws Exception {byte[] b=o.toString().getBytes(StandardCharsets.UTF_8);if(b.length>1500000)throw new IOException("보고서 크기가 너무 큽니다");AtomicFile a=new AtomicFile(f);FileOutputStream out=null;try{out=a.startWrite();out.write(b);a.finishWrite(out);}catch(Exception e){if(out!=null)a.failWrite(out);throw e;}}
 static JSONArray array(JSONObject o,String k,int max)throws Exception {JSONArray a=o.optJSONArray(k);if(a==null)throw new IOException("보고서 항목을 확인해 주세요: "+k);if(a.length()>max)throw new IOException("항목이 너무 많습니다: "+k);return a;}
 static JSONObject normalize(JSONObject in)throws Exception {
  if(in.optBoolean("sample",false))throw new IOException("샘플은 실제 보고서로 저장할 수 없습니다");
  JSONObject out=new JSONObject().put("origin","manual-import").put("schema",1);
  JSONArray summary=array(in,"summary",3),clean=new JSONArray();if(summary.length()==0)throw new IOException("핵심 요약을 입력해 주세요");for(int i=0;i<summary.length();i++){String s=summary.getString(i);if(s.length()>2000||s.trim().isEmpty())throw new IOException("핵심 요약을 확인해 주세요");clean.put(s);}out.put("summary",clean);
  for(String key:new String[]{"sections","todos","speakers"}){
   JSONArray a=array(in,key,key.equals("sections")?6:key.equals("todos")?100:50),next=new JSONArray();
   for(int i=0;i<a.length();i++){
    JSONObject row=a.getJSONObject(i),v=new JSONObject().put("id",Integer.toString(i));
    if(key.equals("sections")){String topic=text(row,"topic",20);if(!Arrays.asList("todo","speech","brief","relation","memory","emotion").contains(topic))throw new IOException("알 수 없는 분석 항목");v.put("topic",topic).put("text",text(row,"text",30000)).put("evidence",text(row,"evidence",3000));}
    if(key.equals("todos")){String title=text(row,"title",500);if(title.trim().isEmpty())throw new IOException("할 일 제목이 비었습니다");String due=text(row,"due",10);if(!due.isEmpty())date(due);v.put("title",title).put("assignee",text(row,"assignee",100)).put("due",due).put("evidence",text(row,"evidence",3000));}
    if(key.equals("speakers"))v.put("label",text(row,"label",100)).put("name",text(row,"name",100)).put("relation",text(row,"relation",200)).put("note",text(row,"note",3000));
    next.put(v);
   }out.put(key,next);
  }
  JSONArray priorities=array(in,"priorities",6);LinkedHashSet<String> keys=new LinkedHashSet<>();for(int i=0;i<priorities.length();i++){String k=priorities.getString(i);if(!Arrays.asList("todo","speech","brief","relation","memory","emotion").contains(k))throw new IOException("잘못된 우선순위");keys.add(k);}out.put("priorities",new JSONArray(keys)).put("extra",text(in,"extra",1000)).put("length",text(in,"length",20));
  return out.put("coverage",text(in,"coverage",2000));
 }
 static synchronized JSONObject save(Context c,String scope,String day,JSONObject report)throws Exception {File base=root(c,scope);day=date(day);JSONObject out=normalize(report);String id=UUID.randomUUID().toString();out.put("id",id).put("date",day).put("savedAt",System.currentTimeMillis());File dir=new File(base,day);if(!dir.exists()&&!dir.mkdirs())throw new IOException("저장 폴더 생성 실패");write(new File(dir,id+".json"),out);return out;}
 static synchronized JSONObject load(Context c,String scope,String day,String version)throws Exception {
  File base=root(c,scope),dir=new File(base,date(day));List<JSONObject> rows=new ArrayList<>();File[] files=dir.listFiles();if(files!=null)for(File f:files)if(f.getName().matches("[0-9a-f-]{36}\\.json")){JSONObject r=read(f);rows.add(r);}
  rows.sort((a,b)->Long.compare(b.optLong("savedAt"),a.optLong("savedAt")));JSONArray versions=new JSONArray();JSONObject selected=new JSONObject();for(JSONObject r:rows){versions.put(new JSONObject().put("id",r.getString("id")).put("savedAt",r.getLong("savedAt")));if((version==null||version.isEmpty())&&selected.length()==0||r.optString("id").equals(version))selected=r;}
  if(version!=null&&!version.isEmpty()&&selected.length()==0)throw new IOException("해당 보고서가 없습니다");JSONObject review=selected.length()==0?new JSONObject():read(new File(dir,selected.getString("id")+".review.json"));
  JSONArray links=review.optJSONArray("links"),valid=new JSONArray();if(links!=null)for(int i=0;i<links.length();i++){JSONObject link=links.getJSONObject(i);String person=link.optString("personId");id(person);if(new File(base,"people/"+person+".json").exists())valid.put(link);}review.put("links",valid);
  return new JSONObject().put("report",selected).put("versions",versions).put("review",review).put("people",people(base));
 }
 static JSONArray people(File base)throws Exception {JSONArray people=new JSONArray();File[] files=new File(base,"people").listFiles();if(files!=null)for(File f:files)if(f.getName().matches("[0-9a-f-]{36}\\.json"))people.put(read(f));return people;}
 static synchronized void review(Context c,String scope,String day,String version,JSONObject input)throws Exception {
  File base=root(c,scope),dir=new File(base,date(day));version=id(version);JSONObject report=read(new File(dir,version+".json"));if(report.length()==0)throw new IOException("보고서가 없습니다");
  JSONArray todos=array(input,"todos",100),next=new JSONArray();Set<Integer> seen=new HashSet<>();for(int i=0;i<todos.length();i++){JSONObject t=todos.getJSONObject(i);int n=t.getInt("index");if(n<0||n>=report.getJSONArray("todos").length()||!seen.add(n))throw new IOException("잘못된 할 일");String due=text(t,"due",10);if(!due.isEmpty())date(due);String title=text(t,"title",500);if(title.trim().isEmpty())throw new IOException("제목을 입력해 주세요");next.put(new JSONObject().put("index",n).put("title",title).put("assignee",text(t,"assignee",100)).put("due",due).put("selected",t.optBoolean("selected",false)));}
  JSONArray links=array(input,"links",50),clean=new JSONArray();seen.clear();for(int i=0;i<links.length();i++){JSONObject link=links.getJSONObject(i);int n=link.getInt("index");String person=id(link.getString("personId"));if(n<0||n>=report.getJSONArray("speakers").length()||!seen.add(n)||!new File(base,"people/"+person+".json").exists())throw new IOException("화자 연결을 확인해 주세요");clean.put(new JSONObject().put("index",n).put("personId",person));}
  write(new File(dir,version+".review.json"),new JSONObject().put("todos",next).put("links",clean));
 }
 static synchronized JSONObject person(Context c,String scope,JSONObject input)throws Exception {File base=root(c,scope),dir=new File(base,"people");if(!dir.exists()&&!dir.mkdirs())throw new IOException("인물 폴더 생성 실패");String key=input.optString("id");if(key.isEmpty())key=UUID.randomUUID().toString();else{id(key);if(!new File(dir,key+".json").exists())throw new IOException("인물이 없습니다");}String name=text(input,"name",100).trim();if(name.isEmpty())throw new IOException("확인한 이름을 입력해 주세요");JSONObject p=new JSONObject().put("id",key).put("name",name).put("relation",text(input,"relation",200)).put("note",text(input,"note",3000)).put("confirmed",true).put("updatedAt",System.currentTimeMillis());write(new File(dir,key+".json"),p);return p;}
 static synchronized void deletePerson(Context c,String scope,String person)throws Exception {File f=new File(root(c,scope),"people/"+id(person)+".json");new AtomicFile(f).delete();if(f.exists())throw new IOException("인물 삭제 실패");}
 static synchronized void delete(Context c,String scope,String day)throws Exception {File dir=new File(root(c,scope),date(day));File[] files=dir.listFiles();if(files!=null)for(File f:files)if(!f.delete())throw new IOException("일부 보고서를 삭제하지 못했습니다");if(dir.exists()&&!dir.delete())throw new IOException("폴더 삭제 실패");}
}
