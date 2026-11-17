#!/system/bin/sh
# Run this INSIDE rish (so you are uid=2000 shell).
#
#   1) Download classes.dex from the repo's "Latest build" release
#      into your phone's Download folder.
#   2) In Termux:  sh rish        (prompt becomes  cuscoi:/ $ ,  id shows shell)
#   3) Then:       sh /sdcard/Download/run.sh
#
# It copies the dex somewhere app_process will accept it, strips the write
# bit (Android 14+ refuses a writable dex), and launches the injector.

set -e
SRC=/sdcard/Download/classes.dex
DST=/data/local/tmp/micinject.dex

cp "$SRC" "$DST"
chmod 444 "$DST"
echo "launching MicInject (watch for [MicInject] lines)..."
CLASSPATH="$DST" app_process /system/bin MicInject
