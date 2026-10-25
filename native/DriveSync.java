package com.lifeblackbox.recorder;

import android.accounts.Account;
import android.content.*;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.work.*;
import com.google.android.gms.auth.api.identity.*;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Tasks;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Persistent network-constrained queue; access tokens stay in memory. */
public final class DriveSync extends Worker {
    static final String DRIVE="https://www.googleapis.com/auth/drive.file";
    static final String EMAIL="https://www.googleapis.com/auth/userinfo.email";
    static final String API="https://www.googleapis.com/drive/v3/files";
    static final String WORK="life-drive-upload";
    static android.content.SharedPreferences prefs(Context c){return c.getSharedPreferences("drive",0);}
    public DriveSync(@NonNull Context c,@NonNull WorkerParameters p){super(c,p);}
    static AuthorizationRequest request(String email){
        AuthorizationRequest.Builder b=AuthorizationRequest.builder().setRequestedScopes(Arrays.asList(new Scope(DRIVE),new Scope(EMAIL)));
        if(email!=null&&!email.isEmpty())b.setAccount(new Account(email,"com.google"));return b.build();
    }
    static void schedule(Context c){
        if(!prefs(c).getBoolean("enabled",false))return;
        Constraints n=new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        OneTimeWorkRequest w=new OneTimeWorkRequest.Builder(DriveSync.class).setConstraints(n)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build();
        WorkManager.getInstance(c).enqueueUniqueWork(WORK,ExistingWorkPolicy.APPEND_OR_REPLACE,w);
        PeriodicWorkRequest p=new PeriodicWorkRequest.Builder(DriveSync.class,15,TimeUnit.MINUTES).setConstraints(n).build();
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(WORK+"-periodic",ExistingPeriodicWorkPolicy.KEEP,p);
    }
    static void disconnect(Context c){prefs(c).edit().putBoolean("enabled",false).remove("email").putString("status","연결 해제됨").apply();
        WorkManager.getInstance(c).cancelUniqueWork(WORK);WorkManager.getInstance(c).cancelUniqueWork(WORK+"-periodic");}
    @NonNull @Override public Result doWork(){
        Context c=getApplicationContext();if(!prefs(c).getBoolean("enabled",false))return Result.success();
        String email=prefs(c).getString("email","");
        try{
            AuthorizationResult auth=Tasks.await(Identity.getAuthorizationClient(c).authorize(request(email)),30,TimeUnit.SECONDS);
            if(auth.hasResolution()){prefs(c).edit().putString("status","앱에서 구글 계정을 다시 연결해 주세요").apply();return Result.success();}
            String token=auth.getAccessToken();if(token==null)throw new IOException("Google 인증 토큰 없음");
            JSONArray files=RecordingStore.list(c);String root=null;int count=0;
            for(int i=0;i<files.length();i++){
                if(isStopped()||!prefs(c).getBoolean("enabled",false)||!email.equals(prefs(c).getString("email","")))return Result.success();
                JSONObject f=files.getJSONObject(i),ids=f.optJSONObject("driveAccounts");if(ids!=null&&ids.has(email))continue;
                if(root==null)root=folder(token,"LifeRecorder",null);
                String date=new java.text.SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date(f.getLong("created")));
                String parent=folder(token,date,root);
                String key=sha(f.getString("uri"));
                String q="trashed=false and '"+parent+"' in parents and appProperties has { key='lifeKey' and value='"+key+"' }";
                JSONArray found=json("GET",API+"?fields=files(id)&q="+enc(q),token,null).getJSONArray("files");
                String id;
                if(found.length()>0)id=found.getJSONObject(0).getString("id");
                else id=upload(c,token,f,parent,key);
                RecordingStore.uploaded(c,f.getString("uri"),email,id);count++;
            }
            prefs(c).edit().putString("status","동기화 완료 · "+new java.text.SimpleDateFormat("MM-dd HH:mm",Locale.US).format(new Date())).apply();
            return Result.success();
        }catch(Exception e){prefs(c).edit().putString("status","업로드 대기: "+e.getMessage()).apply();return getRunAttemptCount()<8?Result.retry():Result.success();}
    }
    static String email(String token)throws Exception{return json("GET","https://www.googleapis.com/oauth2/v3/userinfo",token,null).getString("email");}
    private static String sha(String v)throws Exception{
        byte[] b=java.security.MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format(Locale.US,"%02x",x));return s.toString();}
    private static String enc(String s)throws Exception{return URLEncoder.encode(s,"UTF-8");}
    private static String folder(String token,String name,String parent)throws Exception{
        String q="trashed=false and mimeType='application/vnd.google-apps.folder' and name='"+name+"'"+(parent==null?" and 'root' in parents":" and '"+parent+"' in parents");
        JSONArray a=json("GET",API+"?fields=files(id)&q="+enc(q),token,null).getJSONArray("files");
        if(a.length()>0)return a.getJSONObject(0).getString("id");
        JSONObject m=new JSONObject().put("name",name).put("mimeType","application/vnd.google-apps.folder");
        if(parent!=null)m.put("parents",new JSONArray().put(parent));return json("POST",API,token,m.toString()).getString("id");
    }
    private static HttpURLConnection connection(String method,String url,String token)throws Exception{
        HttpURLConnection h=(HttpURLConnection)new URL(url).openConnection();h.setRequestMethod(method);h.setConnectTimeout(20000);h.setReadTimeout(30000);h.setRequestProperty("Authorization","Bearer "+token);return h;
    }
    private static String body(HttpURLConnection h)throws Exception{
        int code=h.getResponseCode();InputStream in=code>=200&&code<300?h.getInputStream():h.getErrorStream();
        ByteArrayOutputStream out=new ByteArrayOutputStream();if(in!=null)try(InputStream src=in){byte[] b=new byte[4096];int n;while((n=src.read(b))!=-1)out.write(b,0,n);}
        String text=out.toString("UTF-8");if(code<200||code>=300)throw new IOException("Drive HTTP "+code);return text;
    }
    private static JSONObject json(String method,String url,String token,String data)throws Exception{
        HttpURLConnection h=connection(method,url,token);try{if(data!=null){h.setDoOutput(true);h.setRequestProperty("Content-Type","application/json; charset=UTF-8");try(OutputStream o=h.getOutputStream()){o.write(data.getBytes(StandardCharsets.UTF_8));}}
            return new JSONObject(body(h));}finally{h.disconnect();}
    }
    private String upload(Context c,String token,JSONObject f,String parent,String key)throws Exception{
        JSONObject m=new JSONObject().put("name",f.getString("name")).put("parents",new JSONArray().put(parent))
            .put("appProperties",new JSONObject().put("lifeKey",key));
        // Resumable protocol: interruption retries the queued file; remote key prevents duplicates after a lost final response.
        HttpURLConnection h=connection("POST","https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable",token);
        String location;try{
            h.setDoOutput(true);h.setRequestProperty("Content-Type","application/json; charset=UTF-8");h.setRequestProperty("X-Upload-Content-Type","audio/mp4");
            h.setRequestProperty("X-Upload-Content-Length",Long.toString(f.getLong("size")));
            try(OutputStream o=h.getOutputStream()){o.write(m.toString().getBytes(StandardCharsets.UTF_8));}body(h);location=h.getHeaderField("Location");
        }finally{h.disconnect();}
        if(location==null)throw new IOException("Drive 업로드 주소 없음");
        h=connection("PUT",location,token);try{
            h.setDoOutput(true);h.setFixedLengthStreamingMode(f.getLong("size"));h.setRequestProperty("Content-Type","audio/mp4");
            try(InputStream in=c.getContentResolver().openInputStream(Uri.parse(f.getString("uri")));OutputStream out=h.getOutputStream()){
                if(in==null)throw new IOException("원본 파일 없음");byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){if(isStopped()||!prefs(c).getBoolean("enabled",false))throw new IOException("동기화 중단");out.write(b,0,n);}}
            return new JSONObject(body(h)).getString("id");
        }finally{h.disconnect();}
    }
}
