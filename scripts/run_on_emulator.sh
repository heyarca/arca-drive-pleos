#!/bin/bash
# Rebuild + reinstall + launch Mate on the Pleos emulator (run on studio)
set -e
source ~/work/hyundai-pleos/tools/env.sh
export PATH=~/work/hyundai-pleos/tools/gradle-8.9/bin:$PATH
cd ~/work/hyundai-pleos/mate
./gradlew -q assembleRelease 2>&1 | grep -E "^e:|error:|FAIL" | head -30 || true
adb install -r -g app/build/outputs/apk/release/app-release.apk | tail -1
# (no force-stop: Pleos homescreen crashes on task re-add)
adb logcat -c
adb shell am start -n app.mate.gleo/.MainActivity >/dev/null
sleep ${WAIT:-7}
adb logcat -d | grep -E " MATE|FATAL|AndroidRuntime: (java|Caused|\s+at app)" | head -40
adb shell screencap -p /sdcard/a.png && adb pull /sdcard/a.png /tmp/mate_shot.png >/dev/null
