#!/bin/bash
# ARCA Drive v2 demo on the Pleos Connect emulator + Virtual Console (run on studio).
# Real inputs only: gear / battery / range through the Virtual Console HTTP API (-> VHAL -> Vehicle SDK),
# the driver's voice as recorded PCM pushed into the Pleos SpeechToText SDK (ON_DEVICE, sendAudio),
# taps through `adb shell input tap`. Moments: meeting summary delegation, in-car reel encode,
# proactive battery -> charging-stop waypoint, arrival geofence, parked review.
source ~/work/hyundai-pleos/tools/env.sh
VC=http://127.0.0.1:8088
adb forward tcp:8088 tcp:8088 >/dev/null
CID=drive-demo-$$
( while true; do curl -s -X POST "$VC/browser/session?client=$CID" >/dev/null; sleep 1; done ) & HB=$!
trap "kill $HB 2>/dev/null" EXIT
b(){ adb shell am broadcast -a "app.heyarca.drive.$1" "${@:2}" >/dev/null; }
gear(){ curl -s -X PUT "$VC/vehicle/gear?gear=$1" >/dev/null; }
speed(){ curl -s -X PUT "$VC/vehicle/controls?speed=$1&accelerator=${2:-0}&brake=${3:-0}" >/dev/null; }
soc(){ curl -s -X PUT "$VC/vehicle/ev-battery?field=soc&value=$1" >/dev/null; }
range(){ curl -s -X PUT "$VC/vehicle/odometer?remaining_range_m=$1" >/dev/null; }
OUT=${OUT:-/tmp/drive_demo}; mkdir -p $OUT
T0=$(date +%s); mark(){ echo "MARK $(( $(date +%s) - T0 )) $1" >> $OUT/marks.txt; }
shot(){ mark $1; adb shell screencap -p /data/local/tmp/s.png && adb pull /data/local/tmp/s.png "$OUT/$1.png" >/dev/null; echo "shot $1"; }
waitlog(){ for i in $(seq 1 ${2:-60}); do adb logcat -d -s ARCA | grep -qE "$1" && return 0; sleep 1; done; echo "timeout waiting for $1"; return 1; }
# app-area tap: TaskView origin (853,90) + app coords
tap(){ adb shell input tap $((853 + $1)) $((90 + $2)); }
sleep 2
adb emu geo fix 127.1046 37.4405 >/dev/null; sleep 2     # departure point (판교)
speed 0 0 30; gear Park; soc 64; range 290000
# the receiver lives in the app process: make sure it is up before any broadcast (launch is a no-op if already in front)
adb shell am start -n app.heyarca.drive/.MainActivity >/dev/null 2>&1; sleep 6
for i in $(seq 1 10); do [ -n "$(adb shell pidof app.heyarca.drive)" ] && break; sleep 2; done
for i in 1 2 3 4 5; do b DEMO_RESET; sleep 2; adb logcat -d -s ARCA | grep -q "RESET demo data" && break; done
b DEMO_DEST --es name 양재_미팅룸 --ed lat 37.4688 --ed lon 127.0390 --es addr 서울_서초구_양재동
sleep 6
# NaviHelper.requestRoute brings the navigation app to the front; bring ARCA Drive back once (singleTask, no relaunch)
adb shell am start -n app.heyarca.drive/.MainActivity >/dev/null 2>&1; sleep 5
echo "app pid: $(adb shell pidof app.heyarca.drive)"
if [ -n "$REC" ]; then adb shell rm -f /data/local/tmp/demo.mp4; ( adb shell screenrecord --bit-rate 12000000 --time-limit 180 /data/local/tmp/demo.mp4 ) & RECPID=$!; sleep 1; fi
mark rec_start
sleep 3; shot 01_parked

# 1) P -> D : the screen closes to the orb, the assistant takes the arrival promise on its own
gear Drive; speed 40 25 0; sleep 9; shot 02_depart

