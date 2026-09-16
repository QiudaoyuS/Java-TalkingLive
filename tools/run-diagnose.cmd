@echo off
setlocal
rem ============================================================
rem  TalkingLive with TEXT diagnostic logging.
rem
rem  Use this when the recognised text looks wrong, so the log shows
rem  the actual text at each pipeline step:
rem     preview (Vosk streaming) -> refined (offline rerun) -> injected
rem
rem  NOTE: this logs transcript content, which the normal build never does.
rem  Only use it while debugging, and delete the log afterwards.
rem ============================================================
cd /d "%~dp0.."
set "JAVA_EXE=D:\Code\Java\jdk-21.0.12.1\bin\java.exe"
echo Text diagnostic logging ON - the log will contain what you said.
"%JAVA_EXE%" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dtalkinglive.log.text=true -jar "target\talkinglive.jar" %*
endlocal