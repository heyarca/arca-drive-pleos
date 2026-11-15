# 메이트 (Mate) for Gleo

> **차에서 말로 시키면, 내 클라우드 컴퓨터가 끝내 놓는다.**
> 운전 중 말로 맡긴 일을 내 클라우드 컴퓨터의 에이전트가 처리하고, 차가 멈추면 확인하고 승인하는 Pleos Connect 앱.

현대자동차그룹 차량용 앱 개발 공모전 (Code your App into Pleos App Market 2026) 출품작

## 1. 앱 이름 · 한 줄 소개

- 앱 이름: **메이트 (Mate)**, 부제 for Gleo. 패키지 `app.mate.gleo`, v2.0.0
- 한 줄 소개: 운전 중에는 말로 일을 맡기고 화면에는 한 줄만, 일은 차가 아니라 내 컴퓨터가 하고, 결과 확인과 배포 승인은 정차해 있을 때만.
- AI는 내 구독 그대로, 메이트는 차에서 그 AI에게 일을 시키는 작업 환경입니다. 메이트는 모델 사용량을 되팔지 않습니다.

## 2. APK 위치

- `apk/Mate_release.apk` (release 빌드, 서명됨)

## 3. 실행 방법

```bash
# 1) 내 컴퓨터(작업 서버)를 켭니다. 데모에서는 에뮬레이터를 띄운 Mac이 '내 클라우드 컴퓨터' 역할을 합니다.
cd server && python3 mate_server.py            # http://0.0.0.0:8790, 에뮬레이터에서는 http://10.0.2.2:8790
#    에이전트는 이 컴퓨터에 로그인되어 있는 Claude Code CLI(`claude -p`)를 그대로 씁니다. API 키 설정 없음.
#    폰으로 이어 보려면 공개 주소를 하나 붙입니다 (선택):
#    cloudflared tunnel --url http://127.0.0.1:8790   →  MATE_PUBLIC_BASE=https://<터널 주소> python3 mate_server.py

# 2) Pleos Connect 에뮬레이터에 설치 · 실행
adb install -r -g apk/Mate_release.apk          # -g: Pleos 권한 + android.car.permission.CAR_SPEED 허용
adb shell am start -n app.mate.gleo/.MainActivity

# 3) 시연 시나리오 자동 재현 (Virtual Console로 기어·속도, 운전자 음성 PCM을 STT SDK로)
adb forward tcp:8088 tcp:8088
REC=1 bash scripts/mate_scenario.sh             # 차 안 흐름 (약 2분)
PART=B REC=1 bash scripts/mate_scenario.sh      # 폰으로 이어 보기 (폰 화면은 scripts/phone/phone_record.mjs가 녹화)
```

가운데 구체를 누르면 마이크(STT)로 직접 말할 수 있습니다. 예: "방금 미팅 내용으로 A사 제안 페이지 만들어 줘", "일정을 맨 위로 올려 줘", "결과 보여 줘", "결제 버튼 버그 고쳐서 미리보기 올려 줘", "이대로 배포해 줘".

다른 컴퓨터(사용자 VM)에 연결: `adb shell am broadcast -a app.mate.gleo.DEMO_ENDPOINT --es url https://<내 VM> --es token <토큰>`

### Gleo App Schema 호출 (Gleo가 보내는 것과 같은 인텐트)

```bash
adb shell am start -n app.mate.gleo/.MainActivity -a DELEGATE_TASK  --es ACTION_PARAM "'{\"task\":\"A사 제안 페이지 만들어 줘\",\"kind\":\"proposal\",\"requestId\":\"r-1\"}'"
adb shell am start -n app.mate.gleo/.MainActivity -a SHOW_RESULT    --es ACTION_PARAM "'{\"jobId\":\"<job id>\"}'"
adb shell am start -n app.mate.gleo/.MainActivity -a APPROVE_DEPLOY --es ACTION_PARAM "'{\"jobId\":\"<job id>\"}'"
```

결과는 `ai.pleos.playground.intent.action.APP_ACTION_RESPONSE` 브로드캐스트로 돌려주며 `jobId`가 들어 있습니다. 같은 일을 다시 보내면(재시도) 새 일을 만들지 않고 같은 `jobId`를 돌려줍니다.

## 4. 테스트 환경 · 설정