# 2) "오늘 오전 회의, 요약해서 팀에 보내놔." -> THINKING orb -> real summary file + team draft
b DEMO_PCM --es name meeting
waitlog "HEARD:.*회의" 20; sleep 1; shot 03_meeting_thinking
waitlog "MEETING summary" 20; sleep 3; shot 04_meeting_done
sleep 7

# 3) "오늘 찍은 사진들로 릴스 하나 만들어줘." -> background H.264 encode while driving
speed 62 30 0
b DEMO_PCM --es name reel
waitlog "HEARD:.*(릴스|사진)" 20; sleep 4; shot 05_reel_working
sleep 6

# 4) proactive: the battery drops (Vehicle SDK SoC + range) -> charging stop offer -> "응" -> NaviHelper waypoint
range 78000; sleep 2; soc 21
waitlog "BATTERY soc=" 30; sleep 4; shot 06_battery_offer
sleep 6
b DEMO_PCM --es name yes_charge
waitlog "WAYPOINT add" 25; sleep 3
# the navigation app takes the front when guidance re-routes via the charger; bring ARCA Drive back (singleTask, same task)
adb shell am start -n app.heyarca.drive/.MainActivity >/dev/null 2>&1; sleep 4; shot 07_charge_stop
waitlog "REEL done" 120; sleep 6; shot 08_reel_ready

# 5) 300 m geofence -> arrival notice to the person waiting
speed 28 0 20; adb emu geo fix 127.0406 37.4680 >/dev/null; sleep 6
adb logcat -d -s ARCA | grep -q "GEOFENCE ENTER" || b DEMO_NEAR; sleep 6; shot 09_near

# 6) park -> the delegated work is on screen; play the reel; approve the team draft
speed 0 0 60; sleep 2; gear Park; sleep 4
adb shell am start -n app.heyarca.drive/.MainActivity >/dev/null 2>&1; sleep 5; shot 10_parked_review
adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1; adb pull /data/local/tmp/ui.xml $OUT/ui.xml >/dev/null 2>&1
xy(){ python3 - "$OUT/ui.xml" "$1" <<'PY'
import re,sys
x=open(sys.argv[1],encoding='utf-8').read()
for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',x):
    if m.group(1)==sys.argv[2]:
        a,b_,c,d=map(int,m.groups()[1:]); print((a+c)//2,(b_+d)//2); break
PY
}
P=$(xy 재생); echo "play at $P"
[ -z "$P" ] && P="2396 645"     # measured centre of the 재생 pill (app 1543,555)
adb shell input tap $P
waitlog "REEL playing" 6 || { echo "tap missed -> hook"; b DEMO_TASK --es name play; }
waitlog "REEL playing" 15; sleep 9; shot 11_reel_playing
adb shell input tap 1100 1250; sleep 3     # tap the scrim (outside the player) to close
adb logcat -d -s ARCA | grep -q "REEL playing" && b DEMO_TASK --es name close; sleep 1
C=$(xy "확인 후 보내기"); echo "confirm at $C"
[ -z "$C" ] && C="2348 449"     # measured centre of 확인 후 보내기 (app 1495,359)
adb shell input tap $C
waitlog "CONFIRM meeting" 6 || { echo "tap missed -> hook"; b DEMO_TASK --es name confirm; }
sleep 4; shot 12_draft_approved
sleep 3
if [ -n "$REC" ]; then adb shell pkill -INT screenrecord; wait $RECPID 2>/dev/null; sleep 2; adb pull /data/local/tmp/demo.mp4 $OUT/demo_raw.mp4 >/dev/null && echo "rec ok"; fi
# evidence: the files the app produced on the device
# (published by the app to shared storage of the driver profile; user 10 on Pleos Connect)
U=$(adb shell am get-current-user | tr -d '\r'); mkdir -p $OUT/device_files
adb pull "/storage/emulated/$U/Movies/ARCA Drive/." $OUT/device_files/ >/dev/null 2>&1
adb pull "/storage/emulated/$U/Documents/ARCA Drive/." $OUT/device_files/ >/dev/null 2>&1
ls -la $OUT/device_files
echo DEMO_DONE
