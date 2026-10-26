package com.lifeblackbox.recorder;
import java.util.*;
import java.util.regex.*;
/** Local, midnight-aligned two-hour windows. Names persist the epoch key across restarts. */
final class RecordingWindow {
 static long start(long now){Calendar c=Calendar.getInstance();c.setTimeInMillis(now);c.set(Calendar.HOUR_OF_DAY,(c.get(Calendar.HOUR_OF_DAY)/2)*2);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);return c.getTimeInMillis();}
 static long end(long now){Calendar c=Calendar.getInstance();c.setTimeInMillis(start(now));c.add(Calendar.HOUR_OF_DAY,2);return c.getTimeInMillis();}
 static long key(String name){Matcher m=Pattern.compile("_W([0-9]{10,16})(?:_|\\.)").matcher(name);if(!m.find())return 0;try{return Long.parseLong(m.group(1));}catch(Exception e){return 0;}}
}
