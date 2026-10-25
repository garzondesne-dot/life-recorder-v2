package com.lifeblackbox.recorder;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.content.pm.PackageManager;
import com.getcapacitor.*;
import com.getcapacitor.annotation.CapacitorPlugin;
import org.json.*;
import java.util.*;

/** User-initiated, local multi-file handoff. Never uploads or uses account credentials. */
@CapacitorPlugin(name="RecordingExport")
public class RecordingExportPlugin extends Plugin {
    private final Map<String,Long> allowed = new HashMap<>();
    private final Map<String,JSONObject> catalog = new HashMap<>();
    private static final long MAX_BYTES=100L*1024*1024;
    private static final String GEMINI="com.google.android.apps.bard";
    private volatile boolean scanning;

    @PluginMethod public void chooseFolder(PluginCall call) {
        getActivity().runOnUiThread(()->((MainActivity)getActivity()).chooseRecordingFolder(call,this));
    }
    @PluginMethod public void currentRecordings(PluginCall call) {
        new Thread(()->{try{
            JSONArray a=RecordingStore.list(getContext()), clean=new JSONArray();
            synchronized(allowed){allowed.clear();catalog.clear();for(int i=0;i<a.length();i++){
                JSONObject f=a.getJSONObject(i);String uri=f.getString("uri");
                // Audio removed outside this app should not appear in a share request.
                try(android.os.ParcelFileDescriptor fd=getContext().getContentResolver().openFileDescriptor(Uri.parse(uri),"r")){
                    if(fd==null)continue;
                }catch(Exception missing){continue;}
                long[] bounds=DailyLog.bounds(getContext(),f.optString("name"));f.put("wallStart",bounds[0]).put("wallEnd",bounds[1]);
                f.put("days",DailyLog.datesFor(getContext(),f.optString("name")));allowed.put(uri,f.optLong("size"));catalog.put(uri,f);clean.put(f);
            }}
            JSObject o=new JSObject();o.put("files",clean);o.put("folder","앱에 저장된 녹음 전체");call.resolve(o);
        }catch(Exception e){call.reject("녹음 목록을 불러오지 못했습니다: "+e.getMessage());}},"Recording-List").start();
    }
    void readFolder(Uri tree,PluginCall call) {
        if(scanning){call.reject("폴더를 읽는 중입니다");return;}scanning=true;
        new Thread(()->{try{
            JSONArray files=new JSONArray();Map<String,Long> next=new HashMap<>();Set<String> seen=new HashSet<>();
            ArrayDeque<String> todo=new ArrayDeque<>();todo.add(DocumentsContract.getTreeDocumentId(tree));int visited=0;
            ContentResolver cr=getContext().getContentResolver();
            while(!todo.isEmpty()){
                String parent=todo.removeFirst();if(!seen.add(parent))continue;
                if(++visited>10000)throw new Exception("하위 폴더가 너무 많습니다. 날짜 폴더를 선택해 주세요");
                Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,parent);
                String[] cols={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED};
                try(Cursor cur=cr.query(children,cols,null,null,null)){
                    if(cur==null)throw new Exception("이 폴더를 읽을 수 없습니다");
                    while(cur.moveToNext()){
                        String id=cur.getString(0),name=cur.getString(1),mime=cur.getString(2);
                        if(DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)){todo.add(id);continue;}
                        if(name==null)continue;
                        String lower=name.toLowerCase(Locale.ROOT);
                        if(!(mime!=null&&mime.startsWith("audio/"))&&!lower.matches(".*\\.(m4a|mp4|aac|mp3|wav|ogg|opus|webm|flac)$"))continue;
                        long size=cur.isNull(3)?-1:cur.getLong(3);if(size==0)continue;
                        String uri=DocumentsContract.buildDocumentUriUsingTree(tree,id).toString();
                        if(next.containsKey(uri))continue;
                        if(files.length()>=10000)throw new Exception("녹음이 10,000개를 넘습니다. 날짜 폴더를 선택해 주세요");
                        next.put(uri,size);files.put(new JSONObject().put("name",name).put("uri",uri).put("size",size)
                            .put("created",cur.isNull(4)?0:cur.getLong(4)).put("durMs",0));
                    }
                }
            }
            synchronized(allowed){allowed.clear();allowed.putAll(next);catalog.clear();for(int i=0;i<files.length();i++){JSONObject f=files.getJSONObject(i);catalog.put(f.getString("uri"),f);}}
            JSObject o=new JSObject();o.put("files",files);o.put("folder","선택한 폴더 · 하위 폴더 포함");call.resolve(o);
        }catch(Exception e){call.reject("폴더를 불러오지 못했습니다: "+e.getMessage());}finally{scanning=false;}},"Recording-Folder").start();
    }
    @PluginMethod public void shareBatch(PluginCall call) {
        try{
            JSArray input=call.getArray("uris");if(input==null||input.length()==0||input.length()>9)throw new Exception("한 번에 1~9개 파일을 선택해 주세요");
            ArrayList<Uri> streams=new ArrayList<>();Set<String> seen=new HashSet<>();
            synchronized(allowed){for(int i=0;i<input.length();i++){
                String value=input.getString(i);Long size=allowed.get(value);
                if(size==null||!value.startsWith("content://"))throw new Exception("폴더를 다시 선택해 주세요");
                if(size>MAX_BYTES)throw new Exception("100MB가 넘는 파일이 있습니다. 더 작은 파일을 선택해 주세요");
                if(seen.add(value))streams.add(Uri.parse(value));
            }}
            String date=call.getString("date","");
            if(!date.matches("\\d{4}-\\d{2}-\\d{2}"))throw new Exception("분석할 날짜를 선택해 주세요");
            Uri daily=DailyLog.publish(getContext(),date);
            if("claude".equals(call.getString("target","gemini")))streams.clear();
            streams.add(daily);
            Intent send=new Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*");
            String prompt=call.getString("prompt","");if(prompt.length()>30000)throw new Exception("분석 지침이 너무 깁니다");send.putExtra(Intent.EXTRA_TEXT,prompt);
            if(streams.size()==1)send.putExtra(Intent.EXTRA_STREAM,streams.get(0));else send.putParcelableArrayListExtra(Intent.EXTRA_STREAM,streams);
            ClipData clip=ClipData.newUri(getContext().getContentResolver(),"녹음 파일",streams.get(0));
            for(int i=1;i<streams.size();i++)clip.addItem(new ClipData.Item(streams.get(i)));
            send.setClipData(clip);send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            String ai=call.getString("target","gemini");
            String pkg="chatgpt".equals(ai)?"com.openai.chatgpt":"claude".equals(ai)?"com.anthropic.claude":GEMINI;
            Intent target=new Intent(send).setPackage(pkg);
            boolean direct=target.resolveActivity(getContext().getPackageManager())!=null;
            getActivity().runOnUiThread(()->{try{
                if(direct)getActivity().startActivity(target);
                else getActivity().startActivity(Intent.createChooser(send,"AI 분석 자료를 받을 앱 선택"));
                JSObject o=new JSObject();o.put("direct",direct);o.put("count",streams.size());o.put("uploaded",false);call.resolve(o);
            }catch(Exception e){call.reject("공유 화면을 열지 못했습니다. 선택한 AI 앱의 파일 첨부 메뉴에서 첨부해 주세요: "+e.getMessage());}});
        }catch(Exception e){call.reject(e.getMessage());}
    }
    @PluginMethod public void prepareDaily(PluginCall call) {
        new Thread(()->{try{
            String date=call.getString("date","");if(!date.matches("\\d{4}-\\d{2}-\\d{2}"))throw new Exception("날짜 형식 오류");
            DailyLog.repairPending(getContext());
            JSONArray recordings=RecordingStore.list(getContext());
            synchronized(allowed){for(JSONObject f:catalog.values())recordings.put(f);}
            for(int i=0;i<recordings.length();i++){JSONObject f=recordings.getJSONObject(i);
                String name=f.optString("name");long created=f.optLong("created");
                if(name.matches("LBB_\\d{8}_\\d{6}.*"))try{created=new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).parse(name.substring(4,19)).getTime();}catch(Exception ignored){}
                if(date.equals(DailyLog.day(created))&&DailyLog.datesFor(getContext(),f.optString("name")).length()==0)
                    DailyLog.record(getContext(),new java.io.File(getContext().getCacheDir(),"legacy_"+f.optString("name")),f.optString("name"),created,f.optLong("durMs"));
            }
            Uri uri=DailyLog.publish(getContext(),date);JSObject o=new JSObject();o.put("uri",uri.toString());o.put("name","LifeRecorder_"+date+"_timelog.txt");call.resolve(o);
        }catch(Exception e){call.reject(e.getMessage());}},"Daily-Log").start();
    }
    @PluginMethod public void copyPrompt(PluginCall call) {
        android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("AI 분석 지침",call.getString("prompt","")));call.resolve();
    }
    @PluginMethod public void openGemini(PluginCall call) {
        getActivity().runOnUiThread(()->{try{
            getActivity().startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://gemini.google.com/app")));call.resolve();
        }catch(Exception e){call.reject("Gemini를 열 수 없습니다");}});
    }
}

