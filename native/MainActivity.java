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

    private com.getcapacitor.PluginCall folderCall;
    private RecordingExportPlugin folderPlugin;
    private final androidx.activity.result.ActivityResultLauncher<android.net.Uri> folderLauncher =
        registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(), uri -> {
            com.getcapacitor.PluginCall call=folderCall;RecordingExportPlugin plugin=folderPlugin;
            folderCall=null;folderPlugin=null;
            if(call==null)return;
            if(uri==null){call.reject("폴더 선택을 취소했습니다");return;}
            // Grant lasts for this selection/session; no broad storage permission is requested.
            plugin.readFolder(uri,call);
        });
    public void chooseRecordingFolder(com.getcapacitor.PluginCall call,RecordingExportPlugin plugin) {
        if(folderCall!=null){call.reject("폴더 선택 창이 열려 있습니다");return;}
        folderCall=call;folderPlugin=plugin;
        try{folderLauncher.launch(null);}catch(Exception e){folderCall=null;folderPlugin=null;call.reject(e.getMessage());}
    }

    private com.getcapacitor.PluginCall driveCall;
    private DriveBackupPlugin drivePlugin;
    private final androidx.activity.result.ActivityResultLauncher<androidx.activity.result.IntentSenderRequest> driveLauncher =
        registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult(), result -> {
            com.getcapacitor.PluginCall call=driveCall;DriveBackupPlugin plugin=drivePlugin;driveCall=null;drivePlugin=null;
            if(call==null)return;
            if(result.getResultCode()!=android.app.Activity.RESULT_OK){call.reject("Google 연결을 취소했습니다");return;}
            try{plugin.accept(com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(result.getData()),call);}
            catch(Exception e){call.reject("Google 권한 확인 실패. OAuth 설정과 계정을 확인해 주세요.");}
        });
    public void authorizeDrive(com.getcapacitor.PluginCall call,DriveBackupPlugin plugin){
        if(driveCall!=null){call.reject("Google 연결 창이 열려 있습니다");return;}driveCall=call;drivePlugin=plugin;
        com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(this).authorize(
            com.google.android.gms.auth.api.identity.AuthorizationRequest.builder().setRequestedScopes(java.util.Collections.singletonList(new com.google.android.gms.common.api.Scope(DriveBackup.SCOPE))).build())
            .addOnSuccessListener(auth->{if(auth.hasResolution()){
                try{driveLauncher.launch(new androidx.activity.result.IntentSenderRequest.Builder(auth.getPendingIntent().getIntentSender()).build());}
                catch(Exception e){driveCall=null;drivePlugin=null;call.reject("Google 연결 창 실행 실패");}
            }else{driveCall=null;drivePlugin=null;plugin.accept(auth,call);}})
            .addOnFailureListener(e->{driveCall=null;drivePlugin=null;call.reject("Google 연결 실패. Google Cloud에서 Drive API와 Android OAuth 패키지명·서명 SHA-1 등록을 확인해 주세요.");});
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(RecordingExportPlugin.class);
        registerPlugin(DriveBackupPlugin.class);
        registerPlugin(BlackboxServicePlugin.class);   // super.onCreate 이전에 등록해야 함
        super.onCreate(savedInstanceState);
        if(!getPreferences(0).getBoolean("driveRemoved",false)){getSharedPreferences("drive",0).edit().clear().apply();getPreferences(0).edit().putBoolean("driveRemoved",true).apply();}

        DriveBackup.schedule(this);

        // 안드로이드 15+ 강제 전체화면(edge-to-edge): 상태바·내비게이션바·폴드 힌지 영역만큼 화면을 안쪽으로 줄여 하단 잘림 방지
        getWindow().getDecorView().setBackgroundColor(android.graphics.Color.BLACK);
        getWindow().setStatusBarColor(android.graphics.Color.BLACK);
        getWindow().setNavigationBarColor(android.graphics.Color.BLACK);
        androidx.core.view.WindowInsetsControllerCompat bars=androidx.core.view.WindowCompat.getInsetsController(getWindow(),getWindow().getDecorView());
        bars.setAppearanceLightStatusBars(false);bars.setAppearanceLightNavigationBars(false);
        View content = findViewById(android.R.id.content);content.setBackgroundColor(android.graphics.Color.BLACK);
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            Insets i = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(i.left, i.top, i.right, i.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        WebView wv = getBridge().getWebView();
        // 화면이 꺼지거나 앱이 가려져도 WebView 렌더러를 '중요' 우선순위로 유지
        if (Build.VERSION.SDK_INT >= 26) {
            wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        }
        wv.getSettings().setMediaPlaybackRequiresUserGesture(false);
        wv.getSettings().setTextZoom(Math.max(100,Math.round(100*getResources().getConfiguration().fontScale)));

        // 뒤로 가기: 설정창이 열려 있으면 닫고(WebView history), 아니면 앱 종료 대신 홈으로 보내기
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                WebView w = getBridge().getWebView();
                if (w != null) w.evaluateJavascript("window.appBack ? window.appBack() : false", value -> {if(!"true".equals(value))moveTaskToBack(true);});
                else moveTaskToBack(true);
            }
        });
    }
    @Override protected void onNewIntent(android.content.Intent intent){super.onNewIntent(intent);setIntent(intent);
        if(getBridge()!=null)getBridge().getWebView().evaluateJavascript("window.checkLaunchExport && window.checkLaunchExport()",null);
    }

}


