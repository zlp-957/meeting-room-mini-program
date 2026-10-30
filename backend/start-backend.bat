@echo off
setlocal enabledelayedexpansion
title Meeting Room - Backend (port 8080)

echo ============================================================
echo     Meeting Room Backend - Starting
echo ============================================================
echo.

:: Check Maven（用 PATH 里的 mvn，不写死本机路径）
where mvn >nul 2>nul
if errorlevel 1 (
    echo [ERROR] Maven not found. Please install Maven 3.6+ and add it to PATH.
    echo         Download: https://maven.apache.org/download.cgi
    echo         Or use Maven Wrapper: mvnw.cmd spring-boot:run
    echo.
    pause
    exit /b 1
)
set "MVN=mvn"

:: Check Java
where java >nul 2>nul
if errorlevel 1 (
    echo [ERROR] Java not found. Please install JDK 8 or higher.
    echo.
    pause
    exit /b 1
)

cd /d "%~dp0"

echo [INFO] Working dir: %cd%
echo [INFO] Starting Spring Boot on port 8080 ...
echo [INFO] First run will download dependencies, please wait ...
echo.
echo [INFO] Access after startup:
echo        - API docs (Knife4j): http://localhost:8080/doc.html
echo        - Service           : http://localhost:8080
echo.
echo [INFO] Press Ctrl+C to stop.
echo ============================================================
echo.

"%MVN%" spring-boot:run -Dspring-boot.run.arguments=--server.port=8080

echo.
echo [INFO] Backend stopped.
pause
endlocal
