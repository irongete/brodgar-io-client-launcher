#!/bin/sh
# ----------------------------------------------------------------------
#  brodgar.io launcher, Linux and macOS: run.bat's twin.
#
#  It starts launcher.jar on the Java runtime in runtime/ beside it, so
#  nothing has to be installed. On Linux, run it from the folder the
#  launcher was unzipped to (in a file manager: right click, Run as a
#  Program); from then on the launcher is in the applications menu too. On
#  macOS Brodgar.io.app runs it, in the folder it keeps the launcher in.
#  Any extra arguments are passed to the launcher (--check prints what it
#  would do, without a window).
#
#  A launcher update that brought a new runtime leaves it in runtime.new/:
#  it is swapped in here, unless a client still runs on runtime/, in which
#  case the swap waits for a later start.
# ----------------------------------------------------------------------
cd "$(dirname "$0")" || exit 1
here=$(pwd)
if [ -x runtime.new/bin/java ] && ! pgrep -f "$here/runtime/bin/java" >/dev/null 2>&1; then
  rm -rf runtime.old
  if { [ ! -e runtime ] || mv runtime runtime.old; } && mv runtime.new runtime; then
    rm -rf runtime.old
  elif [ ! -e runtime ] && [ -d runtime.old ]; then
    mv runtime.old runtime
  fi
fi
if [ ! -x runtime/bin/java ]; then
  echo "The runtime folder is missing beside run.sh: unzip the launcher whole." >&2
  exit 1
fi
if [ "$(uname -s)" = Darwin ]; then
  # the Dock's name and icon for the window: the process is java, not the app
  exec runtime/bin/java -Xdock:name=Brodgar.io -Xdock:icon=icon.png -jar launcher.jar "$@"
fi
exec runtime/bin/java -jar launcher.jar "$@"
