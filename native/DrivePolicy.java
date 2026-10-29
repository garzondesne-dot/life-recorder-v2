package com.lifeblackbox.recorder;
import org.json.*;
import java.util.*;
/** Pure decisions shared by backup and retention tests. */
final class DrivePolicy {
 static String key(String name){long window=RecordingWindow.key(name);return window>0?"window:"+window:"audio:"+name;}
 static boolean expired(String lastDay,int days,long now){return RetentionManager.supported(days)&&lastDay.matches("\\d{4}-\\d{2}-\\d{2}")&&lastDay.compareTo(RetentionManager.oldestKeptDay(now,days))<0;}
 static boolean owned(JSONObject remote,String instance,String key){JSONObject p=remote.optJSONObject("appProperties");return p!=null&&"LifeRecorder".equals(p.optString("app"))&&instance.equals(p.optString("instance"))&&key.equals(p.optString("key"));}
 static boolean needsUpload(JSONObject receipt,String signature){return !signature.equals(receipt.optString("done"));}
 static String lastDay(JSONObject row,JSONObject summary){long end=summary==null?0:summary.optLong("wallEnd");return DailyLog.day(end>0?end-1:row.optLong("created"));}
}
