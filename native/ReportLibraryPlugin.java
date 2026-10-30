package com.lifeblackbox.recorder;
import com.getcapacitor.*;
import com.getcapacitor.annotation.CapacitorPlugin;
import org.json.JSONObject;

@CapacitorPlugin(name="ReportLibrary")
public class ReportLibraryPlugin extends Plugin {
 interface Work {JSONObject run()throws Exception;}
 private final java.util.concurrent.ExecutorService io=java.util.concurrent.Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"Report-Library");t.setPriority(Thread.MIN_PRIORITY);return t;});
 void run(PluginCall call,Work work){io.execute(()->{try{call.resolve(new JSObject(work.run().toString()));}catch(Exception e){call.reject(e.getMessage()==null?"보고서 처리 실패":e.getMessage());}});}
 @PluginMethod public void context(PluginCall call){run(call,()->new JSONObject().put("scope",ReportStore.scope(getContext())).put("cloudBackedUp",false));}
 @PluginMethod public void load(PluginCall call){run(call,()->ReportStore.load(getContext(),call.getString("scope"),call.getString("date"),call.getString("version","")));}
 @PluginMethod public void save(PluginCall call){run(call,()->ReportStore.save(getContext(),call.getString("scope"),call.getString("date"),call.getObject("report")));}
 @PluginMethod public void review(PluginCall call){run(call,()->{ReportStore.review(getContext(),call.getString("scope"),call.getString("date"),call.getString("version"),call.getObject("review"));return new JSONObject();});}
 @PluginMethod public void person(PluginCall call){run(call,()->ReportStore.person(getContext(),call.getString("scope"),call.getObject("person")));}
 @PluginMethod public void delete(PluginCall call){run(call,()->{ReportStore.delete(getContext(),call.getString("scope"),call.getString("date"));return new JSONObject();});}
 @PluginMethod public void deletePerson(PluginCall call){run(call,()->{ReportStore.deletePerson(getContext(),call.getString("scope"),call.getString("id"));return new JSONObject();});}
 @Override protected void handleOnDestroy(){io.shutdown();super.handleOnDestroy();}
}
