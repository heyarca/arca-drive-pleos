#!/bin/bash
# 메이트 (Mate) for Gleo — demo on the Pleos Connect emulator + Virtual Console (run on the Mac that hosts the emulator).
# Real inputs only:
#   gear / speed through the Virtual Console HTTP API (-> VHAL -> Vehicle SDK gear, android.car PERF_VEHICLE_SPEED)
#   the driver's voice as PCM (macOS `say -v Yuna`) pushed into the Pleos SpeechToText SDK (ON_DEVICE, sendAudio)
#   Gleo actions as the same startActivity(action, ACTION_PARAM) intents Gleo sends (assets/schema.json)
#   the work itself on this Mac's job server (server/mate_server.py, `claude -p`), which stands in for the user's VM
# PART=A (default): proposal page -> voice edit -> code fix -> approval refused while moving -> approved while stopped
# PART=B          : the same kind of job followed on a phone browser and approved there (phone continuation)
source ~/work/hyundai-pleos/tools/env.sh
PKG=app.mate.gleo
VC=http://127.0.0.1:8088
SRV=${SRV:-http://127.0.0.1:8790}
PART=${PART:-A}
HERE=$(cd "$(dirname "$0")" && pwd)
adb forward tcp:8088 tcp:8088 >/dev/null
CID=mate-demo-$$
( while true; do curl -s -X POST "$VC/browser/session?client=$CID" >/dev/null; sleep 1; done ) & HB=$!
trap "kill $HB 2>/dev/null" EXIT
b(){ adb shell am broadcast -a "$PKG.$1" "${@:2}" >/dev/null; }
gear(){ curl -s -X PUT "$VC/vehicle/gear?gear=$1" >/dev/null; }
speed(){ curl -s -X PUT "$VC/vehicle/controls?speed=$1&accelerator=${2:-0}&brake=${3:-0}" >/dev/null; }
OUT=${OUT:-/tmp/mate_demo}; mkdir -p $OUT
MARKS=$OUT/marks${PART/A/}.txt; [ "$PART" = B ] && MARKS=$OUT/marks_handoff.txt; : > $MARKS
T0=$(date +%s); mark(){ echo "MARK $(( $(date +%s) - T0 )) $1" >> $MARKS; }
shot(){ mark $1; adb shell screencap -p /data/local/tmp/s.png && adb pull /data/local/tmp/s.png "$OUT/$1.png" >/dev/null; echo "shot $1"; }
logs(){ adb logcat -d -s MATE; }
waitlog(){ for i in $(seq 1 ${2:-60}); do logs | grep -qE "$1" && return 0; sleep 1; done; echo "timeout waiting for $1"; return 1; }
# the driver's line: recorded PCM into the STT SDK. If the recogniser misses it, the same text goes to the same handler (marked).
voice(){ b DEMO_PCM --es name $1; waitlog "HEARD:.*($2)" 12 && return 0; mark "stt_miss_$1"; echo "STT miss $1 -> text"; b DEMO_SAY --es text "$3"; }
jobid(){ logs | grep -E "JOB [0-9a-f]{12} $1 accepted" | tail -1 | sed -E 's/.*JOB ([0-9a-f]{12}).*/\1/'; }
tap(){ adb shell input tap $((853 + $1)) $((90 + $2)); }

sleep 2
speed 0 0 30; gear Park
curl -s -X PUT "$VC/vehicle/ev-battery?field=soc&value=64" >/dev/null; curl -s -X PUT "$VC/vehicle/odometer?remaining_range_m=290000" >/dev/null
adb shell am start -n $PKG/.MainActivity >/dev/null 2>&1; sleep 4
for i in $(seq 1 10); do [ -n "$(adb shell pidof $PKG)" ] && break; sleep 2; done
adb logcat -c
if [ "$PART" = A ]; then
  for i in 1 2 3 4 5; do b DEMO_RESET; sleep 2; logs | grep -q "RESET demo data" && break; done
fi
PUB=$(curl -s $SRV/health | python3 -c "import sys,json; print(json.load(sys.stdin)['public'])")
echo "app pid: $(adb shell pidof $PKG) · compute $SRV · phone base $PUB"
RECF=demo_raw.mp4; [ "$PART" = B ] && RECF=handoff_car_raw.mp4
if [ -n "$REC" ]; then adb shell rm -f /data/local/tmp/demo.mp4; ( adb shell screenrecord --bit-rate 12000000 --time-limit 180 /data/local/tmp/demo.mp4 ) & RECPID=$!; sleep 1; fi
mark rec_start

if [ "$PART" = A ]; then
  sleep 3; shot 01_parked

  # 1) drive off. From here the screen is one line.
  gear Drive; speed 40 25 0
  waitlog "MOVE -> MOVING" 10; sleep 5; shot 02_driving

  # 2) "방금 미팅 내용으로 A사 제안 페이지 만들어 줘." -> job on the driver's compute, one status line in the car
  voice proposal "제안|페이지" "방금 미팅 내용으로 A사 제안 페이지 만들어 줘"
  waitlog "JOB [0-9a-f]{12} proposal accepted" 15; J1=$(jobid proposal); mark "job_proposal $J1"
  waitlog "JOB $J1 WORKING 2/3" 30; sleep 1; shot 03_status_line_working
  # Gleo sends DELEGATE_TASK for the same work (retry): the callback carries the same job id, nothing new starts
  adb shell am start -n $PKG/.MainActivity -a DELEGATE_TASK --es ACTION_PARAM "'{\"task\":\"A사 제안 페이지 만들어 줘\",\"kind\":\"proposal\",\"requestId\":\"gleo-demo-1\"}'" >/dev/null 2>&1
  waitlog "GLEO callback.*DELEGATE_TASK" 10; G1=$(logs | grep "GLEO callback" | tail -1 | sed -E 's/.*"jobId":"([0-9a-f]+)".*/\1/')
  mark "gleo_delegate_retry job=$J1 callback_job=$G1"; echo "Gleo DELEGATE_TASK retry: job=$J1 callback=$G1"
  waitlog "JOB $J1 READY" 90; sleep 4; shot 04_status_line_ready

  # 4) one voice edit, still driving
  voice edit "일정|올려" "일정을 맨 위로 올려 줘"
  waitlog "JOB [0-9a-f]{12} edit accepted" 15; J2=$(jobid edit); mark "job_edit $J2"
  waitlog "JOB $J2 READY" 90; sleep 4; shot 05_edit_ready_driving

  # 5) red light: speed 0 in D. Nothing opens by itself; "결과 보여 줘" opens before -> after
  speed 0 0 40; waitlog "MOVE -> STOPPED" 10; sleep 3; shot 06_stopped_nothing_opens
  voice show "보여" "결과 보여 줘"
  waitlog "SHOW $J2" 10; sleep 5; shot 07_show_before_after
  adb shell input swipe 2100 1000 2100 560 500; sleep 2; shot 08_scrolled

  # 6) the car moves again -> the result closes by itself, scroll position kept
  speed 35 20 0; waitlog "RESULT auto-closed" 10; sleep 3; shot 09_moving_closed

  # 7) same agent, different work: fix a real bug, publish a preview
  voice bugfix "결제|버튼|버그" "결제 버튼 버그 고쳐서 미리보기 올려 줘"
  waitlog "JOB [0-9a-f]{12} bugfix accepted" 15; J3=$(jobid bugfix); mark "job_bugfix $J3"
  waitlog "JOB $J3 WORKING 2/3" 30; sleep 1; shot 10_bugfix_working
  waitlog "JOB $J3 READY" 90; sleep 5

  # 8) "이대로 배포해 줘" while moving -> refused by the driver's compute (car reports speed > 0), reason spoken
  voice deploy "배포|베포" "이대로 배포해 줘"
  waitlog "DEPLOY refused $J3" 10; sleep 4; shot 11_deploy_refused_moving
  sleep 1

  # 9) stop and park -> ask to see it -> approve by voice -> deployed
  speed 0 0 60; sleep 2; gear Park; waitlog "MOVE -> STOPPED.*" 10; sleep 4
  adb shell am start -n $PKG/.MainActivity -a SHOW_RESULT --es ACTION_PARAM "'{\"jobId\":\"$J3\"}'" >/dev/null 2>&1   # Gleo SHOW_RESULT
  waitlog "SHOW $J3" 10; sleep 5; shot 12_stopped_preview_waiting_approval
  voice deploy "배포|베포" "이대로 배포해 줘"
  waitlog "DEPLOY approved $J3" 12; sleep 4; shot 13_deployed
  b DEMO_TASK --es name close; sleep 3; shot 14_parked_phone_handoff

  # 10) reopen the edited page: it comes back at the scroll position it was closed at
  adb shell am start -n $PKG/.MainActivity -a SHOW_RESULT --es ACTION_PARAM "'{\"jobId\":\"$J2\"}'" >/dev/null 2>&1
  waitlog "RESULT scroll restored $J2" 10; sleep 3; shot 15_reopened_scroll_restored
  sleep 2
  echo "$J1 $J2 $J3" > $OUT/jobs.txt
else
  # ---- PART B: phone continuation. A new job from the car, followed and approved on a phone browser.
  sleep 2; shot h01_parked
  gear Drive; speed 40 25 0; waitlog "MOVE -> MOVING" 10; sleep 6
  voice bugfix "결제|버튼|버그" "결제 버튼 버그 고쳐서 미리보기 올려 줘"
  waitlog "JOB [0-9a-f]{12} bugfix accepted" 15; J=$(jobid bugfix); mark "job_bugfix $J"; echo "$J" > $OUT/job_handoff.txt
  # the phone opens the same job (public URL of the job server) while the agent is still working
  ( cd "$HERE/phone" && node phone_record.mjs --url "$PUB/m/$J" --out $OUT/phone_raw.mp4 --seconds 75 --approve-after-ready ${APPROVE_AFTER:-26} --shots $OUT > $OUT/phone.log 2>&1 ) & PH=$!
  mark "phone_rec_start $PUB/m/$J"
  waitlog "JOB $J WORKING 2/3" 30; sleep 1; shot h02_driving_status
  waitlog "JOB $J READY" 90; sleep 6
  speed 0 0 60; sleep 2; gear Park; waitlog "MOVE -> STOPPED" 10; sleep 8; shot h03_parked_waiting_phone_qr
  waitlog "JOB $J DEPLOYED" 60; mark phone_approved; sleep 5; shot h04_car_shows_phone_approved
  wait $PH; tail -3 $OUT/phone.log
fi

sleep 2
if [ -n "$REC" ]; then adb shell pkill -INT screenrecord; wait $RECPID 2>/dev/null; sleep 2; adb pull /data/local/tmp/demo.mp4 $OUT/$RECF >/dev/null && echo "rec ok $OUT/$RECF"; fi
logs > $OUT/logcat_$PART.txt
cp "$HERE/../server/data/audit.log" $OUT/audit.log 2>/dev/null
echo DEMO_DONE
