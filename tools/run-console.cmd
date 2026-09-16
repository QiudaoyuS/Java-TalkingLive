@echo off
setlocal

rem ============================================================
rem  TalkingLive console launcher (for troubleshooting)
rem
rem  Why this exists: the normal launcher (run.cmd) starts the app
rem  with javaw.exe so that double-clicking it does NOT leave a
rem  black console window on the desktop -- the product's shape is
rem  "one floating ball, nothing else".
rem
rem  But javaw throws stdout/stderr away, which is exactly what you
rem  need when something goes wrong. So this script runs the very
rem  same application with java.exe, keeping the console.
rem
rem  All messages are ASCII on purpose: cmd.exe parses a .bat file
rem  before any code page switch, so non-ASCII bytes corrupt command
rem  parsing (verified the hard way).
rem
rem  Usage:
rem    tools\run-console.cmd              normal start, console kept
rem    tools\run-console.cmd --doctor     environment self-check
rem    tools\run-console.cmd --mic-test   live microphone check
rem ============================================================

cd /d "%~dp0.."

set "JAVA_EXE="
for %%d in (
  "D:\Code\Java\jdk-21.0.12.1"
  "D:\Code\Java\jdk-21"
  "C:\Program Files\Java\jdk-21"
  "C:\Program Files\Eclipse Adoptium\jdk-21"
  "C:\Program Files\Microsoft\jdk-21"
) do (
  if not defined JAVA_EXE if exist "%%~d\bin\java.exe" set "JAVA_EXE=%%~d\bin\java.exe"
)

if not defined JAVA_EXE if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" (
  set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
)

if not defined JAVA_EXE (
  echo [ERROR] JDK 21 not found. Set JAVA_HOME to a JDK 21 install.
  pause
  exit /b 1
)

if not exist "target\talkinglive.jar" (
  echo [ERROR] Not built yet. Run: mvnw.cmd -DskipTests package
  pause
  exit /b 1
)

echo Using JDK: %JAVA_EXE%
echo.
rem chcp 65001 = UTF-8 console code page. Without it the JVM's UTF-8 output is
rem reinterpreted as the OEM code page and Chinese shows up as mojibake
rem (that is exactly the "log has mojibake" report -- the bytes were fine,
rem the reader guessed the wrong encoding).
chcp 65001 >nul 2>&1
"%JAVA_EXE%" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "target\talkinglive.jar" %*
set "RC=%ERRORLEVEL%"
echo.
echo [Exited with code %RC%]
echo.
echo To read the log file with the correct encoding:
echo   java -cp "target\talkinglive.jar;target\lib\*" com.talkinglive.system.LogViewer 100
pause
endlocal
