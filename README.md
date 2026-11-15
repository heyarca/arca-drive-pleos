# ARCA Drive — Pleos Connect 동승 에이전트

> 운전 중에 한 약속을 기억했다가, 핸들에서 손을 떼지 않아도 말 한마디로 처리해 주는 Pleos Connect 앱.
> 기어 D에서는 화면을 닫고 음성으로만, P로 바꾸면 남은 일을 1탭으로 마무리합니다.

현대자동차그룹 차량용 앱 개발 공모전 (Code your App into Pleos App Market 2026) 출품작

## APK

- `apk/ArcaLabs_ARCADrive_release.apk` (release 빌드, v1.2.0, 서명됨)
- 패키지: `app.heyarca.drive`

## v1.2 — 주행 중에 일을 맡기는 순간 3가지 (에뮬레이터에서 실제 동작)

| 운전자가 하는 말 / 차량 상황 | 실제로 일어나는 일 | 결과물 |
|---|---|---|
| "오늘 오전 회의, 요약해서 팀에 보내놔." | STT 인식 → 구체가 '생각 중'으로 바뀌고, 오늘 회의 전사 파일에서 결정·할 일(담당자·기한)을 뽑아 요약 파일과 팀 채널 **초안**을 만듭니다. 외부로는 보내지 않고, 주차 후 '확인 후 보내기'를 눌러야 발송함에 들어갑니다. | `files/out/meeting_summary_<날짜>.md` |
| "오늘 찍은 사진들로 릴스 하나 만들어줘." | 주행 중 백그라운드에서 MediaCodec(H.264)+MediaMuxer로 9:16 릴스를 실제 인코딩합니다. 제목 카드는 NaviHelper 목적지로 만들고, 사진은 천천히 확대·팬하며 크로스페이드됩니다. 완성되면 음성으로 알리고, 주차 후 카드에서 바로 재생합니다. | `files/reels/drive_reel_<날짜>.mp4` (720×1280, 30fps) |
| 배터리 SoC가 떨어짐 (Vehicle SDK `EV_BATTERY_SOC`, `RANGE_REMAINING`) | 남은 거리와 오늘 다음 일정(왕복 거리)을 비교해 먼저 말을 겁니다. "응"이라고 하면 NaviHelper `addWaypoint`로 목적지 건물 충전소를 경유지에 넣습니다. | 경유지 추가, 카드 기록 |

- 주행 중 화면에는 구체와 한 줄, 진행 상태 알약 하나만 보입니다. 카드·재생·승인은 기어 P에서만 열립니다.
- 시연 데이터: 회의 전사(`assets/meetings/`)와 드라이브 사진 5장(`assets/drive_cam/`, 생성 이미지)은 앱 안에 시연용으로 넣었습니다. 실차에서는 회의 녹음 전사와 차량 카메라/폰 사진을 같은 폴더(`files/meetings`, `files/drive_cam`)로 받으면 됩니다. 다음 일정(14:00 수원 고객사 미팅, 왕복 92km)과 충전소 좌표도 시연용 값입니다.
- 에뮬레이터 주의: Pleos Connect v2 에뮬레이터(swiftshader GPU)에서 기본 3D 주행뷰 `ai.umos.drivingview`가 D단에서 반복 종료되며 앱 TaskView가 회색으로 비는 현상이 있어, `scripts/fresh_emulator.sh`가 시연 전에 `pm disable-user --user 10 ai.umos.drivingview`를 실행합니다(앱과 무관, `pm enable`로 복구).
- 결과물은 차량 프로필의 공유 저장소에도 게시됩니다: `Movies/ARCA Drive/drive_reel_<날짜>.mp4`, `Documents/ARCA Drive/meeting_summary_<날짜>.md` (MediaStore).
- 에뮬레이터 재현: `scripts/demo_scenario.sh`가 Virtual Console HTTP API로 기어·속도·배터리·주행가능거리를 바꾸고, 운전자 음성 PCM(`meeting`, `reel`, `yes_charge`)을 STT SDK에 넣습니다. 테스트 훅: `DEMO_TASK --es name meeting|reel|confirm`.

## 구체 (Thinking orb)

