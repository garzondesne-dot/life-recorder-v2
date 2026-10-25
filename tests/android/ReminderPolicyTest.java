package com.lifeblackbox.recorder;
import org.junit.Test;import static org.junit.Assert.*;
public class ReminderPolicyTest {
 @Test public void midnightSleepRefersToTheDayThatJustEnded(){
  java.util.TimeZone old=java.util.TimeZone.getDefault();try{
   java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
   assertEquals("2026-10-25",ReminderPolicy.recordingDay(java.time.Instant.parse("2026-10-26T00:00:00.050Z").toEpochMilli(),"00:00"));
   assertEquals("2026-10-25",ReminderPolicy.recordingDay(java.time.Instant.parse("2026-10-25T23:00:00.050Z").toEpochMilli(),"23:00"));
  }finally{java.util.TimeZone.setDefault(old);}
 }
 @Test public void optInPermissionSavedAudioAndNewDayAreRequired(){
  assertTrue(ReminderPolicy.allow(true,true,true,true,1,"today","yesterday"));
  assertFalse(ReminderPolicy.allow(false,true,true,true,1,"today","yesterday"));
  assertFalse(ReminderPolicy.allow(true,false,true,true,1,"today","yesterday"));
  assertFalse(ReminderPolicy.allow(true,true,false,true,1,"today","yesterday"));
  assertFalse(ReminderPolicy.allow(true,true,true,false,1,"today","yesterday"));
  assertFalse(ReminderPolicy.allow(true,true,true,true,0,"today","yesterday"));
  assertFalse(ReminderPolicy.allow(true,true,true,true,1,"today","today"));
 }
}
