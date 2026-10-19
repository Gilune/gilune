@echo off
rem Starts the desktop app from source: double-click, or run with a vault
rem folder as the first argument. Without one it opens %USERPROFILE%\Gilune.
rem
rem The kernel is rebuilt first (debug, without Whisper, so voice notes do
rem not transcribe here); cargo and gradle both skip what has not changed,
rem so a second start is quick. Closing this window closes the app.
setlocal
set "ROOT=%~dp0.."
set "VAULT=%~1"
if "%VAULT%"=="" set "VAULT=%USERPROFILE%\Gilune"
title Gilune (from source) - %VAULT%

pushd "%ROOT%\kernel" || goto :fail
echo Building the kernel...
cargo build -p gilune-ffi || goto :fail
cargo run -q -p gilune-ffi --bin uniffi-bindgen -- generate ^
  --library target\debug\gilune_ffi.dll --language kotlin ^
  --out-dir ..\app\kernelBindings\src\desktopMain\generated || goto :fail
popd

pushd "%ROOT%\app" || goto :fail
echo Starting Gilune on %VAULT%...
call .\gradlew.bat :composeApp:run "-Pgilune.vault=%VAULT%" || goto :fail
popd
exit /b 0

:fail
echo.
echo Gilune did not start - the messages above say why.
pause
exit /b 1
