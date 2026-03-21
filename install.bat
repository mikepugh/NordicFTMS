@echo off
REM NordicFTMS Installer for Windows

set SCRIPT_DIR=%~dp0
set ADB=%SCRIPT_DIR%adb.exe
set APK=%SCRIPT_DIR%NordicFTMS.apk

REM Verify ADB exists
if not exist "%ADB%" (
    echo Error: adb.exe not found in %SCRIPT_DIR%
    echo Make sure adb.exe is in the same folder as this script.
    pause
    exit /b 1
)

REM Verify APK exists
if not exist "%APK%" (
    echo Error: NordicFTMS.apk not found in %SCRIPT_DIR%
    pause
    exit /b 1
)

echo.
echo ===================================
echo   NordicFTMS Installer
echo ===================================
echo.
echo Enter the IP address of your treadmill/bike.
echo You can find this in Settings ^> Network on your machine.
echo.
set /p DEVICE_IP="Device IP address: "

if "%DEVICE_IP%"=="" (
    echo Error: No IP address entered.
    pause
    exit /b 1
)

REM Connect to device
echo.
echo Connecting to %DEVICE_IP%:5555...
"%ADB%" connect %DEVICE_IP%:5555
if errorlevel 1 (
    echo Error: Could not connect to %DEVICE_IP%:5555
    echo Make sure USB debugging is enabled and the device is on the same network.
    pause
    exit /b 1
)

REM Install APK
echo.
echo Installing NordicFTMS...
"%ADB%" install -r "%APK%"
if errorlevel 1 (
    echo Error: Installation failed.
    pause
    exit /b 1
)

echo.
echo ===================================
echo   Installation complete!
echo ===================================
echo.
echo Reboot your treadmill/bike to start NordicFTMS.
echo After reboot, open your FTMS app and scan for Bluetooth devices.
echo.
pause
