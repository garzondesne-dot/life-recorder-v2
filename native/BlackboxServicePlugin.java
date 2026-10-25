package com.lifeblackbox.recorder;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import java.io.OutputStream;
import android.view.Window;
import android.view.WindowManager;

import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

/**
 * JS 사용 예:
 *   Capacitor.Plugins.BlackboxService.start({ text: '녹음 중' })
 *   Capacitor.Plugins.BlackboxService.update({ text: '취침 모드' })
 *   Capacitor.Plugins.BlackboxService.stop()
 *   Capacitor.Plugins.BlackboxService.requestIgnoreBattery()   → { ignoring: boolean }
 *   Capacitor.Plugins.BlackboxService.keepScreenOn({ on: true })
 *   Capacitor.Plugins.BlackboxService.sleepUntil({ at: epochMs, text })  ← 취침: 웨이크락 반납 + 기상 알람
 *   Capacitor.Plugins.BlackboxService.wake({ text })                     ← 기상: 웨이크락 재획득
 *   Capacitor.Plugins.BlackboxService.immersive({ on: true })            ← 포켓 모드: 시스템 바 숨김 + 밝기 최저
 */
@CapacitorPlugin(
    name = "BlackboxService",
    permissions = { @Permission(alias = "microphone", strings = { "android.permission.RECORD_AUDIO" }), @Permission(alias = "notifications", strings = { "android.permission.POST_NOTIFICATIONS" }) }
)
public class BlackboxServicePlugin extends Plugin {

    @Override
    public void load() {
        BlackboxService.stopListener = () -> notifyListeners("stopRequested", new JSObject());
    }

    @Override
    protected void handleOnDestroy() {
        BlackboxService.stopListener = null;
    }

