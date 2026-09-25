@echo off
setlocal enabledelayedexpansion

rem ============================================================
rem  TalkingLive speech model setup (double-click to run)
rem
rem  Why this exists: the Vosk models are 42MB / 1.3GB binaries and are
rem  deliberately NOT kept in the repository, so a fresh clone has no
rem  model -- the app runs and says "model unavailable" but cannot
rem  transcribe. This script fetches one and unpacks it to the exact
rem  place the app looks for it.
rem
rem  Target: %LOCALAPPDATA%\TalkingLive\models\
rem    small model  -> vosk-model-small-cn-0.22\  (REQUIRED: wake word /
rem                    end word detection only supports this one)
rem    large model  -> model-cn\                  (optional: much better
rem                    accuracy; the app auto-detects either name)
rem  No configuration is needed after unpacking -- the app finds it.
rem
rem  Usage from a console:
rem    tools\get-model.cmd                 interactive
rem    tools\get-model.cmd 1 --no-pause    scriptable (1=small, 2=large)
rem
rem  NOTE 1: all messages are ASCII on purpose -- same reason as run.cmd:
rem  cmd.exe parses the whole file before any code page switch takes
rem  effect, so non-ASCII bytes corrupt command parsing (verified).
rem  NOTE 2: downloads go through curl.exe (built into Windows 10 1803+),
rem  mirror first, official site as fallback. Measured in China: official
rem  ~40 KB/s, hf-mirror ~3.5 MB/s (small) / ~13 MB/s (large).
rem  NOTE 3: the zip is written to %TEMP% and deleted after a successful
rem  unpack -- otherwise the large model would leave 1.3GB behind.
rem ============================================================

set "MODELS=%LOCALAPPDATA%\TalkingLive\models"
set "ZIP=%TEMP%\talkinglive-model.zip"

set "MIRROR_SMALL=https://hf-mirror.com/localstack/vosk-models/resolve/main/vosk-model-small-cn-0.22.zip"
set "OFFICIAL_SMALL=https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"
set "MIRROR_LARGE=https://hf-mirror.com/LiangJingyi/vosk-model-cn-0.22/resolve/main/model-cn.zip"
set "OFFICIAL_LARGE=https://alphacephei.com/vosk/models/vosk-model-cn-0.22.zip"

set "NOPAUSE="
set "PICK="
for %%a in (%*) do (
    if /i "%%a"=="--no-pause" set "NOPAUSE=1"
    if "%%a"=="1" set "PICK=1"
    if "%%a"=="2" set "PICK=2"
    if /i "%%a"=="small" set "PICK=1"
    if /i "%%a"=="large" set "PICK=2"
)

if defined PICK goto :pick_%PICK%

echo.
echo   TalkingLive - speech model setup
echo   ---------------------------------------------------------
echo     [1] small model    42 MB   required for the wake word
echo     [2] large model   1.3 GB   optional, much more accurate
echo     [3] quit
echo.
choice /c 123 /n /m "  Choose 1, 2 or 3: "
if errorlevel 3 goto :bye
if errorlevel 2 goto :large

:pick_1
:small
set "NAME=small model"
if exist "%MODELS%\vosk-model-small-cn-0.22" goto :installed
set "URL1=%MIRROR_SMALL%"
set "URL2=%OFFICIAL_SMALL%"
goto :download

:pick_2
:large
set "NAME=large model"
if exist "%MODELS%\model-cn" goto :installed
if exist "%MODELS%\vosk-model-cn-0.22" goto :installed
set "URL1=%MIRROR_LARGE%"
set "URL2=%OFFICIAL_LARGE%"
goto :download

:installed
echo.
echo   Already installed under %MODELS% -- nothing to do.
echo   (Double-click run.cmd to start.)
goto :bye

:download
if not exist "%MODELS%" mkdir "%MODELS%" >nul 2>&1
echo.
echo   Downloading the %NAME% from hf-mirror ...
echo   %URL1%
echo.
curl.exe -L --fail --retry 3 --retry-delay 2 -o "%ZIP%" "%URL1%"
if errorlevel 1 (
    echo.
    echo   Mirror failed. Trying the official site ^(slow outside/inside China^) ...
    echo   %URL2%
    echo.
    curl.exe -L --fail --retry 3 --retry-delay 2 -o "%ZIP%" "%URL2%"
)
if errorlevel 1 goto :failed
if not exist "%ZIP%" goto :failed

echo.
echo   Unpacking into %MODELS% ... ^(this takes a moment^)
powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -LiteralPath '%ZIP%' -DestinationPath '%MODELS%' -Force"
if errorlevel 1 goto :failed
del "%ZIP%" >nul 2>&1

set "FOUND="
if exist "%MODELS%\vosk-model-small-cn-0.22" set "FOUND=vosk-model-small-cn-0.22"
if exist "%MODELS%\model-cn" set "FOUND=model-cn"
if exist "%MODELS%\vosk-model-cn-0.22" set "FOUND=vosk-model-cn-0.22"
echo.
if defined FOUND (
    echo   Done. Model unpacked to:
    echo     %MODELS%\!FOUND!
    echo.
    echo   Next step: double-click run.cmd in the project root.
    echo   First run tells you whether the microphone is picked up.
) else (
    echo   WARNING: unpacking finished but no known model folder was found
    echo   under %MODELS%. Check the zip contents manually.
)
goto :bye

:failed
echo.
echo   FAILED. Nothing was installed.
echo   You can download it by hand and unpack it into:
echo     %MODELS%
echo   Links are in README.md, section "3 steps to start".
del "%ZIP%" >nul 2>&1

:bye
echo.
if not defined NOPAUSE pause
endlocal
