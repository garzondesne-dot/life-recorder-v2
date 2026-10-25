package com.lifeblackbox.recorder;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

/**
 * 마이크 타입 포그라운드 서비스.
 * - 녹음 중: PARTIAL_WAKE_LOCK 유지 → 화면이 꺼져도 CPU·WebView(JS, Web Worker, 오디오)가 계속 동작
 * - 취침 중(ACTION_SLEEP): 웨이크락 반납 → 기기가 Doze로 깊은 절전, 기상 시각에 알람으로 웨이크락 재획득
 * - Android 14+ 백그라운드 마이크 차단을 피하려면 앱이 화면에 보일 때 최초 시작해야 함
 */
public class BlackboxService extends Service {
    public static final String CHANNEL_ID = "blackbox_rec";
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_AT = "at";
    public static final String ACTION_SLEEP = "com.lifeblackbox.recorder.SLEEP";
    public static final String ACTION_WAKE = "com.lifeblackbox.recorder.WAKE";
    public static final String ACTION_STOP = "com.lifeblackbox.recorder.STOP";
    public static final String DEFAULT_TEXT = "일상에 귀기울이고 있습니다";
    private NativeRecorder recorder;
    public static volatile String lastError = "";
    public static volatile Runnable stopListener;   // 플러그인이 등록: 알림의 '녹음 중지' → JS 로 전달
    private static final String ACTION_ALARM = "com.lifeblackbox.recorder.WAKE_ALARM";
    public static final int NOTI_ID = 1001;
    public static volatile boolean running = false;

    private PowerManager.WakeLock wakeLock;
    private final BroadcastReceiver alarmReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            acquireWakeLock();   // CPU를 깨워 WebView의 1초 타이머가 기상 처리(마이크 재획득)를 하게 함
            notifyText(DEFAULT_TEXT);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "블랙박스 녹음", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("녹음이 진행 중임을 표시합니다");
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        ContextCompat.registerReceiver(this, alarmReceiver, new IntentFilter(ACTION_ALARM),
                ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {            // 알림의 '녹음 중지' 버튼
            if (recorder != null) recorder.stop(); else stopSelf();
            return START_NOT_STICKY;
        }
        String text = DEFAULT_TEXT;
        if (intent != null && intent.getStringExtra(EXTRA_TEXT) != null) text = intent.getStringExtra(EXTRA_TEXT);

        int type = 0;
        if (Build.VERSION.SDK_INT >= 30) type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        ServiceCompat.startForeground(this, NOTI_ID, buildNotification(text), type);

        if (ACTION_SLEEP.equals(action)) {
            releaseWakeLock();
            long at = intent.getLongExtra(EXTRA_AT, 0);
            if (at > System.currentTimeMillis()) scheduleWake(at);
        } else {                      // 최초 시작 / ACTION_WAKE / 알림 갱신
            cancelWake();
            acquireWakeLock();
        }
        if (intent != null && intent.hasExtra("config")) {
            try {
                org.json.JSONObject config = new org.json.JSONObject(intent.getStringExtra("config"));
                if (recorder == null) { lastError=""; recorder = new NativeRecorder(this, config); recorder.start(); }
                else recorder.configure(config);
            } catch (Exception e) { recordError(e.getMessage()); stopSelf(); }
        }
        running = true;
        return START_NOT_STICKY;      // OS에 의한 프로세스 종료/강제 중지는 자동 재시작하지 않음
    }

    void sleepNative(long at) { scheduleWake(at); notifyText("취침 중 · 예약 시간에 재개"); releaseWakeLock(); }
    void wakeNative() { cancelWake(); acquireWakeLock(); notifyText(DEFAULT_TEXT); }
    void recordError(String error) { lastError=error; notifyText("녹음 오류: " + error); }
    void recordingFinished() { new Handler(Looper.getMainLooper()).post(this::stopSelf); }

    private synchronized void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LifeBlackbox::recording");
            wakeLock.setReferenceCounted(false);
        }
        if (!wakeLock.isHeld()) wakeLock.acquire();
    }

    private synchronized void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    private PendingIntent alarmIntent() {
        Intent i = new Intent(ACTION_ALARM).setPackage(getPackageName());
        return PendingIntent.getBroadcast(this, 1, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void scheduleWake(long at) {
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = alarmIntent();
        boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);   // 정확 알람 권한이 없으면 수 분 오차 허용
    }

    private void cancelWake() {
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (am != null) am.cancel(alarmIntent());
    }

    private void notifyText(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTI_ID, buildNotification(text));
    }

    private PendingIntent stopIntent() {
        Intent i = new Intent(this, BlackboxService.class).setAction(ACTION_STOP);
        return PendingIntent.getService(this, 2, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Life Blackbox")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pi)
                .addAction(0, "녹음 중지", stopIntent())
                .setOngoing(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
    }

    @Override
    public void onDestroy() {
        running = false;
        if (recorder != null) recorder.stop();
        cancelWake();
        releaseWakeLock();
        try { unregisterReceiver(alarmReceiver); } catch (Exception ignored) { }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
