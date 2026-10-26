package com.lifeblackbox.recorder;
import org.json.*;
import java.util.*;

/** Weekly local-time intervals. Overnight weekday belongs to the start day. */
final class RestSchedule {
 static JSONArray normalize(JSONObject cfg)throws JSONException{
  JSONArray src=cfg.optJSONArray("restSchedules"),out=new JSONArray();
  if(src==null){if(cfg.optBoolean("sleepOn",false))src=new JSONArray().put(new JSONObject().put("name","취침").put("enabled",true).put("start",cfg.optString("sleepStart","23:00")).put("end",cfg.optString("sleepEnd","07:00")).put("days",new JSONArray("[1,2,3,4,5,6,7]")));else return out;}
  if(src.length()>32)throw new JSONException("휴식 구간은 최대 32개입니다");
  for(int i=0;i<src.length();i++){
   JSONObject v=src.getJSONObject(i);String a=v.optString("start"),b=v.optString("end");
   if(!a.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]")||!b.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]")||a.equals(b))throw new JSONException("휴식 시작·종료 시간을 확인해 주세요");
   JSONArray days=v.optJSONArray("days"),clean=new JSONArray();Set<Integer> seen=new HashSet<>();
   if(days!=null)for(int n=0;n<days.length();n++){int d=days.getInt(n);if(d<1||d>7)throw new JSONException("요일 오류");if(seen.add(d))clean.put(d);}
   if(clean.length()==0)throw new JSONException("반복 요일을 선택해 주세요");
   String name=v.optString("name","휴식").trim();if(name.length()>30)name=name.substring(0,30);
   out.put(new JSONObject().put("name",name).put("enabled",v.optBoolean("enabled",true)).put("start",a).put("end",b).put("days",clean));
  }return out;
 }
 static long at(Calendar day,String time){Calendar c=(Calendar)day.clone();String[] p=time.split(":");c.set(Calendar.HOUR_OF_DAY,Integer.parseInt(p[0]));c.set(Calendar.MINUTE,Integer.parseInt(p[1]));c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);return c.getTimeInMillis();}
 /** [start,end] of merged current interval; [0,0] outside. */
 static long[] current(JSONArray schedules,long now)throws JSONException{
  List<long[]> intervals=new ArrayList<>();Calendar base=Calendar.getInstance();base.setTimeInMillis(now);
  for(int offset=-8;offset<=15;offset++){
   Calendar d=(Calendar)base.clone();d.add(Calendar.DATE,offset);int weekday=(d.get(Calendar.DAY_OF_WEEK)+5)%7+1;
   for(int i=0;i<schedules.length();i++){JSONObject v=schedules.getJSONObject(i);if(!v.optBoolean("enabled"))continue;JSONArray days=v.getJSONArray("days");boolean match=false;for(int j=0;j<days.length();j++)if(days.getInt(j)==weekday)match=true;if(!match)continue;
    long a=at(d,v.getString("start")),b=at(d,v.getString("end"));if(b<=a){Calendar next=(Calendar)d.clone();next.add(Calendar.DATE,1);b=at(next,v.getString("end"));}intervals.add(new long[]{a,b});
   }
  }
  intervals.sort(Comparator.comparingLong(v->v[0]));long a=0,b=0;
  for(long[] v:intervals){if(b!=0&&v[0]>b){if(a<=now&&now<b)return new long[]{a,b};a=v[0];b=v[1];}else{if(b==0)a=v[0];b=Math.max(b,v[1]);}}
  return a<=now&&now<b?new long[]{a,b}:new long[]{0,0};
 }
}
