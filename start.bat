@echo off
setlocal
title Meeting Room System - Launcher

:menu
cls
echo ============================================================
echo     Meeting Room Reservation System - Launcher
echo ============================================================
echo.
echo   [1] Start Backend        (Spring Boot, port 8080)
echo   [2] Start Admin Web      (Vue3 + Vite, port 3000)
echo   [3] Open Mini Program    (miniprogram, via WeChat DevTools)
echo   [4] Start ALL (Backend + Admin Web)
echo   [5] Exit
echo.
echo ============================================================
set /p choice="Please enter your choice (1-5): "

if "%choice%"=="1" goto backend
if "%choice%"=="2" goto admin
if "%choice%"=="3" goto miniapp
if "%choice%"=="4" goto all
if "%choice%"=="5" goto end
goto menu

:backend
echo.
echo Starting Backend on port 8080 ...
echo.
call "%~dp0backend\start-backend.bat"
goto end

:admin
echo.
echo Starting Admin Web on port 3000 ...
echo.
call "%~dp0admin-web\start-admin.bat"
goto end

:miniapp
echo.
echo ============================================================
echo   Mini Program (WeChat native)
echo ============================================================
echo.
echo   The mini program sources are in the "miniprogram" folder.
echo   WeChat DevTools is required to run it:
echo     1. Launch WeChat DevTools
echo     2. Choose "Import project"
echo     3. Select directory: %~dp0miniprogram
echo     4. AppID: use your own or a test AppID
echo.
echo   Opening the folder now ...
start "" "%~dp0miniprogram"
echo.
pause
goto end

:all
echo.
echo Starting Backend AND Admin Web ...
echo.
start "Meeting-Backend" cmd /k "call %~dp0backend\start-backend.bat"
start "Meeting-Admin"   cmd /k "call %~dp0admin-web\start-admin.bat"
echo.
echo Both services are starting in separate windows.
echo Backend  : http://localhost:8080
echo Admin Web: http://localhost:3000
echo.
pause
goto end

:end
endlocal
