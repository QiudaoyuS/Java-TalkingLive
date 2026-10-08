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

rem UTF-8 console code page. The JVM writes UTF-8; without this the console
rem reinterprets those bytes using the OEM code page and Chinese shows up as
rem mojibake. A normal start passes javaw (a GUI-subsystem binary), so the app
rem itself never gets a console; the console-mode subcommands (--doctor etc.)
rem keep this window because their whole result is printed into it.
chcp 65001 >nul 2>&1

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

rem ---- 3. launch ----
rem
rem Use javaw.exe (GUI subsystem) instead of java.exe. java.exe is a console
rem program, so double-clicking run.cmd leaves a black console window on the
rem desktop -- which contradicts the product's shape ("one floating ball,
rem nothing else"). The user explicitly asked to hide it.
rem
rem Cost: javaw throws stdout/stderr away, so the console shows nothing.
rem Therefore:
rem   1) Normal start uses javaw; failures are reported by the app's own
rem      dialog (see the catch in App.main). After hiding the console that
rem      dialog is the ONLY failure path -- it must not be removed.
rem   2) To see console output use tools\run-console.cmd (it uses java.exe).
rem   3) Subcommands that print their result to stdout (--doctor, --self-check,
rem      --mic-test) also use java.exe, see the check below.
set "JAVAW_EXE=%JAVA_EXE:java.exe=javaw.exe%"
if not exist "%JAVAW_EXE%" set "JAVAW_EXE=%JAVA_EXE%"

rem Subcommands whose whole point is console output; javaw would show nothing.
set "NEEDS_CONSOLE="
for %%a in (%*) do (
  if /i "%%a"=="--doctor"      set "NEEDS_CONSOLE=1"
  if /i "%%a"=="--self-check"  set "NEEDS_CONSOLE=1"
  if /i "%%a"=="--mic-test"    set "NEEDS_CONSOLE=1"
  if /i "%%a"=="--console"     set "NEEDS_CONSOLE=1"
  if /i "%%a"=="--help"        set "NEEDS_CONSOLE=1"
  if /i "%%a"=="-h"            set "NEEDS_CONSOLE=1"
)

if defined NEEDS_CONSOLE (
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
  exit /b %RC%
)

rem ---- normal start: no console window, no waiting ----
rem
rem Two details, both learned the hard way:
rem
rem 1) NO /wait. The app is a long-running tray application. With /wait the
rem    script blocks until the app exits, which means the cmd.exe process --
rem    and therefore its console window -- stays alive for the whole session.
rem    A hidden launcher would hide it, but the console window is still there
rem    for anyone running this .cmd directly (measured: four stray cmd.exe
rem    processes still holding run.cmd).
rem
rem 2) NO /b either. /b means "start inside the SAME console", which is the
rem    opposite of what we want. Without /b, javaw is a GUI process and gets
rem    no console of its own.
rem
rem Consequence: the exit code is no longer available here, so startup failures
rem cannot be reported by this script. They are reported by the app's own
rem dialog instead (see the catch in App.main) -- after hiding the console that
rem dialog is the only failure path, which is why it must not be removed.
rem
rem To keep a console for troubleshooting use tools\run-console.cmd.
rem Heap bounds: this app's Java heap usage is tiny (measured ~3 MB -- the Vosk models
rem live in NATIVE memory, not the Java heap). Without bounds, JVM ergonomics on a
rem 16 GB machine reserves a quarter of RAM as max heap, which inflates the process's
rem COMMIT charge (task manager: "Commit size" / "virtual memory"). Measured:
rem 4,686 MB -> 4,457 MB. Small, but free -- and it caps worst-case heap growth.
rem The real memory cost is the model itself (see docs/DECISIONS.md D1).
start "" "%JAVAW_EXE%" -Xmx512m -Xms32m -Dfile.encoding=UTF-8 -jar "target\talkinglive.jar" %*
endlocal