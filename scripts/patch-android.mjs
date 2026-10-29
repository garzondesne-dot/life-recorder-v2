// npx cap add android 직후 실행: 네이티브 서비스·권한을 Android 프로젝트에 주입 (여러 번 실행해도 안전)
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const android = path.join(root, 'android');
if (!fs.existsSync(android)) {
  console.error('[patch] android 폴더가 없습니다. 먼저 "npx cap add android"를 실행하세요.');
  process.exit(1);
}

// 1) Java 소스 복사
const pkgDir = path.join(android, 'app/src/main/java/com/lifeblackbox/recorder');
fs.mkdirSync(pkgDir, { recursive: true });
for (const f of ['MainActivity.java', 'BlackboxService.java', 'BlackboxServicePlugin.java', 'NativeRecorder.java', 'RecordingStore.java', 'RecordingExportPlugin.java', 'DailyLog.java', 'TimelineSegments.java', 'RecordingPublication.java', 'ExportSnapshot.java', 'ExportFileProvider.java', 'ReminderPolicy.java', 'ExportReminder.java', 'RestSchedule.java', 'RestNotifications.java', 'RetentionManager.java', 'RecordingWindow.java', 'WindowMerger.java', 'DrivePolicy.java', 'DriveHttp.java', 'DriveBackup.java', 'DriveBackupPlugin.java', 'DriveBackupWorker.java']) {
  fs.copyFileSync(path.join(root, 'native', f), path.join(pkgDir, f));
  console.log('[patch] copied', f);
}
// Drop generated old source; the recorder no longer links sync libraries.
const oldSync = path.join(pkgDir, 'DriveSync.java');
if (fs.existsSync(oldSync)) fs.rmSync(oldSync);
// 템플릿이 Kotlin MainActivity를 만든 경우 중복 클래스 방지
const kt = path.join(pkgDir, 'MainActivity.kt');
if (fs.existsSync(kt)) fs.rmSync(kt);


// 1-b) 앱 아이콘 교체 (native/res → android/app/src/main/res)
const resSrc = path.join(root, 'native/res');
const resDst = path.join(android, 'app/src/main/res');
if (fs.existsSync(resSrc)) {
  // 템플릿 기본 아이콘(png/webp/xml)과 이름이 겹치면 빌드 오류 → 먼저 제거
  for (const d of fs.readdirSync(resDst)) {
    if (!d.startsWith('mipmap-')) continue;
    for (const f of fs.readdirSync(path.join(resDst, d))) {
      if (/^ic_launcher(_round|_foreground|_background)?\.(png|webp|xml)$/.test(f)) fs.rmSync(path.join(resDst, d, f));
    }
  }
  fs.cpSync(resSrc, resDst, { recursive: true });
  console.log('[patch] icons copied');
}

// 2) AndroidManifest.xml 권한·서비스 주입
const mf = path.join(android, 'app/src/main/AndroidManifest.xml');
let m = fs.readFileSync(mf, 'utf8');

const perms = [
  'android.permission.RECORD_AUDIO',
  'android.permission.INTERNET',
  'android.permission.ACCESS_NETWORK_STATE',
  'android.permission.MODIFY_AUDIO_SETTINGS',
  'android.permission.FOREGROUND_SERVICE',
  'android.permission.FOREGROUND_SERVICE_MICROPHONE',
  'android.permission.WAKE_LOCK',
  'android.permission.POST_NOTIFICATIONS',
  'android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS',
  'android.permission.SCHEDULE_EXACT_ALARM',
];
let add = '';
for (const p of perms) if (!m.includes(`"${p}"`)) add += `    <uses-permission android:name="${p}" />\n`;
if (!m.includes('android.permission.WRITE_EXTERNAL_STORAGE')) {
  add += '    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="29" />\n';
}
if (add) m = m.replace(/(\s*)<application/, `\n${add}$1<application`);

if (!m.includes('requestLegacyExternalStorage')) {
  m = m.replace('<application', '<application\n        android:requestLegacyExternalStorage="true"');
}

if (!m.includes('.BlackboxService')) {
  m = m.replace('</application>',
`    <service
            android:name=".BlackboxService"
            android:exported="false"
            android:foregroundServiceType="microphone"
            android:stopWithTask="false" />
    </application>`);
}
m = m.replace(/android:stopWithTask="true"/g, 'android:stopWithTask="false"');
if (!m.includes('com.openai.chatgpt')) m=m.replace('</manifest>', '<queries><package android:name="com.openai.chatgpt"/><package android:name="com.anthropic.claude"/></queries></manifest>');
if (!m.includes('com.google.android.apps.bard')) m = m.replace('</manifest>', `
    <queries><package android:name="com.google.android.apps.bard" /></queries>
</manifest>`);
if(!m.includes('${applicationId}.exports'))m=m.replace('</application>', `<provider android:name=".ExportFileProvider" android:authorities="\${applicationId}.exports" android:exported="false" android:grantUriPermissions="true"><meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/export_paths" /></provider></application>`);
const xmlDir=path.join(resDst,'xml');fs.mkdirSync(xmlDir,{recursive:true});
m=m.replace(/android:allowBackup="[^"]*"/g,'android:allowBackup="false"');
if(!m.includes('android:allowBackup='))m=m.replace('<application','<application android:allowBackup="false"');

