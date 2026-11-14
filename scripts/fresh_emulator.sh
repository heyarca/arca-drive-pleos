#!/bin/bash
# Cold-boot the Pleos emulator and launch ARCA Drive exactly once (the Pleos homescreen
# crashes if the same task is re-added, so never force-stop/relaunch during a demo).
source ~/work/hyundai-pleos/tools/env.sh
adb emu kill >/dev/null 2>&1; sleep 4; pkill -f qemu-system 2>/dev/null; sleep 2
cd ~/work/hyundai-pleos
screen -dmS pleosemu bash -c "source ~/work/hyundai-pleos/tools/env.sh; emulator -avd pleos -no-window -no-snapshot -no-boot-anim -gpu swiftshader_indirect -no-audio > ~/work/hyundai-pleos/emu.log 2>&1"
adb wait-for-device
for i in $(seq 1 80); do [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break; sleep 3; done
sleep ${SETTLE:-40}
[ -n "$APK" ] && adb install -r -g "$APK" | tail -1
adb shell am start -n app.heyarca.drive/.MainActivity >/dev/null
sleep 10
adb forward tcp:8088 tcp:8088 >/dev/null
echo ready
