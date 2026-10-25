package com.lifeblackbox.recorder;
import org.junit.Test;import static org.junit.Assert.*;
public class ReminderPolicyTest {
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
