@echo off
rem ----------------------------------------------------------------------
rem  Brodgar.io client launcher.
rem
rem  Double-click run.bat: it starts launcher.jar on the Java runtime in
rem  runtime\ beside it, so nothing has to be installed. The launcher keeps
rem  itself and client\ at the newest release of the chosen channel and
rem  starts the client from there. Any extra arguments are passed to the
rem  launcher (--check prints what it would do, without a window).
rem
rem  A launcher update that brought a new runtime leaves it in runtime.new\,
rem  since the runtime cannot be replaced while the launcher or the client
rem  runs on it: it is swapped in here, when nothing does. The first ren is
rem  refused while something still runs on runtime\, and the swap then waits
rem  for a later start.
rem ----------------------------------------------------------------------
cd /d "%~dp0"
if exist "runtime.new\bin\javaw.exe" (
  if exist "runtime.old" rmdir /s /q "runtime.old"
  ren runtime runtime.old 2>nul && ren runtime.new runtime && rmdir /s /q "runtime.old"
  if not exist "runtime\bin\javaw.exe" if exist "runtime.old\bin\javaw.exe" ren runtime.old runtime
)
if not exist "runtime\bin\javaw.exe" (
  echo The runtime folder is missing beside run.bat: unzip the launcher whole.
  pause
  exit /b 1
)
start "" "runtime\bin\javaw.exe" -jar launcher.jar %*
