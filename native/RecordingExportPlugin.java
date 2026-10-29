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
    private static final String GEMINI="com.google.android.apps.bard";
    private volatile boolean scanning;

    @PluginMethod public void chooseFolder(PluginCall call) {
        getActivity().runOnUiThread(()->((MainActivity)getActivity()).chooseRecordingFolder(call,this));
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
            JSONObject snapshot=ExportSnapshot.read(getContext(),call.getString("token",""));
            Map<String,Long> snapshotUris=new HashMap<>();JSONArray saved=snapshot.getJSONArray("files");
            for(int i=0;i<saved.length();i++){JSONObject f=saved.getJSONObject(i);snapshotUris.put(f.getString("uri"),f.optLong("size"));}
            String ai=call.getString("target","gemini");long limit="chatgpt".equals(ai)?512L*1024*1024:100L*1000*1000;
            for(int i=0;i<input.length();i++){
                String value=input.getString(i);Long size=snapshotUris.get(value);
                if(size==null||!value.startsWith("content://"))throw new Exception("고정한 내보내기 목록에 없는 파일입니다");
                if(!"claude".equals(ai)&&size>limit)throw new Exception("선택한 AI의 파일 크기 한도를 초과합니다");
                if(!"claude".equals(ai))try(android.os.ParcelFileDescriptor fd=getContext().getContentResolver().openFileDescriptor(Uri.parse(value),"r")){if(fd==null)throw new Exception("원본 파일을 읽을 수 없습니다");}
                if(seen.add(value))streams.add(Uri.parse(value));
            }
            String prompt=call.getString("prompt","");
            Uri daily=ExportSnapshot.logWithPrompt(getContext(),snapshot,prompt);
            if("claude".equals(ai))streams.clear();streams.add(daily);
            Intent send=new Intent(streams.size()==1?Intent.ACTION_SEND:Intent.ACTION_SEND_MULTIPLE).setType(streams.size()==1?"text/plain":"*/*");
            if(prompt.length()>30000)throw new Exception("분석 지침이 너무 깁니다");send.putExtra(Intent.EXTRA_TEXT,prompt);
            if(streams.size()==1)send.putExtra(Intent.EXTRA_STREAM,streams.get(0));else send.putParcelableArrayListExtra(Intent.EXTRA_STREAM,streams);
            ClipData clip=ClipData.newUri(getContext().getContentResolver(),"녹음 파일",streams.get(0));
            for(int i=1;i<streams.size();i++)clip.addItem(new ClipData.Item(streams.get(i)));
            send.setClipData(clip);send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            String pkg="chatgpt".equals(ai)?"com.openai.chatgpt":"claude".equals(ai)?"com.anthropic.claude":GEMINI;
            Intent target=new Intent(send).setPackage(pkg);
            boolean direct=!"share".equals(ai)&&target.resolveActivity(getContext().getPackageManager())!=null;
            getActivity().runOnUiThread(()->{try{
                if(direct)getActivity().startActivity(target);
                else getActivity().startActivity(Intent.createChooser(send,"AI 분석 자료를 받을 앱 선택"));
                JSObject o=new JSObject();o.put("direct",direct);o.put("count",streams.size());o.put("uploaded",false);call.resolve(o);
            }catch(Exception e){call.reject("공유 화면을 열지 못했습니다. 선택한 AI 앱의 파일 첨부 메뉴에서 첨부해 주세요: "+e.getMessage());}});
        }catch(Exception e){call.reject(e.getMessage());}
    }
    private final java.util.concurrent.atomic.AtomicBoolean preparing=new java.util.concurrent.atomic.AtomicBoolean();
    @PluginMethod public void prepareSnapshot(PluginCall call){
        if(!preparing.compareAndSet(false,true)){call.reject("내보내기를 준비 중입니다");return;}
        new Thread(()->{try{
            String date=call.getString("date",DailyLog.day(System.currentTimeMillis()));
            if(!date.matches("\\d{4}-\\d{2}-\\d{2}"))throw new Exception("날짜 형식 오류");
            NativeRecorder recorder=NativeRecorder.instance;JSONObject result;
            if(recorder!=null)result=recorder.prepareExport(date).get(120,java.util.concurrent.TimeUnit.SECONDS);
            else {
                DailyLog.repairPending(getContext());WindowMerger.reconcile(getContext());JSONArray all=RecordingStore.list(getContext());
                synchronized(allowed){for(JSONObject row:catalog.values())all.put(row);}
                result=ExportSnapshot.create(getContext(),date,System.currentTimeMillis(),all);
            }
            call.resolve(new JSObject(result.toString()));
        }catch(Exception e){call.reject("내보내기 준비 실패: "+(e.getCause()!=null?e.getCause().getMessage():e.getMessage()));}finally{preparing.set(false);}},"Prepare-Export").start();
    }
    @PluginMethod public void restoreSnapshot(PluginCall call){try{call.resolve(new JSObject(ExportSnapshot.read(getContext(),call.getString("token","")).toString()));}catch(Exception e){call.reject("준비 기록이 만료되었습니다. 다시 준비해 주세요");}}
    @PluginMethod public void openDailyLog(PluginCall call){new Thread(()->{try{
        Uri uri=DailyLog.publish(getContext(),call.getString("date",DailyLog.day(System.currentTimeMillis())));
        Intent i=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"text/plain").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        getActivity().runOnUiThread(()->{try{getActivity().startActivity(i);call.resolve();}catch(Exception e){call.reject("TXT를 열 앱이 없습니다");}});
    }catch(Exception e){call.reject(e.getMessage());}},"Open-Log").start();}
    @PluginMethod public void setAnalysisProfile(PluginCall call){try{AnalysisInstructions.save(getContext(),call.getObject("profile",new JSObject()));call.resolve();}catch(Exception e){call.reject("분석 선호 저장 실패");}}
    @PluginMethod public void copyPrompt(PluginCall call) {
        android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("AI 분석 지침",call.getString("prompt","")));call.resolve();
    }
}
