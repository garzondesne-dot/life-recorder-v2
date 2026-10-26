package com.lifeblackbox.recorder;
import org.json.*;import org.junit.*;import org.junit.runner.RunWith;import org.robolectric.RobolectricTestRunner;import org.robolectric.annotation.Config;import java.util.*;import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=29)
public class RestScheduleTest {
 TimeZone old;
 @Before public void setup(){old=TimeZone.getDefault();TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));}
 @After public void restore(){TimeZone.setDefault(old);}
 long time(String s){return java.time.LocalDateTime.parse(s).atZone(java.time.ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli();}
 JSONObject row(String a,String b,String days)throws Exception{return new JSONObject().put("name","휴식").put("start",a).put("end",b).put("days",new JSONArray(days)).put("enabled",true);}
 @Test public void overnightUsesStartingWeekdayAndResumesAtExactEnd()throws Exception{
  JSONArray a=new JSONArray().put(row("23:00","07:00","[1]"));
  assertEquals(time("2026-10-27T07:00"),RestSchedule.current(a,time("2026-10-27T06:59"))[1]);
  assertEquals(0,RestSchedule.current(a,time("2026-10-27T07:00"))[1]);
  assertEquals(0,RestSchedule.current(a,time("2026-10-26T06:00"))[1]);
 }
 @Test public void overlappingAndAdjacentIntervalsAreOneRest()throws Exception{
  JSONArray a=new JSONArray().put(row("12:00","13:00","[1]")).put(row("12:30","14:00","[1]")).put(row("14:00","15:00","[1]"));
  assertEquals(time("2026-10-26T15:00"),RestSchedule.current(a,time("2026-10-26T12:05"))[1]);
  a.getJSONObject(2).put("enabled",false);assertEquals(time("2026-10-26T14:00"),RestSchedule.current(a,time("2026-10-26T12:05"))[1]);
 }
 @Test public void legacyMigrationAndDisabledSchedule()throws Exception{
  JSONObject c=new JSONObject().put("sleepOn",true).put("sleepStart","22:00").put("sleepEnd","07:00");JSONArray a=RestSchedule.normalize(c);assertEquals(1,a.length());assertEquals(7,a.getJSONObject(0).getJSONArray("days").length());
  c.put("restSchedules",new JSONArray());assertEquals(0,RestSchedule.normalize(c).length());
 }
 @Test public void invalidTimeAndNoDaysAreRejected()throws Exception{
  for(JSONObject v:new JSONObject[]{row("12:00","12:00","[1]"),row("25:00","13:00","[1]"),row("12:00","13:00","[]")})try{RestSchedule.normalize(new JSONObject().put("restSchedules",new JSONArray().put(v)));fail();}catch(JSONException expected){}
 }
}