    @PluginMethod
    public void start(PluginCall call) {
        if (getPermissionState("microphone") != PermissionState.GRANTED) {
            requestPermissionForAlias("microphone", call, "afterMicrophonePermission"); return;
        }
        startWithNotifications(call);
    }
    @PermissionCallback
    private void afterMicrophonePermission(PluginCall call) {
        if (getPermissionState("microphone") != PermissionState.GRANTED) { call.reject("마이크 권한이 필요합니다"); return; }
        startWithNotifications(call);
    }
    private void startWithNotifications(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 33 && getPermissionState("notifications") != PermissionState.GRANTED) {
            requestPermissionForAlias("notifications", call, "afterNotificationPermission");
            return;
        }
        launch(call);
    }

    @PermissionCallback
    private void afterNotificationPermission(PluginCall call) {
        launch(call);   // 알림 권한을 거부해도 서비스는 동작 (알림만 숨겨짐)
    }

    private void launch(PluginCall call) {
        try {
            Intent i = new Intent(getContext(), BlackboxService.class);
            i.putExtra(BlackboxService.EXTRA_TEXT, call.getString("text", BlackboxService.DEFAULT_TEXT));
            i.putExtra("config", call.getObject("config", new JSObject()).toString());
            ContextCompat.startForegroundService(getContext(), i);
            call.resolve();
        } catch (Exception e) {
            call.reject("포그라운드 서비스 시작 실패: " + e.getMessage());
        }
    }

    @PluginMethod
    public void update(PluginCall call) {
        if (!BlackboxService.running) { call.resolve(); return; }
        launch(call);
    }

    private void sendAction(PluginCall call, Intent i) {
        try {
            getContext().startService(i);   // 이미 포그라운드 서비스 실행 중이므로 백그라운드에서도 허용됨
        } catch (Exception e) {
            try { ContextCompat.startForegroundService(getContext(), i); }
            catch (Exception e2) { call.reject("서비스 명령 실패: " + e2.getMessage()); return; }
        }
        call.resolve();
    }

    @PluginMethod
    public void sleepUntil(PluginCall call) {
        if (!BlackboxService.running) { call.resolve(); return; }
        Intent i = new Intent(getContext(), BlackboxService.class).setAction(BlackboxService.ACTION_SLEEP);
        Double at = call.getDouble("at", 0d);
        i.putExtra(BlackboxService.EXTRA_AT, at == null ? 0L : at.longValue());
        i.putExtra(BlackboxService.EXTRA_TEXT, call.getString("text", "💤 취침 모드"));
        sendAction(call, i);
    }

    @PluginMethod
    public void wake(PluginCall call) {
        if (!BlackboxService.running) { call.resolve(); return; }
        Intent i = new Intent(getContext(), BlackboxService.class).setAction(BlackboxService.ACTION_WAKE);
        i.putExtra(BlackboxService.EXTRA_TEXT, call.getString("text", BlackboxService.DEFAULT_TEXT));
        sendAction(call, i);
    }

    @PluginMethod
    public void immersive(PluginCall call) {
        final boolean on = Boolean.TRUE.equals(call.getBoolean("on", true));
        getActivity().runOnUiThread(() -> {
            Window w = getActivity().getWindow();
            WindowInsetsControllerCompat c = WindowCompat.getInsetsController(w, w.getDecorView());
            WindowManager.LayoutParams lp = w.getAttributes();
            if (on) {
                c.hide(WindowInsetsCompat.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                lp.screenBrightness = 0.01f;   // 검정 화면 + 백라이트/패널 구동 전력 최소
            } else {
                c.show(WindowInsetsCompat.Type.systemBars());
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            }
            w.setAttributes(lp);
        });
        call.resolve();
    }

    /** m4a 를 MediaStore 에 등록: 삼성 녹음앱(Recordings/Voice Recorder)이 바로 인식. { name, mime, data(base64) } → { path, uri } */
    @PluginMethod
    public void saveAudio(PluginCall call) {
        final String name = call.getString("name");
        final String data = call.getString("data");
        final String mime = call.getString("mime", "audio/mp4");
        if (name == null || data == null) { call.reject("name/data 누락"); return; }
        if (Build.VERSION.SDK_INT < 29) { call.reject("Android 10 미만은 MediaStore 저장 미지원"); return; }
        new Thread(() -> {
            ContentResolver cr = getContext().getContentResolver();
            String[] dirs = { "Recordings/Voice Recorder", "Music/Voice Recorder" };
            android.net.Uri uri = null; String used = null;
            for (String dir : dirs) {
                try {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Audio.Media.DISPLAY_NAME, name);
                    v.put(MediaStore.Audio.Media.MIME_TYPE, mime);
                    v.put(MediaStore.Audio.Media.RELATIVE_PATH, dir);
                    v.put(MediaStore.Audio.Media.IS_PENDING, 1);
                    uri = cr.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v);
                    if (uri != null) { used = dir; break; }
                } catch (Exception ignored) { uri = null; }
            }
            if (uri == null) { call.reject("MediaStore 등록 실패"); return; }
            try {
                byte[] raw = Base64.decode(data, Base64.DEFAULT);
                long durUs = -1; int samples = 0; boolean remuxed = false;
                File tmp = File.createTempFile("lbb", ".mp4", getContext().getCacheDir());
                try {
                    try (FileOutputStream fo = new FileOutputStream(tmp)) { fo.write(raw); }
                    // 조각난 MP4(fMP4)를 표준 m4a 로 재포장 → 삼성 플레이어가 전체 길이를 인식
                    MediaExtractor ex = new MediaExtractor();
                    ex.setDataSource(tmp.getAbsolutePath());
                    int track = -1; MediaFormat fmt = null;
                    for (int i = 0; i < ex.getTrackCount(); i++) {
                        MediaFormat f = ex.getTrackFormat(i);
                        String m = f.getString(MediaFormat.KEY_MIME);
                        if (m != null && m.startsWith("audio/")) { track = i; fmt = f; break; }
                    }
                    if (track < 0) throw new Exception("오디오 트랙 없음");
                    ex.selectTrack(track);
                    ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "rw");
                    MediaMuxer mx = new MediaMuxer(pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                    int ti = mx.addTrack(fmt); mx.start();
                    ByteBuffer buf = ByteBuffer.allocate(1 << 18);
                    MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
                    while (true) {
                        buf.clear();
                        int n = ex.readSampleData(buf, 0);
                        if (n < 0) break;
                        bi.offset = 0; bi.size = n; bi.presentationTimeUs = ex.getSampleTime();
                        bi.flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                        mx.writeSampleData(ti, buf, bi);
                        durUs = bi.presentationTimeUs; samples++;
                        ex.advance();
                    }
                    mx.stop(); mx.release(); ex.release(); pfd.close();
                    remuxed = samples > 0;
                } catch (Exception re) {
                    remuxed = false;
                } finally { tmp.delete(); }
                if (!remuxed) {   // 재포장 실패 → 원본 그대로 저장
                    try (OutputStream os = cr.openOutputStream(uri, "wt")) {
                        if (os == null) throw new java.io.IOException("출력 스트림 없음");
                        os.write(raw); os.flush();
                    }
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.Audio.Media.IS_PENDING, 0);
                cr.update(uri, done, null, null);
                JSObject r = new JSObject();
                r.put("path", used); r.put("uri", uri.toString());
                r.put("remuxed", remuxed); r.put("samples", samples); r.put("durMs", durUs < 0 ? -1 : durUs / 1000);
                r.put("bytes", raw.length);
                call.resolve(r);
            } catch (Exception e) {
                try { cr.delete(uri, null, null); } catch (Exception ignored) { }
                call.reject("저장 실패: " + e.getMessage());
            }
        }).start();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        if (BlackboxService.running) getContext().startService(new Intent(getContext(), BlackboxService.class).setAction(BlackboxService.ACTION_STOP));
        call.resolve();
    }

    @PluginMethod
    public void requestIgnoreBattery(PluginCall call) {
        Context ctx = getContext();
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        boolean ignoring = pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
        if (!ignoring) {
            try {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + ctx.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Exception e) {
                Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            }
        }
        JSObject r = new JSObject();
        r.put("ignoring", ignoring);
        call.resolve(r);
    }

    @PluginMethod
    public void keepScreenOn(PluginCall call) {
        final boolean on = Boolean.TRUE.equals(call.getBoolean("on", true));
        getActivity().runOnUiThread(() -> {
            if (on) getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
        call.resolve();
    }
    @PluginMethod public void status(PluginCall call) {
        try {
            NativeRecorder r=NativeRecorder.instance;
            org.json.JSONObject o=r==null?new org.json.JSONObject().put("running",false).put("error",BlackboxService.lastError):r.status();
            call.resolve(new JSObject(o.toString()));
        } catch(Exception e){call.reject(e.getMessage());}
    }
    @PluginMethod public void configure(PluginCall call) {
        NativeRecorder r=NativeRecorder.instance;if(r!=null)r.configure(call.getObject("config",new JSObject()));call.resolve();
    }
    @PluginMethod public void listRecordings(PluginCall call) {
        try {JSObject o=new JSObject();o.put("files",RecordingStore.list(getContext()));call.resolve(o);}
        catch(Exception e){call.reject(e.getMessage());}
    }
    @PluginMethod public void openRecording(PluginCall call) {
        try {
            String uri=call.getString("uri");boolean known=false;
            org.json.JSONArray files=RecordingStore.list(getContext());
            for(int n=0;n<files.length();n++)if(files.getJSONObject(n).optString("uri").equals(uri))known=true;
            if(!known)throw new Exception("파일을 찾을 수 없습니다");
            Intent i=new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri),"audio/mp4");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);getActivity().startActivity(i);call.resolve();
        }catch(Exception e){call.reject("재생 앱을 열지 못했습니다: "+e.getMessage());}
    }
    @PluginMethod public void shareRecording(PluginCall call) {
        try {
            String uri=call.getString("uri");boolean known=false;
            org.json.JSONArray files=RecordingStore.list(getContext());
            for(int n=0;n<files.length();n++)if(files.getJSONObject(n).optString("uri").equals(uri))known=true;
            if(!known)throw new Exception("파일을 찾을 수 없습니다");
            Intent i=new Intent(Intent.ACTION_SEND).setType("audio/mp4").putExtra(Intent.EXTRA_STREAM,Uri.parse(uri));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);getActivity().startActivity(Intent.createChooser(i,"녹음 파일 내보내기"));call.resolve();
        }catch(Exception e){call.reject(e.getMessage());}
    }

}