| 항목 | 값 |
|---|---|
| 에뮬레이터 | Pleos Connect v2 AVD (`crp_car_arm64_emulator`), Android 14 / API 34, arm64-v8a, 이미지 `RELEASE.release_connect_v2.1.0` |
| 화면 | 2560 × 1440 (앱 영역 1682 × 1210) |
| 호스트 | macOS (Apple Silicon), Android Emulator 37.2.13.0, Python 3 (표준 라이브러리만), Claude Code CLI 로그인 상태 |
| 빌드 | JDK 17, Gradle 8.9, AGP 8.5.2, Kotlin 2.0.21, Jetpack Compose, minSdk 28 / targetSdk 34 |
| 필요한 설정 | 작업 서버 실행(위 1번). 기본 주소 `http://10.0.2.2:8790`. 별도 API 키 없음 |
| 폰 녹화(선택) | Node 18+, `scripts/phone`에서 `npm i` (playwright-core, 설치된 Chrome 사용) |

## 무엇이 실제로 동작하나

| 순간 | 차에서 | 내 컴퓨터에서 |
|---|---|---|
| 주행 중 "제안 페이지 만들어 줘" | STT(ON_DEVICE) → 화면에는 상태 한 줄 ("A사 제안 페이지 2/3 작성 중") | 미팅 전사에서 요구·제안·일정을 뽑아 실제 HTML 제안 페이지 생성 (`claude -p`) |
| 주행 중 "일정을 맨 위로 올려 줘" | 한 줄만 | 같은 페이지를 수정해 수정 전/후 두 벌 저장 |
| 속도 0 (기어 D, 신호 대기) | 아무것도 저절로 열리지 않음. "결과 보여 줘"라고 해야 수정 전 → 후가 열림 | |
| 다시 출발 | 결과 화면이 닫히고 보던 스크롤 위치를 기억, 다시 열면 그 자리 | |
| 주행 중 "결제 버튼 버그 고쳐서 미리보기 올려 줘" | 한 줄만 | 샘플 쇼핑몰 저장소에서 실패하는 테스트를 통과시키는 실제 코드 수정 → 미리보기 URL |
| 주행 중 "이대로 배포해 줘" | 거절: "배포 승인은 정차해 있을 때만" (이유를 말로 알려 줌) | 서버가 차량 속도 > 0인 승인을 거부하고 감사 로그에 남김 |
| 정차 후 "이대로 배포해 줘" | 미리보기 확인 후 승인 → 배포됨 | 승인된 뒤에만 미리보기를 배포 경로로 복사 |
| 주차 후 | "폰으로 이어 보기" QR · 주소 | 같은 작업의 모바일 페이지(상태 → 결과 → 배포 승인). 폰에서 승인하면 차 화면에도 "폰에서 승인"으로 반영 |

차량 속도는 `android.car` `PERF_VEHICLE_SPEED`, 기어는 Pleos Vehicle SDK에서 읽습니다. 속도를 읽을 수 없으면 `CarUxRestrictions`로 판단합니다.

## 구조 · 컴퓨트 모드

```
[차: 메이트 앱]  --(말, Gleo 액션)-->  POST /v1/jobs  -->  [내 컴퓨터: server/mate_server.py + 에이전트 CLI]
   상태 한 줄  <--  GET /v1/jobs/<id>                          결과 페이지 /work/<id>/… , 모바일 페이지 /m/<id>
   승인        -->  POST /v1/jobs/<id>/approve {source, speedKmh, gear}   (차에서 온 승인은 속도 0일 때만)
```

| 모드 | '내 컴퓨터' | 주소 | 상태 |
|---|---|---|---|
| 데모 (이 저장소) | 에뮬레이터를 띄운 Mac | `http://10.0.2.2:8790` | 실제 동작 |
| 서비스 | 구독자별 클라우드 VM 1대 | `https://<user>.vm…` + 사용자 토큰 | 프로비저닝 경로를 코드로 작성 (`infra/provision_user_vm.sh`, `infra/cloud-init.yaml`). 드라이런만 확인, 유료 자원은 만들지 않았음 |

두 모드는 같은 서버·같은 API입니다. 서비스 모드에서 구독자는 자기 AI 구독(Claude / ChatGPT)으로 VM의 공식 에이전트 CLI에 로그인합니다. 가격·원가(추정)는 `infra/README.md`.

