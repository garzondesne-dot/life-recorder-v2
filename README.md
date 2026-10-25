# Life Recorder v2.1

구글 계정 로그인·드라이브 동기화·자동 업로드를 제거하고, 녹음 폴더 전체 불러오기와 Gemini 공유를 추가했습니다.
녹음 엔진은 기존 네이티브 엔진을 유지합니다. 설정 화면에서 카드들이 눌려 잘리는 flex 레이아웃을 수정했습니다.

## 설치 / 빌드

1. ZIP의 폴더 안쪽 내용을 저장소 루트에 같은 경로로 덮어씁니다. www / native / scripts / tests / package.json을 모두 반영합니다.
2. 기존 .github/workflows/build-apk.yml은 그대로 사용합니다. 추가 수정 없이 push하면 기존 Actions가 실행됩니다.
3. Actions 성공 → Artifacts → LifeRecorder-v2-APK → LifeRecorder-v2.apk를 설치합니다. APK 이름은 기존 워크플로 때문에 v2이지만 앱 버전은 2.1.0 / versionCode 3입니다.
4. 기존 고정 keystore를 그대로 사용하므로 기존 앱을 삭제하지 않고 업데이트합니다. 앱을 삭제하면 내부 목록·설정·미완성 저널이 사라질 수 있습니다.

## Gemini로 녹음 폴더 보내기

1. 앱의 [저장 파일] 탭을 엽니다.
2. [앱 녹음 전체]로 앱 인덱스의 저장 완료된 파일을 불러오거나, [녹음 폴더 선택]으로 Android 폴더 선택 창을 엽니다.
3. 내장 저장공간/Documents/LifeRecorder 또는 날짜 폴더를 고르고 [이 폴더 사용]을 누릅니다. 하위 폴더의 오디오도 포함합니다.
4. 모든 파일을 파일명 순서로 불러오고, 최대 10개씩 묶음으로 준비합니다. 5개 / 1개씩으로 바꿀 수도 있습니다.
5. [Gemini로 보내기]를 누릅니다. Gemini가 해당 공유 형식을 받으면 앱을 직접 엽니다. 아니면 Android 공유 메뉴를 엽니다.
6. Gemini에서 첨부된 파일을 확인하고 전송하세요. 완료 후 이 앱으로 돌아와 [다음 묶음]을 누릅니다. 앱은 공유 화면을 열었다는 이유로 업로드 성공이나 완료를 표시하지 않습니다.

Gemini가 공유 메뉴에 없거나 파일을 받지 않는 버전이면, [Gemini 열기] → Gemini의 + → 파일 → Documents/LifeRecorder에서 길게 눌러 여러 개를 선택하세요.
일부 Gemini 버전은 외부 앱의 여러 오디오 공유를 받지 않을 수 있습니다. 이 앱은 Gemini의 업로드 화면·서버를 제어하지 않으며 자동 전송하지 않습니다.
API 키, 서버, OAuth 연결이 필요하지 않습니다. 네트워크 업로드와 전송은 사용자가 선택한 Gemini 앱에서 처리합니다.

## Gemini 제한

Google 공식 안내 기준: 한 프롬프트 최대 10개(제공 상태에 따라 변경 가능), 오디오 등 일반 파일당 최대 100MB.
오디오 합계는 기본 계정 10분, Google AI Pro·Ultra 3시간입니다. 파일 개수와 길이 한도는 별개입니다.
100MB 초과 파일이 포함된 묶음은 전송을 막습니다. 폴더 선택으로 읽은 파일은 오디오 길이를 모를 수 있어 Gemini에서 길이 한도를 확인해야 합니다.
오디오 ZIP은 지원되지 않으므로 ZIP으로 압축해서 보내지 않습니다. 큰 파일의 재분할·병합은 이번 버전에서 하지 않습니다.
공식 안내: https://support.google.com/gemini/answer/14903178?co=GENIE.Platform%3DAndroid&hl=ko

## 설정 화면

- 스크롤 컨테이너에 min-height:0, 항목에 flex-shrink:0을 적용해 카드 내용이 잘리지 않게 수정.
- 작은 화면에서 설명·설정값·시간 입력이 줄바꿈되도록 수정.
- 임시로 삽입했던 드라이브 박스를 제거하고 녹음 상태를 기존 카드 스타일에 통합.
- 배터리 최적화 상태를 확인하지 않고 '허용됨'으로 표시하던 기본 문구 수정.

## 변경 경로

www/index.html, native/MainActivity.java, native/BlackboxServicePlugin.java, native/NativeRecorder.java,
native/RecordingStore.java, native/RecordingExportPlugin.java(신규), native/DriveSync.java(빈 폐기 파일),
scripts/patch-android.mjs, tests/native-ui.mjs, tests/patch-android.mjs, package.json, README.md, GOOGLE-DRIVE-SETUP.md.

DriveSync.java의 빈 파일은 ZIP 덮어쓰기 시 옛 코드를 지우기 위한 대체 파일입니다. 저장소에서 삭제해도 됩니다.
Android 생성 폴더의 옛 DriveSync.java와 인증·WorkManager 의존성은 패치 스크립트가 제거합니다.

## 검증

통과: JS node --check, 네이티브 시작/중복시작/정지 UI 회귀 테스트, 폴더 전체 27개→10/10/7 묶음·중복 제거·취소 시 보존·자동 완료 표시 금지,
Android 패치 재실행·권한·고정 서명·버전 증가·공유 대상 조회 설정·드라이브 의존성 제거 테스트.
미검증: Android Java 컴파일, APK 설치, 삼성 실기기 레이아웃, 시스템 폴더 선택 창, Gemini의 실제 파일 수신/전송.

## 주의할 동작

- 녹음 중인 파일은 분할 또는 중지 후 저장이 끝나야 목록에 포함됩니다. 폴더를 불러온 뒤 새 파일이 생기면 다시 불러오세요.
- 외부 파일 앱에서 지운 녹음은 [앱 녹음 전체]에서 제외됩니다.
- 폴더 선택은 해당 폴더 읽기 권한만 사용합니다. 전체 저장소 권한이나 구글 계정 권한을 요청하지 않습니다.
- 폴더 내부 파일 수 10,000개 또는 하위 폴더 10,000개를 넘으면 날짜별 작은 폴더를 고르도록 안내합니다. 일부 파일을 조용히 누락시키지 않습니다.
- 화면 꺼짐·최근 앱 제거·강제 종료·취침 예약 등 녹음 엔진의 한계는 기존 v2와 같습니다. 강제 중지/재부팅 후 자동 녹음하지 않습니다.
