@echo off
setlocal

rem ============================================================
rem  TalkingLive launcher (double-click to run)
rem
rem  Usage:
rem    run.cmd              normal start (floating ball only)
rem    run.cmd --mic-test   microphone check, 15s with a live volume bar
rem    run.cmd --settings   start and open the settings window
rem    run.cmd --doctor     environment self-check, then exit
rem
rem  NOTE 1: all messages are ASCII on purpose. A .bat file containing
rem  non-ASCII text is unreliable -- cmd.exe parses the file before any
rem  code page switch takes effect, so UTF-8 or GBK bytes corrupt command
rem  parsing (verified: they turned the file into garbage). Chinese output
rem  comes from the program itself, where the encoding is under control.
rem
rem  NOTE 2: this script deliberately does NOT try to parse "java -version".
rem  Two earlier attempts were abandoned because cmd.exe escaping around
rem  "| findstr ... >nul" inside an "if (...)" block is fragile: it silently
rem  mis-parsed and launched JDK 8, which died with
rem  UnsupportedClassVersionError (class file version 65.0 vs 52.0).
rem  Instead we run the jar with whatever java we found and let the JVM
rem  report the problem, which it does clearly and in Chinese.
rem
rem  NOTE 3: JAVA_HOME on this machine pointed at JDK 8 while PATH had JDK 21.
rem  That mismatch is why the explicit fallback list below exists.
rem ============================================================

cd /d "%~dp0"

rem ---- 1. collect JDK 21 candidates, first match wins ----
rem
rem IMPORTANT: known-good JDK 21 locations are tried BEFORE JAVA_HOME.
rem Reason: on this machine JAVA_HOME points at jdk1.8.0_111 while PATH has
rem JDK 21 (a documented mismatch, DESIGN.md ?10.1). Trusting JAVA_HOME first
rem launched JDK 8 and died with UnsupportedClassVersionError. JAVA_HOME is
rem still honored, but only as a last resort -- and only after the JVM itself
rem confirms it can run a class file version 65 build.
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
  echo [WARN] No known JDK 21 install found; trying JAVA_HOME = %JAVA_HOME%
  set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
)

if not defined JAVA_EXE (
  echo.
  echo [ERROR] JDK 21 not found.
  echo         Set JAVA_HOME to a JDK 21 install. The build targets Java 21;
  echo         JDK 8 cannot run it.
  echo.
  pause
  exit /b 1
)

echo Using JDK: %JAVA_EXE%

rem ---- 2. build if needed ----
if not exist "target\talkinglive.jar" (
  echo.
  echo Not built yet. Building now ^(downloads dependencies, 1-2 min^)...
  call mvnw.cmd -q -DskipTests package
  if errorlevel 1 (
    echo.
    echo [ERROR] Build failed. Run this to see details:  mvnw.cmd package
    pause
    exit /b 1
  )
)

if not exist "target\lib" (
  echo.
  echo Runtime dependencies missing. Repackaging...
  call mvnw.cmd -q -DskipTests package
  if errorlevel 1 (
    echo [ERROR] Repackage failed.
    pause
    exit /b 1
  )
)

rem ---- 3. launch. If the JVM is not 21 it prints a clear version error. ----
echo.
"%JAVA_EXE%" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -jar "target\talkinglive.jar" %*
set "RC=%ERRORLEVEL%"

if not "%RC%"=="0" (
  echo.
  echo [Exited with code %RC%]
  echo If this mentions "class file version", JAVA_HOME is not JDK 21.
  echo Current JAVA_HOME = %JAVA_HOME%
  pause
)
endlocal