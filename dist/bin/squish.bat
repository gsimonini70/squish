@echo off
REM Squish - PDF Compression Engine
REM Startup script for Windows

setlocal EnableDelayedExpansion

set APP_NAME=Squish
set JAR_FILE=squish.jar
set LOG_FILE=squish.log

REM Get script directory
set SCRIPT_DIR=%~dp0
set APP_HOME=%SCRIPT_DIR%..

cd /d "%APP_HOME%"

REM Load environment file if exists
if exist "%APP_HOME%\config\squish.env.bat" (
    call "%APP_HOME%\config\squish.env.bat"
)

REM Java executable (use JAVA_HOME if set)
if defined JAVA_HOME (
    set JAVA_CMD=%JAVA_HOME%\bin\java.exe
    if not exist "!JAVA_CMD!" (
        echo ERROR: JAVA_HOME is set but !JAVA_CMD! not found
        exit /b 1
    )
) else (
    set JAVA_CMD=java
)

REM Java options
if "%JAVA_OPTS%"=="" set JAVA_OPTS=-Xms256m -Xmx2g

REM Profile
if "%SPRING_PROFILES_ACTIVE%"=="" set SPRING_PROFILES_ACTIVE=prod

REM Check Java
"%JAVA_CMD%" -version >nul 2>&1
if errorlevel 1 (
    echo ERROR: Java not found. Please install Java 22 or set JAVA_HOME.
    exit /b 1
)

REM Show Java version
for /f "tokens=3" %%v in ('"%JAVA_CMD%" -version 2^>^&1 ^| findstr /i "version"') do (
    echo Using Java: %JAVA_CMD% ^(version %%v^)
    goto :java_ok
)
:java_ok

if "%1"=="start" goto start
if "%1"=="stop" goto stop
if "%1"=="status" goto status
goto usage

:start
echo Starting %APP_NAME%...

REM 'reports' is squish.report.directory (default 'reports', relative to the
REM working directory). Without it the PDF report write fails at the end of a run.
if not exist "%APP_HOME%\logs" mkdir "%APP_HOME%\logs"
if not exist "%APP_HOME%\reports" mkdir "%APP_HOME%\reports"

REM NOTE: the first quoted argument to `start` is the window TITLE, not the
REM command. Without the explicit "Squish" title below, cmd took "%JAVA_CMD%" as
REM the title and tried to execute %JAVA_OPTS% as the program - so `start` never
REM actually launched Java.
start "Squish" /b "%JAVA_CMD%" %JAVA_OPTS% -jar %JAR_FILE% --spring.profiles.active=%SPRING_PROFILES_ACTIVE% > %LOG_FILE% 2>&1
echo %APP_NAME% started
echo Dashboard: http://localhost:8080/
echo Log file: %APP_HOME%\%LOG_FILE%
echo.
echo NOTE: nothing supervises this process. Squish exits with code 1 on a fatal
echo       failure and will stay down until restarted. For unattended use, run it
echo       as a Windows Service via NSSM with 'AppExit Default Restart'.
goto end

:stop
echo Stopping %APP_NAME%...
for /f "tokens=1" %%p in ('jps -l ^| findstr squish.jar') do (
    taskkill /PID %%p /F >nul 2>&1
)
echo %APP_NAME% stopped
goto end

:status
for /f "tokens=1" %%p in ('jps -l ^| findstr squish.jar') do (
    echo %APP_NAME% is running ^(PID: %%p^)
    echo Dashboard: http://localhost:8080/
    goto end
)
echo %APP_NAME% is not running
goto end

:usage
echo Usage: %0 {start^|stop^|status}
exit /b 1

:end
endlocal