fs.writeFileSync(path.join(xmlDir,'export_paths.xml'), '<paths xmlns:android="http://schemas.android.com/apk/res/android"><cache-path name="daily_exports" path="exports/" /></paths>');
fs.writeFileSync(mf, m);
console.log('[patch] AndroidManifest.xml updated');
console.log('[patch] done ✔');

// Reproducible native dependencies and application version.
const gradleFile = path.join(android, 'app/build.gradle');
let g = fs.readFileSync(gradleFile, 'utf8');
// Remove obsolete account/sync libraries when reusing an existing Android project.
g = g.replace(/^.*implementation ['"]com\.google\.android\.gms:play-services-auth:[^\n]*\n?/gm, '')
     .replace(/^.*implementation ['"]androidx\.work:work-runtime:[^\n]*\n?/gm, '');
g = g.replace(/versionCode \d+/, 'versionCode 12').replace(/versionName "[^"]*"/, 'versionName "2.4.6"');
// Replace the previous pinned block as well as legacy sync dependencies, idempotently.
g=g.replace(/\n\/\/ LIFE_DRIVE_BACKUP[\s\S]*?\/\/ END_LIFE_DRIVE_BACKUP\n/g,'\n');
g+=`\n// LIFE_DRIVE_BACKUP
dependencies {
    implementation 'com.google.android.gms:play-services-auth:22.0.0'
    implementation 'androidx.work:work-runtime:2.10.1'
}
// END_LIFE_DRIVE_BACKUP
`;
fs.writeFileSync(gradleFile, g);

if (!g.includes('LIFE_FIXED_SIGNING')) {
  g += `
// LIFE_FIXED_SIGNING: preserve the existing installation certificate on every build host.
android {
    defaultConfig { minSdkVersion 29 }
    signingConfigs {
        debug {
            storeFile rootProject.file('../keystore/debug.keystore')
            storePassword 'android'
            keyAlias 'androiddebugkey'
            keyPassword 'android'
        }
    }
}
`;
  fs.writeFileSync(gradleFile, g);
}



// Exercise the real metadata store against Android's ContentResolver in local unit tests.
const testDir=path.join(android,'app/src/test/java/com/lifeblackbox/recorder');
fs.mkdirSync(testDir,{recursive:true});
const tests=path.join(root,'tests/android');
if(fs.existsSync(tests))fs.cpSync(tests,testDir,{recursive:true});
if(!g.includes('LIFE_RELIABILITY_TESTS')){
  fs.appendFileSync(gradleFile, `
// LIFE_RELIABILITY_TESTS
android { testOptions { unitTests.includeAndroidResources = true } }
dependencies {
    testImplementation 'junit:junit:4.13.2'
    testImplementation 'org.robolectric:robolectric:4.14.1'
}
`);
}


// Keep launch surfaces true black in both system light and dark modes.
const stylesFile=path.join(resDst,'values/styles.xml');
if(fs.existsSync(stylesFile)){
  let styles=fs.readFileSync(stylesFile,'utf8');
  styles=styles.replace(/(<item name="windowSplashScreenBackground">)[^<]*/g,'$1@android:color/black');
  if(!styles.includes('name="windowSplashScreenBackground"')) styles=styles.replace(/(<style name="AppTheme.NoActionBarLaunch"[^>]*>)/,'$1<item name="windowSplashScreenBackground">@android:color/black</item>');
  fs.writeFileSync(stylesFile,styles);
}

// Real AAC/MediaStore integration runs only in the disposable CI emulator.
const instrumentDir=path.join(android,'app/src/androidTest/java/com/lifeblackbox/recorder');
fs.mkdirSync(instrumentDir,{recursive:true});
const instrument=path.join(root,'tests/instrumentation');
if(fs.existsSync(instrument))fs.cpSync(instrument,instrumentDir,{recursive:true});
if(!g.includes('LIFE_AUDIO_INTEGRATION'))fs.appendFileSync(gradleFile, `
// LIFE_AUDIO_INTEGRATION
android { defaultConfig { testInstrumentationRunner 'androidx.test.runner.AndroidJUnitRunner' } }
dependencies {
    androidTestImplementation 'androidx.test:runner:1.6.2'
    androidTestImplementation 'androidx.test.ext:junit:1.2.1'
}
`);

// Discard only Capacitor's generated package-name example, replaced by the real integration suite.
for(const pkg of ['myapp','app']){const example=path.join(android,'app/src/androidTest/java/com/getcapacitor',pkg,'ExampleInstrumentedTest.java');if(fs.existsSync(example))fs.rmSync(example);}
