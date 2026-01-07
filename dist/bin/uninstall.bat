@echo off
REM ========================================
REM Squish - Windows Uninstallation Script
REM ========================================
setlocal EnableDelayedExpansion

set APP_NAME=Squish
set INSTALL_DIR=C:\squish

echo.
echo ========================================
echo   %APP_NAME% - Uninstaller
echo ========================================
echo.

REM Check for admin rights
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo ERROR: This script requires Administrator privileges.
    echo Right-click and select "Run as administrator"
    pause
    exit /b 1
)

REM Confirm
echo This will uninstall %APP_NAME% from %INSTALL_DIR%
echo.
set /p CONFIRM="Are you sure? [y/N]: "
if /i "%CONFIRM%" neq "y" (
    echo Aborted.
    pause
    exit /b 0
)

REM Stop service if running
echo Stopping service...
for /f "tokens=1" %%p in ('jps -l 2^>nul ^| findstr squish.jar') do (
    taskkill /PID %%p /F >nul 2>&1
)

REM Stop Windows Service if exists
sc query Squish >nul 2>&1
if %errorlevel% equ 0 (
    echo Stopping Windows Service...
    sc stop Squish >nul 2>&1
    sc delete Squish >nul 2>&1
    echo [OK] Windows Service removed
)

REM Ask about config/logs
set KEEP_CONFIG=0
set /p KEEP="Keep configuration and logs? [y/N]: "
if /i "%KEEP%" equ "y" set KEEP_CONFIG=1

REM Remove files
echo Removing files...

del /q "%INSTALL_DIR%\squish.jar" 2>nul
del /q "%INSTALL_DIR%\squish.pid" 2>nul
del /q "%INSTALL_DIR%\squish.log" 2>nul
rmdir /s /q "%INSTALL_DIR%\bin" 2>nul

if %KEEP_CONFIG% equ 0 (
    rmdir /s /q "%INSTALL_DIR%\config" 2>nul
    rmdir /s /q "%INSTALL_DIR%\logs" 2>nul
    echo [OK] Configuration and logs removed
) else (
    echo [WARN] Configuration and logs kept
)

REM Try to remove main directory
rmdir "%INSTALL_DIR%" 2>nul
if exist "%INSTALL_DIR%" (
    echo [WARN] Directory not empty: %INSTALL_DIR%
) else (
    echo [OK] Installation directory removed
)

echo.
echo ========================================
echo   Uninstallation Complete!
echo ========================================
echo.
pause