```
app/src/main/java/app/mate/gleo/
  MateClient.kt      # 내 컴퓨터의 작업 서버 호출 (작업 생성·조회·승인)
  CompanionBrain.kt  # 말 → 일 맡기기 / 보여 주기 / 배포 승인, 주행·정차 규칙
  PleosBridge.kt     # Pleos SDK 5종 + android.car 속도 + CarUxRestrictions
  MainActivity.kt    # Compose UI (주행: 구체 + 한 줄 / 정차: 맡긴 일 카드, 결과, 승인, 폰 QR), Gleo 액션 처리
  Orb.kt             # 상태 구체
app/src/main/assets/schema.json   # Gleo App Schema
server/                           # 작업 서버 + 샘플 쇼핑몰 저장소(demo_shop)
infra/                            # 구독자별 VM 프로비저닝 경로
scripts/mate_scenario.sh          # 시연 자동화, scripts/phone/ 폰 화면 녹화
```

## 사용한 Pleos Playground SDK

| SDK | 버전 | 메이트에서 하는 일 |
|---|---|---|
| Vehicle | 2.3.2 | 기어(P/D), 배터리 SoC, 주행가능거리 |
| NaviHelper | 3.0.5 | 목적지 · 남은 거리/시간 |
| FusedLocation | 1.1.3 | 현재 위치, 도착 300 m 전 알림 |
| SpeechToText | 3.3.1 | `Mode.ON_DEVICE` 한국어 인식 |
| TextToSpeech | 3.3.1 | `Mode.ON_DEVICE` 한국어 응답 |
| Gleo Interface | App Schema | `DELEGATE_TASK`, `SHOW_RESULT`, `APPROVE_DEPLOY` (+ `BRIEF_MY_PROMISES`, `ADD_PROMISE`, `SEND_ARRIVAL_UPDATE`) |

`AndroidManifest.xml`에 `distractionOptimized=true`를 선언했고, 주행 중에는 목록·키보드·결과 화면을 띄우지 않습니다.

## 정직하게 밝혀 두는 것

- 데모의 '내 클라우드 컴퓨터'는 에뮬레이터를 띄운 Mac입니다. 구독자별 VM은 코드 경로만 있고 실제로 만들지 않았습니다.
- 미팅 전사(`assets/meetings/`), 샘플 쇼핑몰(`server/demo_shop`), 약속·목적지 데이터는 시연용 샘플입니다. '배포'는 작업 서버의 배포 경로(`/deployed/shop`)로 복사하는 것이며 외부 서비스에 올리지 않습니다.
- 운전자 음성은 합성 음성(macOS `say`, Yuna) PCM을 실제 Pleos STT SDK에 넣은 것입니다.
- 폰 화면 녹화는 실제 폰이 아니라 폰 크기 Chrome(Playwright, iPhone 에뮬레이션)으로 작업 서버의 공개 주소를 연 것입니다. 페이지와 승인은 실제 서버에 대한 실제 요청입니다.
- 에뮬레이터 Gleo 대신 Gleo가 보내는 것과 같은 인텐트(`am start -a <ACTION> --es ACTION_PARAM`)로 액션을 호출했습니다.
- 아직 만들지 않은 것(계획): 메일 답장 초안, 발표 자료 수정 같은 다른 종류의 일, 미사용 시 VM 자동 중지, 결제.
- 이전 버전의 흐름(출발 브리핑, 음성 답장, 도착 알림, 회의 요약, 드라이브 릴스, 충전 경유지)은 앱에 남아 있지만 이번 출품의 핵심은 아닙니다.

## 빌드

```bash
./gradlew assembleRelease   # Pleos nexus 저장소는 settings.gradle.kts에 등록됨
```

서명 키는 저장소에 포함하지 않습니다 (`keystore.properties`, `*.jks`는 .gitignore).

구체는 MIT 라이선스 [thinking-orbs](https://github.com/Jakubantalik/Libraries.dev/tree/main/packages/thinking-orbs) (Jakub Antalik)의 기하 엔진을 Kotlin/Compose로 옮긴 것입니다 (`third_party/thinking-orbs-LICENSE`). 글꼴은 Asta Sans입니다.
