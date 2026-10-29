package com.lifeblackbox.recorder;
import android.content.*;
/** Explicit receiver survives process loss; user interaction is required to recreate a microphone FGS. */
public class RestAlarmReceiver extends BroadcastReceiver {
 @Override public void onReceive(Context c,Intent intent){if(intent!=null&&"com.lifeblackbox.recorder.REST_END".equals(intent.getAction()))RecordingSession.fired(c,intent.getLongExtra("at",0));}
}
