@echo off
setlocal enabledelayedexpansion

REM ============================================================
REM  JMNeXt Token Dumper
REM
REM  Usage:
REM    dump_token.bat              Auto-detect JMNeXt java.exe
REM    dump_token.bat <PID>        Use a specific process ID
REM    dump_token.bat /keep        Keep the intermediate heap dump
REM
REM  Output: %TEMP%\jmnext_dump\token.txt
REM ============================================================

set "OUT_DIR=%TEMP%\jmnext_dump"
set "HPROF=%OUT_DIR%\jmnext_heap.hprof"
set "TOKEN_FILE=%OUT_DIR%\token.txt"
set "KEEP="
set "ARG_PID="

:parse_args
if "%~1"=="" goto args_done
if /i "%~1"=="/keep" (
    set "KEEP=1"
    shift
    goto parse_args
)
set "ARG_PID=%~1"
shift
goto parse_args
:args_done

if not exist "%OUT_DIR%" mkdir "%OUT_DIR%" >nul 2>&1

REM --- 1. Locate PID ---
set "TARGET_PID=%ARG_PID%"
if not "%TARGET_PID%"=="" goto have_pid

echo [1/4] Searching for JMNeXt process...

for /f "usebackq skip=1 tokens=1" %%P in (`wmic process where "name='java.exe' and commandline like '%%jmnext%%'" get processid 2^>nul`) do (
    if not defined TARGET_PID set "TARGET_PID=%%P"
)

if not "%TARGET_PID%"=="" goto have_pid

echo       Auto-detection failed. Listing all java.exe processes:
echo.
wmic process where "name='java.exe'" get ProcessId,CommandLine /format:list 2>nul | findstr "."
echo.
echo       Re-run with explicit PID: %~nx0 ^<PID^>
exit /b 1

:have_pid
echo       PID = %TARGET_PID%

REM --- 2. Check tools ---
where jcmd >nul 2>&1
if errorlevel 1 (
    where jmap >nul 2>&1
    if errorlevel 1 (
        echo       ERROR: Neither jcmd nor jmap is in PATH.
        echo       Add the JDK bin directory to PATH and retry.
        exit /b 1
    )
)

REM --- 3. Dump heap ---
echo [2/4] Dumping heap to %HPROF% ...
if exist "%HPROF%" del "%HPROF%" >nul 2>&1

jcmd %TARGET_PID% GC.heap_dump "%HPROF%" >nul 2>&1
if not exist "%HPROF%" (
    echo       jcmd failed, falling back to jmap ...
    jmap -dump:live,format=b,file="%HPROF%" %TARGET_PID%
    if not exist "%HPROF%" (
        echo       ERROR: dump failed. Try running this script as Administrator.
        exit /b 1
    )
)

for %%F in ("%HPROF%") do echo       Size: %%~zF bytes

REM --- 4. Extract JWT ---
echo [3/4] Extracting JWT from heap dump ...

powershell -NoProfile -Command "$b=[IO.File]::ReadAllBytes('%HPROF%'); $t=[Text.Encoding]::ASCII.GetString($b); $m=[regex]::Matches($t,'eyJ[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+'); $m | ForEach-Object { $_.Value } | Sort-Object -Unique | Set-Content -Encoding ASCII '%TOKEN_FILE%'"

if not exist "%TOKEN_FILE%" (
    echo       ERROR: No JWT found in heap dump.
    exit /b 1
)

set "COUNT=0"
for /f %%C in ('type "%TOKEN_FILE%" ^| find /c /v ""') do set "COUNT=%%C"

REM --- 5. Done ---
echo [4/4] Done.
echo.
echo       Found %COUNT% unique token^(s^):
echo       ------------------------------------------
type "%TOKEN_FILE%"
echo       ------------------------------------------
echo       Saved to: %TOKEN_FILE%

if not defined KEEP (
    del "%HPROF%" >nul 2>&1
    echo       Removed heap dump ^(use /keep to keep it^).
)

endlocal