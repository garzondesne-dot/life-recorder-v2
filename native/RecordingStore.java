package com.lifeblackbox.recorder;
import android.content.*;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Small, atomic metadata index. Audio bytes remain in MediaStore. */
final class RecordingStore {
    private static AtomicFile file(Context c){return new AtomicFile(new File(c.getFilesDir(),"recordings-v2.json"));}
    private static JSONArray raw(Context c)throws Exception{
        AtomicFile f=file(c);if(!f.exists())return new JSONArray();
        return new JSONArray(new String(f.readFully(),StandardCharsets.UTF_8));
    }
    static synchronized JSONArray list(Context c)throws Exception{
        JSONArray all=raw(c), kept=new JSONArray();
        for(int i=0;i<all.length();i++){
            JSONObject row=all.getJSONObject(i);
            // An empty successful query proves deletion. Permission/provider failures do not.
            boolean missing=false;
            try(android.database.Cursor cursor=c.getContentResolver().query(
                    android.net.Uri.parse(row.getString("uri")),
                    new String[]{android.provider.MediaStore.MediaColumns._ID},null,null,null)){
                missing=cursor!=null&&!cursor.moveToFirst();
            }catch(Exception unavailable){ /* retain metadata until absence can be confirmed */ }
            if(!missing)kept.put(row);
        }
        if(kept.length()!=all.length())save(c,kept);
        return kept;
    }
    private static void save(Context c,JSONArray a)throws Exception{
        AtomicFile f=file(c);FileOutputStream out=null;
        try{out=f.startWrite();out.write(a.toString().getBytes(StandardCharsets.UTF_8));f.finishWrite(out);}
        catch(Exception e){if(out!=null)f.failWrite(out);throw e;}
    }
    static synchronized void add(Context c,String n,String uri,long size,long ms,boolean recovered,long created)throws Exception{
        JSONArray a=raw(c);
        for(int i=0;i<a.length();i++)if(uri.equals(a.getJSONObject(i).optString("uri")))return;
        a.put(new JSONObject().put("name",n).put("uri",uri).put("size",size)
            .put("durMs",ms).put("created",created).put("recovered",recovered));save(c,a);
    }
}
