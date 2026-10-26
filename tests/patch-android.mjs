import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import {execFileSync} from 'node:child_process';
import assert from 'node:assert/strict';
const tmp=fs.mkdtempSync(path.join(os.tmpdir(),'life-patch-'));
try {
  fs.cpSync('native',path.join(tmp,'native'),{recursive:true});
  fs.mkdirSync(path.join(tmp,'scripts'));
  fs.copyFileSync('scripts/patch-android.mjs',path.join(tmp,'scripts/patch-android.mjs'));
  const main=path.join(tmp,'android/app/src/main');fs.mkdirSync(path.join(main,'res'),{recursive:true});
  fs.writeFileSync(path.join(main,'AndroidManifest.xml'),'<manifest><application><service android:name=".BlackboxService" android:stopWithTask="true" /></application></manifest>');
  fs.writeFileSync(path.join(tmp,'android/app/build.gradle'),'android { defaultConfig { versionCode 1\nversionName "1.0" } }');
  for(let i=0;i<2;i++)execFileSync(process.execPath,[path.join(tmp,'scripts/patch-android.mjs')],{stdio:'pipe'});
  const manifest=fs.readFileSync(path.join(main,'AndroidManifest.xml'),'utf8');
  assert.ok(manifest.includes('stopWithTask="false"'));assert.equal((manifest.match(/android.permission.RECORD_AUDIO/g)||[]).length,1);
  const gradle=fs.readFileSync(path.join(tmp,'android/app/build.gradle'),'utf8');
  assert.equal((gradle.match(/play-services-auth:/g)||[]).length,0);assert.equal((gradle.match(/LIFE_FIXED_SIGNING/g)||[]).length,1);
  assert.ok(gradle.includes('versionCode 7'));
  for(const f of ['NativeRecorder.java','RecordingExportPlugin.java','RecordingStore.java'])assert.ok(fs.existsSync(path.join(main,'java/com/lifeblackbox/recorder',f)));
  assert.equal((manifest.match(/com.google.android.apps.bard/g)||[]).length,1);
  assert.ok(!gradle.includes('androidx.work:work-runtime'));
  assert.ok(manifest.includes('ExportFileProvider'));
  assert.ok(fs.existsSync(path.join(main,'res/xml/export_paths.xml')));
  console.log('Android patch permissions / service survival / dependencies / signing / idempotence: PASS');
}finally{fs.rmSync(tmp,{recursive:true,force:true});}


