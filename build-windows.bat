@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
echo ===== Life Blackbox APK 빌드 (Windows) =====

where node >nul 2>nul || (echo [오류] Node.js가 없습니다. https://nodejs.org 에서 LTS 설치 후 다시 실행하세요. & pause & exit /b 1)

rem Android Studio 내장 JDK 자동 지정
if "%JAVA_HOME%"=="" if exist "C:\Program Files\Android\Android Studio\jbr" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
if "%JAVA_HOME%"=="" (echo [오류] JAVA_HOME을 찾을 수 없습니다. Android Studio를 설치하세요. & pause & exit /b 1)

rem Android SDK 위치
if "%ANDROID_HOME%"=="" set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
if not exist "%ANDROID_HOME%" (echo [오류] Android SDK가 없습니다. Android Studio를 한 번 실행해 SDK 설치를 마치세요. & pause & exit /b 1)

call npm install || goto :fail
if not exist android (call npx cap add android || goto :fail)
call node scripts\patch-android.mjs || goto :fail
call npx cap sync android || goto :fail

echo sdk.dir=%ANDROID_HOME:\=\\%> android\local.properties
cd android
call gradlew.bat assembleDebug || goto :fail
cd ..
copy /Y android\app\build\outputs\apk\debug\app-debug.apk LifeBlackbox.apk >nul
echo.
echo ===== 완료: %CD%\LifeBlackbox.apk =====
pause
exit /b 0

:fail
echo [실패] 위 오류 메시지를 확인하세요.
pause
exit /b 1
