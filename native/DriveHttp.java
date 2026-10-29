package com.lifeblackbox.recorder;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** Small streaming Drive REST transport. No token/session URLs are logged or bridged to JS. */
class DriveHttp {
 static final String API="https://www.googleapis.com/drive/v3/",UPLOAD="https://www.googleapis.com/upload/drive/v3/";
 final String token;final Runnable guard;
 DriveHttp(String token,Runnable guard){this.token=token;this.guard=guard;}
 static class Failure extends IOException {final int code;Failure(int code){super(code==401?"Google 연결 만료 · 다시 연결해 주세요":code==403?"Drive 권한·저장 공간·API 설정을 확인해 주세요":code==429?"Drive 요청 한도 · 자동 재시도 대기":"Drive 통신 오류 ("+code+")");this.code=code;}}
 static class Reply {int code;String body,location,range;JSONObject json()throws Exception{return body.isEmpty()?new JSONObject():new JSONObject(body);}}
 static String enc(String s){try{return URLEncoder.encode(s,"UTF-8");}catch(Exception e){throw new IllegalArgumentException(e);}}
 Reply request(String method,String url,JSONObject json,File file,long offset,int length,Map<String,String> headers)throws Exception{
  guard.run();URL target=new URL(url);if(!"https".equals(target.getProtocol())||!"www.googleapis.com".equals(target.getHost())||!(target.getPath().startsWith("/drive/v3/")||target.getPath().startsWith("/upload/drive/v3/")))throw new IOException("허용되지 않은 Drive 주소");
  HttpURLConnection h=(HttpURLConnection)target.openConnection();h.setInstanceFollowRedirects(false);h.setConnectTimeout(20000);h.setReadTimeout(30000);h.setRequestMethod(method);h.setRequestProperty("Authorization","Bearer "+token);
  for(Map.Entry<String,String> v:headers.entrySet())h.setRequestProperty(v.getKey(),v.getValue());
  try{byte[] data=json==null?null:json.toString().getBytes(StandardCharsets.UTF_8);if(data!=null||file!=null||"PUT".equals(method)){
    h.setDoOutput(true);long count=data!=null?data.length:file!=null?length:0;h.setFixedLengthStreamingMode(count);if(data!=null)h.setRequestProperty("Content-Type","application/json; charset=UTF-8");
    try(OutputStream out=h.getOutputStream()){if(data!=null)out.write(data);else if(file!=null)try(RandomAccessFile in=new RandomAccessFile(file,"r")){in.seek(offset);byte[] b=new byte[65536];int left=length;while(left>0){guard.run();int n=in.read(b,0,Math.min(left,b.length));if(n<0)throw new EOFException();out.write(b,0,n);left-=n;}}}
   }
   Reply r=new Reply();r.code=h.getResponseCode();r.location=h.getHeaderField("Location");r.range=h.getHeaderField("Range");InputStream stream=r.code>=400?h.getErrorStream():h.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream();if(stream!=null)try(InputStream in=stream){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(out.size()+n>2097152)throw new IOException("Drive 응답 크기 초과");out.write(b,0,n);}}r.body=out.toString("UTF-8");return r;
  }finally{h.disconnect();}
 }
 Reply call(String method,String url,JSONObject body)throws Exception{Reply r=request(method,url,body,null,0,0,Collections.emptyMap());if(r.code<200||r.code>=300)throw new Failure(r.code);return r;}
 JSONObject about()throws Exception{return call("GET",API+"about?fields=user(permissionId,emailAddress)",null).json().getJSONObject("user");}
 String newId()throws Exception{return call("GET",API+"files/generateIds?count=1&space=drive&type=files",null).json().getJSONArray("ids").getString(0);}
 JSONObject get(String id)throws Exception{Reply r=request("GET",API+"files/"+enc(id)+"?fields=id,name,trashed,appProperties,md5Checksum,size",null,null,0,0,Collections.emptyMap());if(r.code==404)return null;if(r.code!=200)throw new Failure(r.code);return r.json();}
 void trash(String id)throws Exception{call("PATCH",API+"files/"+enc(id),new JSONObject().put("trashed",true));}
 String folder(String name,String parent,JSONObject tags)throws Exception{
  String q="trashed=false and mimeType='application/vnd.google-apps.folder' and appProperties has { key='app' and value='LifeRecorder' } and appProperties has { key='key' and value='"+tags.getString("key")+"' }";
  if(parent!=null)q+=" and '"+parent+"' in parents";
  JSONArray a=call("GET",API+"files?q="+enc(q)+"&fields=files(id)&pageSize=100",null).json().getJSONArray("files");if(a.length()>0)return a.getJSONObject(0).getString("id");
  JSONObject m=new JSONObject().put("name",name).put("mimeType","application/vnd.google-apps.folder").put("appProperties",tags);if(parent!=null)m.put("parents",new JSONArray().put(parent));return call("POST",API+"files?fields=id",m).json().getString("id");
 }
 static long acknowledged(String range){if(range==null)return 0;try{return Long.parseLong(range.substring(range.lastIndexOf('-')+1))+1;}catch(Exception e){return 0;}}
}
