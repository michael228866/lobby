@echo off
setlocal
cd /d "%~dp0"

set "APK=%~1"
if not defined APK set "APK=app\build\outputs\apk\debug\app-debug.apk"

where adb >nul 2>&1
if errorlevel 1 (
    echo ERROR: adb was not found in PATH.
    goto :failed
)

if not exist "%APK%" (
    echo ERROR: APK not found: %APK%
    echo Build the APK first, or drag an APK file onto this BAT.
    goto :failed
)

adb get-state >nul 2>&1
if errorlevel 1 (
    echo ERROR: No authorized Quest headset is connected.
    goto :failed
)

echo Installing %APK%...
adb install -r "%APK%"
if errorlevel 1 goto :failed

echo Applying kiosk permissions...
adb shell appops set com.quest.lobby GET_USAGE_STATS allow
if errorlevel 1 goto :failed
adb shell appops set --uid com.quest.lobby MANAGE_EXTERNAL_STORAGE allow
if errorlevel 1 goto :failed

echo Restarting Lobby...
adb shell am force-stop com.quest.lobby
adb shell am start -n com.quest.lobby/.MainActivity
if errorlevel 1 goto :failed

echo.
echo Installation complete. Current permissions:
adb shell appops get com.quest.lobby GET_USAGE_STATS
adb shell appops get com.quest.lobby MANAGE_EXTERNAL_STORAGE
echo.
pause
exit /b 0

:failed
echo.
echo Installation failed.
pause
exit /b 1
