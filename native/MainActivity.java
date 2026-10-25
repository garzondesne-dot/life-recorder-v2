package com.lifeblackbox.recorder;

import android.os.Build;
import android.os.Bundle;
import android.webkit.WebView;

import android.view.View;

import androidx.activity.OnBackPressedCallback;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    private com.getcapacitor.PluginCall driveCall;
    private final androidx.activity.result.ActivityResultLauncher<androidx.activity.result.IntentSenderRequest> driveLauncher =
        registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult(), result -> {
            if(driveCall==null)return;
            try {
                if(result.getResultCode()!=RESULT_OK)throw new Exception("구글 연결이 취소되었습니다");
                acceptDrive(com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(result.getData()));
            }catch(Exception e){driveCall.reject(e.getMessage());driveCall=null;}
        });
    public void connectDrive(com.getcapacitor.PluginCall call) {
        if(driveCall!=null){call.reject("구글 연결이 진행 중입니다");return;}driveCall=call;
        com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(this).authorize(DriveSync.request(null))
            .addOnSuccessListener(result->{
                if(result.hasResolution()) {
                    try{driveLauncher.launch(new androidx.activity.result.IntentSenderRequest.Builder(result.getPendingIntent()).build());}
                    catch(Exception e){driveCall.reject(e.getMessage());driveCall=null;}
                } else acceptDrive(result);
            }).addOnFailureListener(e->{if(driveCall!=null){driveCall.reject("Google Cloud의 Android OAuth 등록을 확인하세요: "+e.getMessage());driveCall=null;}});
    }
    private void acceptDrive(com.google.android.gms.auth.api.identity.AuthorizationResult result) {
        final com.getcapacitor.PluginCall call=driveCall;driveCall=null;
        new Thread(()->{try{
            String email=DriveSync.email(result.getAccessToken());
            DriveSync.prefs(this).edit().putString("email",email).putBoolean("enabled",true).putString("status","업로드 대기").apply();
            DriveSync.schedule(this);com.getcapacitor.JSObject o=new com.getcapacitor.JSObject();o.put("email",email);call.resolve(o);
        }catch(Exception e){call.reject(e.getMessage());}},"Drive-Connect").start();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(BlackboxServicePlugin.class);   // super.onCreate 이전에 등록해야 함
        super.onCreate(savedInstanceState);

        // 안드로이드 15+ 강제 전체화면(edge-to-edge): 상태바·내비게이션바·폴드 힌지 영역만큼 화면을 안쪽으로 줄여 하단 잘림 방지
        View content = findViewById(android.R.id.content);
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            Insets i = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(i.left, i.top, i.right, i.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        WebView wv = getBridge().getWebView();
        // 화면이 꺼지거나 앱이 가려져도 WebView 렌더러(=JS·오디오 처리)를 '중요' 우선순위로 유지
        if (Build.VERSION.SDK_INT >= 26) {
            wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        }
        wv.getSettings().setMediaPlaybackRequiresUserGesture(false);

        // 뒤로 가기: 설정창이 열려 있으면 닫고(WebView history), 아니면 앱 종료 대신 홈으로 보내기
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                WebView w = getBridge().getWebView();
                if (w != null && w.canGoBack()) w.goBack();
                else moveTaskToBack(true);
            }
        });
    }

}
