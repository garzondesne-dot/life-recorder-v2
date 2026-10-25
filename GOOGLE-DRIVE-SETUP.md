# Google Drive 설정 (최초 1회)

1. Google Cloud Console에서 프로젝트를 생성하고 Google Drive API를 사용 설정합니다.
2. Google Auth Platform에서 앱 이름과 지원 이메일을 설정하고, 테스트 상태라면 사용할 구글 계정을 테스트 사용자로 추가합니다.
3. OAuth 클라이언트 유형을 Android로 선택합니다.
   - 패키지명: `com.lifeblackbox.recorder`
   - SHA-1: `88:C2:BE:5B:00:4F:A2:A1:6E:AA:23:73:F9:FE:DA:90:0E:85:21:6B`
4. 데이터 액세스 범위: `https://www.googleapis.com/auth/drive.file`, `https://www.googleapis.com/auth/userinfo.email`.
5. APK 설치 → 설정 → 구글 계정 연결 → 계정 선택 및 동의.

클라이언트 비밀번호나 서비스 계정 키를 앱에 넣지 않습니다. 이 구현은 서버 없이 Android AuthorizationClient를 사용합니다.
앱이 생성한 Drive 파일만 접근하며, 새 버전에서 저장한 녹음부터 업로드합니다. 이전 버전의 녹음은 자동 이관하지 않습니다.
업로드 대상: 내 드라이브/LifeRecorder/YYYY-MM-DD/. 원본은 업로드 이후에도 휴대폰에 유지합니다.
연결 후 네트워크 연결 시 자동 업로드하고 실패 시 재시도합니다. 배터리 절약 정책에 따라 지연될 수 있습니다.
연결 해제는 업로드를 중단하며 이미 업로드한 파일은 삭제하지 않습니다. 권한 철회는 Google 계정의 타사 앱 및 서비스에서 할 수 있습니다.
큰 파일은 재개 가능 업로드 프로토콜로 전송하지만 연결이 끊기면 파일 전체를 다시 전송합니다. 셀룰러 데이터도 사용합니다.
