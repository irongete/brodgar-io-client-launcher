@echo off
rem ----------------------------------------------------------------------
rem  Brodgar.io client launcher.
rem
rem  Double-click run.bat: it starts launcher.jar on the Java runtime in
rem  runtime\ beside it, so nothing has to be installed. The launcher keeps
rem  client\ at the newest release of the chosen channel and starts the
rem  client from there. Any extra arguments are passed to the launcher
rem  (--check prints what it would do, without a window).
rem ----------------------------------------------------------------------
cd /d "%~dp0"
if not exist "runtime\bin\javaw.exe" (
  echo The runtime folder is missing beside run.bat: unzip the launcher whole.
  pause
  exit /b 1
)
start "" "runtime\bin\javaw.exe" -jar launcher.jar %*