가운데 구체는 MIT 라이선스 오픈소스 [thinking-orbs](https://github.com/Jakubantalik/Libraries.dev/tree/main/packages/thinking-orbs) (Jakub Antalik, libraries.dev/orbs)의 기하 엔진을 Kotlin/Compose로 옮긴 것입니다 (`Orb.kt`). 점으로 된 3D 구를 회전·깊이 정렬해 그리며, 상태마다 라이브러리의 모드를 씁니다: 대기·듣기·말하기 = wave, 생각 = globe(스캔), 백그라운드 작업 = orbits. 색은 현대 네이비(#002C5F) 한 가지입니다.

## 개발·테스트 환경

| 항목 | 값 |
|---|---|
| 에뮬레이터 | Pleos Connect v2 AVD (`connect-avd/2.x/crp_car_arm64_emulator.zip`), Android 14 / API 34, arm64-v8a |
| 시스템 이미지 빌드 | `RELEASE.release_connect_v2.1.0` (fingerprint `Pleos/crp_avd_arm64/crp_avd_arm64:14/RELEASE.release_connect_v2.1.0/release_connect_v2.1.0:user/test-keys`) |
| Android Emulator | 37.2.13.0 (macOS, Apple Silicon) |
| AVD 디바이스 | `Pleos_Connect_v2_0` |
| 화면 규격 | 2560 × 1440, density 160 (앱 영역 1682 × 1210 = TaskView 853,90 – 2535,1300) |
| 빌드 | JDK 17, Gradle 8.9, AGP 8.5.2, Kotlin 2.0.21, Jetpack Compose |
| minSdk / targetSdk | 28 / 34 |

## 주행 중 동작 (주행 규제)

- `AndroidManifest.xml`에 `distractionOptimized=true`를 선언하고, Pleos Playground 주행 규제 개발 가이드대로 `CarUxRestrictionsManager`를 함께 봅니다.
- 기어가 P가 아니거나 `isRequiresDistractionOptimization()`이면 음성 전용(구체 + 한 줄). P이면서 제한이 없을 때만 1탭 카드.
- 기어 콜백이 늦는 경우를 대비해 `getCurrentGearSelectionState`를 1 Hz로 다시 조회합니다.

## 사용한 Pleos Playground SDK

| SDK | 버전 | ARCA Drive에서 하는 일 |
|---|---|---|
| Vehicle | 2.3.2 | GearSelection 리스너(+1Hz 조회)로 P↔D 전환 감지 → 음성 전용 / 1탭 카드 모드 전환. EvBattery SoC, BrakePedal, 주행 시간 |
| NaviHelper | 3.0.5 | `requestRoute`로 목적지 설정, `DestinationInfo`·`TripInfo`로 남은 거리/ETA, `onDestinationArrived` |
| FusedLocation | 1.1.3 | 현재 위치 + `addProximityAlert`(반경 300m, ENTER) → 도착 직전 자동 알림 |
| SpeechToText | 3.3.1 | `Mode.ON_DEVICE` 한국어 인식. 차량 음성을 외부 서버로 보내지 않음 |
| TextToSpeech | 3.3.1 | `Mode.ON_DEVICE` 한국어 응답 |
| Gleo Interface | App Schema | `assets/schema.json` — `BRIEF_MY_PROMISES`, `ADD_PROMISE`, `SEND_ARRIVAL_UPDATE` |

Android Automotive `CarUxRestrictionsManager`도 함께 확인해, 주행 제한 상태면 목록·키보드를 띄우지 않습니다.

## 실행 방법

필요한 설정: 별도 API 키·계정·서버가 없습니다. 약속 데이터는 앱 안에 시연용으로 들어 있고, 메시지는 앱 안의 발송함에 기록됩니다(외부 발송 채널은 2차 PT 전에 연결 예정). `-g` 옵션으로 설치하면 Pleos 권한(CAR_DRIVE, CAR_ENERGY, NAVI_ROUTE, FUSED_LOCATION, STT_SERVICE, TTS_SERVICE)이 함께 허용됩니다.

1. Pleos Playground 개발 문서대로 Pleos Connect 에뮬레이터를 실행합니다.
2. `adb install -r -g apk/ArcaLabs_ARCADrive_release.apk`
3. 앱 실행: `adb shell am start -n app.heyarca.drive/.MainActivity` (또는 앱 목록의 ARCA Drive)
4. Virtual Console(에뮬레이터 차량 제어)에서 기어를 D로 바꾸면 ARCA가 먼저 브리핑합니다. 가운데 구체를 누르면 마이크(STT)로 말할 수 있습니다.

### 시연 시나리오 자동 재현

```bash
adb forward tcp:8088 tcp:8088          # Virtual Console HTTP API
bash scripts/demo_scenario.sh          # 기어/속도 변경 + 녹음된 운전자 음성(PCM)을 STT SDK에 입력
REC=1 bash scripts/demo_scenario.sh    # 화면 녹화 포함
```

운전자 음성 PCM(16 kHz mono s16le, `app/src/main/assets/demo/`)은 합성한 자연스러운 한국어 음성이며, 실제 Pleos STT SDK(ON_DEVICE)에 넣어 인식합니다.

### Gleo App Schema 호출 확인

```bash
adb shell am start -a ADD_PROMISE -n app.heyarca.drive/.MainActivity --es ACTION_PARAM '{"counterpart":"최민호_실장","content":"계약서_검토_회신"}'
adb shell am start -a BRIEF_MY_PROMISES -n app.heyarca.drive/.MainActivity --es ACTION_PARAM '{"scope":"today"}'
```

결과는 `ai.pleos.playground.intent.action.APP_ACTION_RESPONSE` 브로드캐스트로 돌려줍니다(`adb shell dumpsys activity broadcasts history`로 확인).

데모용 브로드캐스트(테스트 훅): `app.heyarca.drive.DEMO_PCM --es name <yes_send|quote|yes|remember>` (음성 PCM을 STT SDK로), `DEMO_DEST`, `DEMO_NEAR`(지오펜스 ENTER와 같은 경로), `DEMO_RESET`.

## 빌드

```bash
./gradlew assembleRelease   # Pleos nexus 저장소는 settings.gradle.kts에 등록됨
```

서명 키는 저장소에 포함하지 않습니다 (`keystore.properties`, `*.jks`는 .gitignore).

## 구조

```
app/src/main/java/app/heyarca/drive/
  PleosBridge.kt     # Pleos SDK 5종 + CarUxRestrictions 연결
  CompanionBrain.kt  # 약속 판단: 브리핑, 음성 답장, 이름 보정, 도착 알림, 주차 정리
  PromiseStore.kt    # 약속/증거 저장
  MainActivity.kt    # Compose UI (주행: 구체 + 한 줄 / 주차: 1탭 카드), Gleo 액션 처리
  Orb.kt             # 생각하는 구체: thinking-orbs(MIT) 기하 엔진 Kotlin 포팅, 현대 네이비 #002C5F 한 색. 대기·듣기·말하기=wave, 생각=globe, 백그라운드 작업=orbits
  Moments.kt         # 주행 중 맡기는 일: 회의 요약(초안), 릴스 인코딩(MediaCodec), 공유 저장소 게시
app/src/main/assets/schema.json   # Gleo App Schema
scripts/                          # 에뮬레이터·시연 자동화
```
