package com.lifeblackbox.recorder;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.json.JSONObject;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=29)
public class NativeConfigurationTest {
    JSONObject validate(int minutes)throws Exception{
        java.lang.reflect.Method m=NativeRecorder.class.getDeclaredMethod("validated",JSONObject.class);m.setAccessible(true);
        return (JSONObject)m.invoke(null,new JSONObject().put("splitMin",minutes));
    }
    @Test public void migratesOldIntervalsToFixedTwoHours()throws Exception{assertEquals(120,validate(90).getInt("splitMin"));assertEquals(120,validate(120).getInt("splitMin"));}
    @Test public void invalidIntervalStillFallsBack()throws Exception{assertEquals(120,validate(0).getInt("splitMin"));assertEquals(120,validate(1000000).getInt("splitMin"));}
}
