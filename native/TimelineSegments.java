package com.lifeblackbox.recorder;
/** PCM playback offsets and approximate capture wall-clock spans; no Android dependencies. */
public final class TimelineSegments {
    long from=-1,to,wallStart,wallEnd;
    public String accept(long offset,long duration,long wall) {
        String old="";
        if(from>=0 && (Math.abs(wall-wallEnd)>100 || wallEnd-wallStart>=10000))old=flush();
        if(from<0){from=offset;wallStart=wall;}
        to=offset+duration;wallEnd=wall+duration;return old;
    }
    public String flush(){if(from<0)return "";String s="{\"from\":"+from+",\"to\":"+to+",\"start\":"+wallStart+",\"end\":"+wallEnd+"}\n";from=-1;return s;}
}
