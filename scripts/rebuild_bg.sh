#!/bin/bash
# build -> fresh emulator -> install -> launch (detached; log to /tmp/rebuild.log)
source ~/work/hyundai-pleos/tools/env.sh
export PATH=~/work/hyundai-pleos/tools/gradle-8.9/bin:$PATH
cd ~/work/hyundai-pleos/arca-drive
./gradlew -q assembleRelease 2>&1 | grep -E "^e:|FAILED" ; echo "BUILD $(ls -la app/build/outputs/apk/release/app-release.apk | awk '{print $6,$7,$8}')"
adb shell getprop sys.boot_completed >/dev/null 2>&1 && adb install -r -g app/build/outputs/apk/release/app-release.apk | tail -1
echo DONE
