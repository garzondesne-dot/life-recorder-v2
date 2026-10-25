package com.lifeblackbox.recorder;
import android.content.*;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Small, atomic metadata index. Audio bytes remain in MediaStore. */
final class RecordingStore {
    private static AtomicFile file(Context c){return new AtomicFile(new File(c.getFilesDir(),"recordings-v2.json"));}
    static synchronized JSONArray list(Context c)throws Exception{
        AtomicFile f=file(c);if(!f.getBaseFile().exists())return new JSONArray();
        return new JSONArray(new String(f.readFully(),StandardCharsets.UTF_8));
    }
    private static void save(Context c,JSONArray a)throws Exception{
        AtomicFile f=file(c);FileOutputStream out=null;
        try{out=f.startWrite();out.write(a.toString().getBytes(StandardCharsets.UTF_8));f.finishWrite(out);}
        catch(Exception e){if(out!=null)f.failWrite(out);throw e;}
    }
    static synchronized void add(Context c,String n,String uri,long size,long ms,boolean recovered,long created)throws Exception{
        JSONArray a=list(c);a.put(new JSONObject().put("name",n).put("uri",uri).put("size",size)
            .put("durMs",ms).put("created",created).put("recovered",recovered));save(c,a);
    }
    static synchronized void uploaded(Context c,String uri,String account,String id)throws Exception{
        JSONArray a=list(c);for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);if(uri.equals(o.optString("uri"))){
            JSONObject ids=o.optJSONObject("driveAccounts");if(ids==null)ids=new JSONObject();ids.put(account,id);o.put("driveAccounts",ids);}}
        save(c,a);
    }
}
