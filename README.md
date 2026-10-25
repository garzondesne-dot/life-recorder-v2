# Life Recorder v2.0 — 테스트 빌드 소스

이 ZIP은 APK가 아닌 전체 프로젝트 소스입니다. Android 컴파일·린트·실기기 검증은 아직 실행하지 못했습니다.

## 적용 및 빌드

1. 압축을 풉니다. `life-recorder-v2` 폴더 **안쪽 내용**을 기존 저장소 루트에 같은 경로로 덮어씁니다. 기존 앱 삭제는 하지 마세요.
2. `www/`, `native/`, `scripts/`, `tests/`, `package.json`, `.github/workflows/build-apk.yml`을 모두 반영합니다. 고정 키 `keystore/debug.keystore`는 기존과 동일합니다.
3. GitHub Actions → Build Life Recorder v2 APK → 실행합니다. main/master에 push하면 자동 실행됩니다.
4. 성공한 실행의 Artifacts → `LifeRecorder-v2-APK` → 압축 해제 → `LifeRecorder-v2.apk`를 덮어쓰기 설치합니다.
5. 실패했다면 Actions의 첫 오류와 앞뒤 로그를 확인합니다. `LifeRecorder-v2-build-reports`에 린트 보고서가 포함됩니다.

## 변경 파일과 위치

| 경로 | 역할 |
|---|---|
| www/index.html | 기존 UI에 네이티브 상태·파일 목록·구글 계정 연결·업로드 상태 연결 |
| native/NativeRecorder.java | AudioRecord → PCM VAD → AAC 저널 → 표준 m4a, 0.6초 프리롤 |
| native/BlackboxService.java | WebView와 독립적인 녹음 수명, 알림 중지, 취침 알람·웨이크락 |
| native/BlackboxServicePlugin.java | 마이크 권한, 서비스 제어, 파일 열기·공유, 드라이브 연결 |
| native/RecordingStore.java | 녹음 파일과 계정별 업로드 결과를 원자적으로 저장 |
| native/DriveSync.java | Google AuthorizationClient, WorkManager 네트워크 재시도·정기 동기화 |
| native/MainActivity.java | 구글 계정 선택·동의 결과, 폴드 인셋 |
| scripts/patch-android.mjs | 네이티브 코드 복사, 서비스 유지, 고정 서명, 버전2, Android10 이상 |
| .github/workflows/build-apk.yml | JS 검사·회귀 테스트·APK 컴파일·린트·아티팩트 |

## 기능 및 제한

- 내장 마이크를 요청하고 실제 경로를 확인합니다. SCO·통화 오디오 모드·스피커·오디오 포커스를 조작하지 않습니다. 경로가 외부 입력으로 바뀌면 오류를 표시하고 중지합니다. 차량 소리 문제 해결 여부는 실기기 검증이 필요합니다.
- 실제 녹음은 서비스의 전용 스레드에서 진행합니다. `stopWithTask=false`로 최근 앱 제거 후 서비스 유지가 가능하도록 구성했습니다. 삼성의 절전·프로세스 강제 종료·설정의 강제 중지·재부팅까지 생존을 보장하지 않습니다.
- 16kHz 모노 AAC, 16/24/32/48kbps, 50ms RMS, 2프레임 연속 감지, 0.6초 프리롤, 설정한 무음 유지 시간, 정각 분할을 지원합니다. 음질·분할 변경은 다음 파일부터 적용됩니다.
- AAC 임시 파일은 앱 내부 `pending/`에 즉시 기록하고 10초마다 디스크 동기화합니다. 비정상 종료 후 **다음 녹음 시작 시** 남은 저널을 복구합니다. 전원 손실·저장장치 문제에서는 일부 오디오가 손실될 수 있습니다.
- 완성 m4a는 `Documents/LifeRecorder/YYYY-MM-DD/`에 저장합니다. 파일 재포장은 별도 스레드로 처리합니다. 파일 목록에서 재생 앱 또는 공유 메뉴를 열 수 있습니다.
- 구글 연동 설정은 `GOOGLE-DRIVE-SETUP.md`를 따릅니다. Google Cloud 콘솔 설정 없이는 연결할 수 없습니다. 녹음 기능에는 영향이 없습니다.
- 이 버전의 파일만 자동 업로드합니다. 이전 IndexedDB 녹음은 기존 목록에서 유지되며 자동 이관하지 않습니다. 앱 삭제 시 내부 설정·인덱스·저널은 삭제됩니다.
- 취침 시 마이크와 녹음 웨이크락을 해제합니다. 파일 저장·업로드 중에는 일시적으로 CPU를 사용합니다. 정확 알람 권한이 없으면 기상이 늦어질 수 있습니다.
- 마이크 FGS는 앱 화면에서 녹음을 시작해야 합니다. 재부팅 자동 녹음은 추가하지 않았습니다.

## 검증 상태

실행 완료: `npm run check`, `npm test`. JS 문법, 네이티브 UI 시작/중복시작/상태/정지, Android 패치 재실행·권한·서비스·서명 설정 회귀 테스트 통과.
미실행: Java/Gradle 컴파일, Android lint, APK 설치, 실제 오디오·블루투스·Google OAuth·Drive 업로드. 이 환경에는 Android SDK/JDK가 없고 저장소 접근이 거부됩니다.

## 설치 후 확인 (권장 순서)

1. 분할 1분, VAD OFF로 2분 녹음 → 파일 2개 재생·길이 확인.
2. VAD ON → 10초 무음·10초 발화 반복 → 시작 음절과 무음 제거 확인.
3. 차량 음악 재생 중 녹음 시작/정지 및 블루투스 연결/해제 → 소리와 내장 마이크 경로 확인.
4. 녹음 시작 → 화면 끄기·최근 앱 모두 닫기 → 3분 후 재실행해 상태·파일 확인.
5. 취침 시각을 가까이 설정 → 취침 중 마이크 해제와 예약 기상 확인.
6. Google Cloud 설정 후 계정 연결 → 파일 업로드 → 비행기 모드 해제 후 재시도·중복 여부 확인.
7. 강제 중지 후 재실행·녹음 시작 → 복구 파일 확인. 마지막 30분 화면 꺼짐 배터리 테스트.
