# ARCA Drive — Pleos Connect 동승 에이전트

> 운전 중에 한 약속을 기억했다가, 핸들에서 손을 떼지 않아도 말 한마디로 처리해 주는 Pleos Connect 앱.
> 기어 D에서는 화면을 닫고 음성으로만, P로 바꾸면 남은 일을 1탭으로 마무리합니다.

현대자동차그룹 차량용 앱 개발 공모전 (Code your App into Pleos App Market 2026) 출품작

## APK

- `apk/ArcaLabs_ARCADrive_release.apk` (release 빌드, v1.0.0, 서명됨)
- 패키지: `app.heyarca.drive`

## 개발·테스트 환경

| 항목 | 값 |
|---|---|
| 에뮬레이터 | Pleos Connect v2 AVD (`connect-avd/2.x/crp_car_arm64_emulator.zip`), Android 14 / API 34, arm64-v8a |
| AVD 디바이스 | `Pleos_Connect_v2_0` |
| 화면 규격 | 2560 × 1440 (앱 영역 1682 × 1210, 우측 앱 화면) |
| 빌드 | JDK 17, Gradle 8.9, AGP 8.5.2, Kotlin 2.0.21, Jetpack Compose |
| minSdk / targetSdk | 28 / 34 |

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

1. Pleos Playground 개발 문서대로 Pleos Connect 에뮬레이터를 실행합니다.
2. `adb install -r -g apk/ArcaLabs_ARCADrive_release.apk`
3. 앱 실행: `adb shell am start -n app.heyarca.drive/.MainActivity` (또는 앱 목록의 ARCA Drive)
4. Virtual Console(에뮬레이터 차량 제어)에서 기어를 D로 바꾸면 ARCA가 먼저 브리핑합니다. 가운데 주황색 구체를 누르면 마이크(STT)로 말할 수 있습니다.

### 시연 시나리오 자동 재현

```bash
adb forward tcp:8088 tcp:8088          # Virtual Console HTTP API
bash scripts/demo_scenario.sh          # 기어/속도 변경 + 녹음된 운전자 음성(PCM)을 STT SDK에 입력
REC=1 bash scripts/demo_scenario.sh    # 화면 녹화 포함
```

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
  MainActivity.kt    # Compose UI (주행: 음성 전용 / 주차: 1탭 카드), Gleo 액션 처리
app/src/main/assets/schema.json   # Gleo App Schema
scripts/                          # 에뮬레이터·시연 자동화
```
