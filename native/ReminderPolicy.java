package com.lifeblackbox.recorder;
final class ReminderPolicy {
 static boolean allow(boolean enabled,boolean sleepEnabled,boolean permission,boolean saved,int count,String day,String lastDay){
  return enabled&&sleepEnabled&&permission&&saved&&count>0&&!day.equals(lastDay);
 }
}
