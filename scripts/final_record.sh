#!/bin/bash
# Final demo capture (run on studio, detached). Install BEFORE the scenario, then launch exactly once:
# reinstalling/relaunching while the Pleos homescreen TaskView holds the app leaves a blank surface.
set -x
source ~/work/hyundai-pleos/tools/env.sh
cd ~/work/hyundai-pleos/mate
OUT=${OUT:-/tmp/drive_demo}
APK=${APK:-$HOME/work/hyundai-pleos/mate/app/build/outputs/apk/release/app-release.apk} SETTLE=50 bash scripts/fresh_emulator.sh
sleep 6
adb logcat -c
rm -rf $OUT
REC=${REC-1} OUT=$OUT bash scripts/demo_scenario.sh
adb logcat -d -v epoch -s MATE AndroidRuntime > $OUT/mate_log_epoch.txt
echo FINAL_DONE
