#!/bin/bash
# Final demo capture (run on studio, detached). Install BEFORE the cold boot, then launch exactly once:
# reinstalling/relaunching while the Pleos homescreen TaskView holds the app leaves a blank surface.
set -x
source ~/work/hyundai-pleos/tools/env.sh
cd ~/work/hyundai-pleos/arca-drive
APK=${APK:-$HOME/work/hyundai-pleos/arca-drive/app/build/outputs/apk/release/app-release.apk} SETTLE=50 bash scripts/fresh_emulator.sh
sleep 6
adb logcat -c
rm -rf /tmp/arca_demo
REC=1 OUT=/tmp/arca_demo bash scripts/demo_scenario.sh
adb logcat -d -v epoch -s ARCA > /tmp/arca_demo/arca_log_epoch.txt
echo FINAL_DONE
