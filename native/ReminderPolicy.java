package com.lifeblackbox.recorder;
final class ReminderPolicy {
 static String recordingDay(long cutoff,String sleepStart){
  java.util.Calendar c=java.util.Calendar.getInstance();c.setTimeInMillis(cutoff);
  if("00:00".equals(sleepStart))c.add(java.util.Calendar.DATE,-1);
  return new java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.US).format(c.getTime());
 }
 static boolean allow(boolean enabled,boolean sleepEnabled,boolean permission,boolean saved,int count,String day,String lastDay){
  return enabled&&sleepEnabled&&permission&&saved&&count>0&&!day.equals(lastDay);
 }
}
