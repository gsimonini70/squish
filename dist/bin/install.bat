@echo off
REM ========================================
REM Squish - Windows Installation Script
REM ========================================
setlocal EnableDelayedExpansion

set APP_NAME=Squish
set INSTALL_DIR=C:\squish

set SCRIPT_DIR=%~dp0
set SRC_DIR=%SCRIPT_DIR%..

REM Version comes from the .env that build-dist.sh writes into the bundle
REM (SQUISH_VERSION=<pom version>). Never hardcode it - it went stale at 2.0.0 once.
set VERSION=unknown
if exist "%SRC_DIR%\.env" (
    for /f "tokens=2 delims==" %%v in ('findstr /b "SQUISH_VERSION=" "%SRC_DIR%\.env"') do set VERSION=%%v
)

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
REM 'reports' is squish.report.directory (default 'reports', relative to the
REM working directory). Without it every PDF report write fails at the end of a run.
echo Creating directories...
if not exist "%INSTALL_DIR%" mkdir "%INSTALL_DIR%"
if not exist "%INSTALL_DIR%\bin" mkdir "%INSTALL_DIR%\bin"
if not exist "%INSTALL_DIR%\config" mkdir "%INSTALL_DIR%\config"
if not exist "%INSTALL_DIR%\logs" mkdir "%INSTALL_DIR%\logs"
if not exist "%INSTALL_DIR%\reports" mkdir "%INSTALL_DIR%\reports"
if not exist "%INSTALL_DIR%\sql" mkdir "%INSTALL_DIR%\sql"
echo [OK] Directories created

REM Copy files
echo Installing files...

copy "%SRC_DIR%\squish.jar" "%INSTALL_DIR%\" >nul 2>&1
if %errorlevel% neq 0 (
    echo ERROR: squish.jar not found in %SRC_DIR%
    pause
    exit /b 1
)

copy "%SCRIPT_DIR%squish.bat" "%INSTALL_DIR%\bin\" >nul
copy "%SCRIPT_DIR%uninstall.bat" "%INSTALL_DIR%\bin\" >nul 2>&1
copy "%SRC_DIR%\sql\*.sql" "%INSTALL_DIR%\sql\" >nul 2>&1
echo [OK] JAR and scripts installed

REM Copy config if not exists
if not exist "%INSTALL_DIR%\config\application.yml" (
    copy "%SRC_DIR%\config\application.yml" "%INSTALL_DIR%\config\" >nul 2>&1
)

REM squish.bat reads config\squish.env.bat - seed it from the template.
REM It holds the DB password AND the dashboard password: restrict it to
REM Administrators + SYSTEM (the NTFS equivalent of chmod 600).
if not exist "%INSTALL_DIR%\config\squish.env.bat" (
    copy "%SRC_DIR%\config\squish.env.bat.template" "%INSTALL_DIR%\config\squish.env.bat" >nul 2>&1
    if exist "%INSTALL_DIR%\config\squish.env.bat" (
        echo [OK] config\squish.env.bat created from template - EDIT IT before starting
    ) else (
        echo [WARN] squish.env.bat.template not found in the bundle
    )
) else (
    echo [WARN] config\squish.env.bat already exists - left untouched
)
if exist "%INSTALL_DIR%\config\squish.env.bat" (
    icacls "%INSTALL_DIR%\config\squish.env.bat" /inheritance:r /grant:r "Administrators:F" /grant:r "SYSTEM:F" >nul 2>&1
    echo [OK] config\squish.env.bat locked down to Administrators + SYSTEM
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
    echo   2. nssm install Squish "%%JAVA_HOME%%\bin\java.exe"
    echo   3. nssm set Squish AppParameters "-jar %INSTALL_DIR%\squish.jar --spring.profiles.active=prod"
    echo   4. nssm set Squish AppDirectory "%INSTALL_DIR%"
    echo.
    echo   IMPORTANT - Squish exits with code 1 on a fatal failure ^(e.g.
    echo   OutOfMemoryError in watchdog mode^) and RELIES on the service manager
    echo   restarting it. Make sure NSSM does:
    echo   5. nssm set Squish AppExit Default Restart
    echo   6. nssm set Squish AppRestartDelay 10000
    echo.
    echo   Without steps 5-6 a crashed Squish stays down until someone notices.
    echo.
)

echo.
echo ========================================
echo   Installation Complete!
echo ========================================
echo.
echo Installation directory: %INSTALL_DIR%
echo Configuration: %INSTALL_DIR%\config\
echo Logs:    %INSTALL_DIR%\logs\
echo Reports: %INSTALL_DIR%\reports\
echo.
echo NEXT STEPS - in this order:
echo.
echo   1. Edit the configuration (DB credentials, dashboard password):
echo        notepad %INSTALL_DIR%\config\squish.env.bat
echo      Set SECURITY_PASSWORD explicitly. If you leave it empty, a random
echo      password is generated and only logged once, at WARN level.
echo.
echo   2. Create the tracking table (once, per schema):
echo        sqlplus user/pass@db @%INSTALL_DIR%\sql\create_tracking_table.sql
echo.
echo   3. Start:
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
