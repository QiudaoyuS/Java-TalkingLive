@echo off
setlocal

rem ============================================================
rem  BlockSnapshot launcher -- run this WHILE the problem is happening.
rem
rem  When the screen "freezes" (cannot click the taskbar / close buttons),
rem  double-click this file. It captures the live state and writes a report.
rem
rem  It reports:
rem    1. large or always-on-top windows that could be covering the screen
rem    2. modifier keys stuck DOWN (Ctrl/Alt/Shift/Win) -- the classic cause of
rem       "nothing responds to clicks" with nothing visibly wrong
rem    3. which window owns the foreground
rem    4. which window is under the mouse cursor
rem
rem  Report: %LOCALAPPDATA%\TalkingLive\block-snapshot.txt
rem  The output is ASCII-only on the console (Windows console defaults to GBK,
rem  which garbles Chinese and hides the real failure).
rem ============================================================

cd /d "%~dp0.."

set "JAVA_EXE="
for %%d in (
  "D:\Code\Java\jdk-21.0.12.1"
  "D:\Code\Java\jdk-21"
  "C:\Program Files\Java\jdk-21"
  "C:\Program Files\Eclipse Adoptium\jdk-21"
) do (
  if not defined JAVA_EXE if exist "%%~d\bin\java.exe" set "JAVA_EXE=%%~d\bin\java.exe"
)
if not defined JAVA_EXE if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"

if not defined JAVA_EXE (
  echo [ERROR] JDK 21 not found. Set JAVA_HOME to a JDK 21 install.
  pause
  exit /b 1
)

echo Capturing screen state, please wait...
echo.
"%JAVA_EXE%" -Dfile.encoding=UTF-8 -cp "target\talkinglive.jar;target\lib\*" com.talkinglive.system.BlockSnapshot %*
echo.
echo Report saved to: %LOCALAPPDATA%\TalkingLive\block-snapshot.txt
pause
endlocal
