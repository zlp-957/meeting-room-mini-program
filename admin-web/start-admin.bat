@echo off
setlocal enabledelayedexpansion
title Meeting Room - Admin Web (port 3000)

echo ============================================================
echo     Meeting Room Admin Web - Starting
echo ============================================================
echo.

cd /d "%~dp0"

rem Verify node is available（依赖 PATH 里的 node/npm，安装 Node.js 时会自动加入）
node --version >nul 2>nul
if errorlevel 1 goto no_node

echo [INFO] Node :
node --version
echo.

rem Check dependencies
if exist "node_modules" goto start_dev

echo [INFO] Dependencies not found. Installing via npm install ...
echo        This may take a few minutes ...
echo.
call npm install
if errorlevel 1 goto npm_fail

:start_dev
echo [INFO] Working dir: %cd%
echo [INFO] Starting Vite dev server on port 3000 ...
echo [INFO] Access: http://localhost:3000
echo [INFO] Login : admin / 123456
echo.
echo [INFO] Press Ctrl+C to stop.
echo ============================================================
echo.

call npm run dev

echo.
echo [INFO] Admin Web stopped.
pause
endlocal
exit /b 0

:no_node
echo [ERROR] Node.js not found.
echo        Please install Node.js 16+ from https://nodejs.org/
echo.
pause
endlocal
exit /b 1

:npm_fail
echo [ERROR] npm install failed. Check your network.
echo.
pause
endlocal
exit /b 1
