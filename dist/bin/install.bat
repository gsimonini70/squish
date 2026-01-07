@echo off
REM ========================================
REM Squish - Windows Installation Script
REM ========================================
setlocal EnableDelayedExpansion

set APP_NAME=Squish
set VERSION=2.0.0
set INSTALL_DIR=C:\squish

echo.
echo ========================================
echo   %APP_NAME% v%VERSION% - Installer
echo   Designed by Lucsartech Srl
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

REM Check Java
echo Checking Java...
java -version >nul 2>&1
if %errorlevel% neq 0 (
    echo ERROR: Java not found. Please install Java 22 or higher.
    pause
    exit /b 1
)
echo [OK] Java found

REM Create directories
echo Creating directories...
if not exist "%INSTALL_DIR%" mkdir "%INSTALL_DIR%"
if not exist "%INSTALL_DIR%\bin" mkdir "%INSTALL_DIR%\bin"
if not exist "%INSTALL_DIR%\config" mkdir "%INSTALL_DIR%\config"
if not exist "%INSTALL_DIR%\logs" mkdir "%INSTALL_DIR%\logs"
echo [OK] Directories created

REM Copy files
echo Installing files...
set SCRIPT_DIR=%~dp0
set SRC_DIR=%SCRIPT_DIR%..

copy "%SRC_DIR%\squish.jar" "%INSTALL_DIR%\" >nul 2>&1
if %errorlevel% neq 0 (
    copy "%SCRIPT_DIR%..\squish.jar" "%INSTALL_DIR%\" >nul
)

copy "%SCRIPT_DIR%squish.bat" "%INSTALL_DIR%\bin\" >nul
echo [OK] JAR and scripts installed

REM Copy config if not exists
if not exist "%INSTALL_DIR%\config\application.yml" (
    copy "%SRC_DIR%\config\application.yml" "%INSTALL_DIR%\config\" >nul 2>&1
)
echo [OK] Configuration installed

REM Add to PATH (optional)
echo.
set /p ADD_PATH="Add Squish to system PATH? [Y/n]: "
if /i "%ADD_PATH%" neq "n" (
    setx PATH "%PATH%;%INSTALL_DIR%\bin" /M >nul 2>&1
    echo [OK] Added to PATH
)

REM Create Windows Service (optional)
echo.
set /p CREATE_SVC="Install as Windows Service? [Y/n]: "
if /i "%CREATE_SVC%" neq "n" (
    echo.
    echo To install as Windows Service, use NSSM:
    echo   1. Download NSSM from https://nssm.cc/
    echo   2. Run: nssm install Squish
    echo   3. Set Path: java
    echo   4. Set Arguments: -jar %INSTALL_DIR%\squish.jar
    echo   5. Set Startup directory: %INSTALL_DIR%
    echo.
)

echo.
echo ========================================
echo   Installation Complete!
echo ========================================
echo.
echo Installation directory: %INSTALL_DIR%
echo Configuration: %INSTALL_DIR%\config\
echo Logs: %INSTALL_DIR%\logs\
echo.
echo IMPORTANT: Edit configuration before starting:
echo   notepad %INSTALL_DIR%\config\application.yml
echo.
echo Commands:
echo   cd %INSTALL_DIR%
echo   bin\squish.bat start
echo   bin\squish.bat stop
echo   bin\squish.bat status
echo.
echo Dashboard: http://localhost:8080/
echo.
pause
