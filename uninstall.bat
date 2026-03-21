@echo off
REM NordicFTMS Uninstaller for Windows

set SCRIPT_DIR=%~dp0
set ADB=%SCRIPT_DIR%adb.exe

REM Verify ADB exists
if not exist "%ADB%" (
    echo Error: adb.exe not found in %SCRIPT_DIR%
    echo Make sure adb.exe is in the same folder as this script.
    pause
    exit /b 1
)

echo.
echo ===================================
echo   NordicFTMS Uninstaller
echo ===================================
echo.
echo Enter the IP address of your treadmill/bike.
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

REM Stop the service
echo.
echo Stopping NordicFTMS...
"%ADB%" shell am force-stop com.nordicftms.app

REM Uninstall
echo.
echo Uninstalling NordicFTMS...
"%ADB%" uninstall com.nordicftms.app
if errorlevel 1 (
    echo Error: Uninstall failed. NordicFTMS may not be installed.
    pause
    exit /b 1
)

echo.
echo ===================================
echo   Uninstall complete!
echo ===================================
echo.
echo NordicFTMS has been removed from your device.
echo.
pause
