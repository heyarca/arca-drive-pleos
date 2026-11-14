#!/bin/bash
# End-to-end demo scenario on the Pleos Connect emulator + Virtual Console (run on studio).
# Drives the car through the real Virtual Console HTTP API (gear/speed) and feeds the
# driver's voice into the Pleos SpeechToText SDK (ON_DEVICE, sendAudio) as recorded PCM.
source ~/work/hyundai-pleos/tools/env.sh
VC=http://127.0.0.1:8088
adb forward tcp:8088 tcp:8088 >/dev/null
CID=arca-demo-$$
( while true; do curl -s -X POST "$VC/browser/session?client=$CID" >/dev/null; sleep 1; done ) & HB=$!
trap "kill $HB 2>/dev/null" EXIT
b(){ adb shell am broadcast -a "app.heyarca.drive.$1" "${@:2}" >/dev/null; }
gear(){ curl -s -X PUT "$VC/vehicle/gear?gear=$1" >/dev/null; }
speed(){ curl -s -X PUT "$VC/vehicle/controls?speed=$1&accelerator=${2:-0}&brake=${3:-0}" >/dev/null; }
OUT=${OUT:-/tmp/arca_demo}; mkdir -p $OUT
front(){ adb shell am start -n app.heyarca.drive/.MainActivity >/dev/null 2>&1; }
shot(){ mark $1; front; sleep 1; adb shell screencap -p /data/local/tmp/s.png && adb pull /data/local/tmp/s.png "$OUT/$1.png" >/dev/null; echo "shot $1"; }
sleep 2
if [ -n "$REC" ]; then adb shell rm -f /data/local/tmp/demo.mp4; ( adb shell screenrecord --bit-rate 12000000 --time-limit 170 /data/local/tmp/demo.mp4 ) & RECPID=$!; sleep 1; fi
T0=$(date +%s); mark(){ echo "MARK $(( $(date +%s) - T0 )) $1" >> $OUT/marks.txt; }

# 0) parked, fresh data, destination set via NaviHelper
adb emu geo fix 127.1046 37.4405 >/dev/null; sleep 2   # car at the departure point (판교)
speed 0 0 30; gear Park; b DEMO_RESET; sleep 1
b DEMO_DEST --es name 양재_미팅룸 --ed lat 37.4688 --ed lon 127.0390 --es addr 서울_서초구_양재동
sleep 5; front; sleep 2; shot 01_parked

# 1) P -> D : ARCA briefs by voice, screen goes voice-only
gear Drive; speed 35 25 0; sleep 9; shot 02_depart_brief

# 2) driver: "응, 보내줘" -> arrival message scheduled
b DEMO_PCM --es name yes_send; sleep 7; shot 03_arrival_scheduled

# 3) driver dictates a reply to a promise while moving
speed 62 30 0
b DEMO_PCM --es name quote; sleep 11; shot 04_reply_draft
b DEMO_PCM --es name yes; sleep 7; shot 05_reply_sent

# 4) 300 m geofence -> arrival notice goes out on its own
# real FusedLocation ProximityAlert: move the car inside the 300 m radius; fall back to the same callback path if ENTER is slow
speed 28 0 20; adb emu geo fix 127.0406 37.4680 >/dev/null; sleep 6
adb logcat -d -s ARCA | grep -q "GEOFENCE ENTER" || b DEMO_NEAR; sleep 4; shot 06_near_sent

# 5) new promise heard in the car
b DEMO_PCM --es name remember; sleep 11; shot 07_remember

# 6) park -> screen opens: 1-tap cards + closed-with-evidence list
speed 0 0 60; sleep 2; gear Park; sleep 8; shot 08_parked_cards
sleep 3
if [ -n "$REC" ]; then adb shell pkill -INT screenrecord; wait $RECPID 2>/dev/null; sleep 2; adb pull /data/local/tmp/demo.mp4 $OUT/demo_raw.mp4 >/dev/null && echo "rec ok"; fi
