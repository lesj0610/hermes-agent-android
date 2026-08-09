# Hermes Android — 설계 문서

## 목표

Claude Android / Codex Android 수준의 모바일 에이전트 앱. 백엔드는 기존 Hermes Agent를 **수정 없이** 사용하고,
앱은 얇은 클라이언트로만 존재한다. UI/UX는 Hermes의 기존 웹 대시보드·TUI를 따르지 않고 Claude Android 앱을
베이스로 새로 설계한다.

핵심 원칙:

1. **원본 무수정** — Hermes Agent 저장소에 diff 0. 백엔드 쪽 작업은 설정 활성화뿐.
2. **얇은 래퍼** — 에이전트 로직·툴·스킬·메모리는 전부 서버가 소유. 앱은 전송·표시·승인만 담당.
3. **유지보수 최소** — 업스트림이 움직여도 앱이 깨지지 않도록 안정된 HTTP 계약에만 의존.

### 넘지 않는 선 (하드 제약)

이 세 가지는 트레이드오프가 아니라 금지 사항이다. 어기는 설계는 채택하지 않는다.

- **앱 전용 백엔드를 세우지 않는다.** 중계 서버도, 프록시도, 어댑터 프로세스도 없다.
  앱은 이미 떠 있는 gateway의 api_server 플랫폼(기본 8642)에 순수 HTTP 클라이언트로 붙는다.
- **모바일 연결을 위해 데스크탑 앱(`apps/desktop`)을 건드리지 않는다.** 참고만 하고 패치하지 않는다.
  태블릿 레이아웃이 데스크탑 셸을 닮은 것은 구조를 보고 다시 그린 결과이지 코드 공유가 아니다.
- **에이전트 저장소에 모바일용 코드를 넣지 않는다.** 엔드포인트 추가, 필드 추가, 플래그 추가 전부 해당한다.
  서버에 없는 정보(예: locale)는 서버를 고치는 대신 앱이 자체적으로 해결한다.

기능이 이 선에 걸리면 기능을 포기한다. 실제로 포기한 것들:
데스크탑 우측 레일의 터미널·로컬 파일 탐색(Electron 로컬 전용),
`hermes.tool.progress` 기반 세밀한 진행률(다른 라우트 소속),
서버 언어 설정 연동(api_server가 노출하지 않음).

---

## 1. 백엔드 계약

앱이 쓰는 표면은 전부 `gateway/platforms/api_server.py`에 이미 존재한다. 신규 엔드포인트 개발 불필요.

### 1.1 실행/스트리밍 (주 경로)

| 메서드 | 경로 | 용도 |
|---|---|---|
| POST | `/v1/runs` | 에이전트 턴 시작 |
| GET | `/v1/runs/{run_id}/events` | SSE 이벤트 스트림 |
| POST | `/v1/runs/{run_id}/approval` | 도구 승인 응답 |
| POST | `/v1/runs/{run_id}/stop` | 실행 중단 |
| GET | `/v1/runs/{run_id}` | 실행 상태 조회 |

### 1.2 세션

| 메서드 | 경로 | 용도 |
|---|---|---|
| GET | `/api/sessions` | 세션 목록 |
| GET | `/api/sessions/{id}` | 세션 상세 |
| GET | `/api/sessions/{id}/messages` | 히스토리 로드 |
| POST | `/api/sessions/{id}/chat` | 단발 메시지 |
| POST | `/api/sessions/{id}/chat/stream` | 스트리밍 메시지 |
| POST | `/api/sessions/{id}/fork` | 세션 분기 |
| POST | `/api/sessions/{id}/model` | 모델 전환 |

### 1.3 메타

`GET /v1/models`, `GET /v1/skills`, `GET /v1/toolsets`, `GET /v1/capabilities`, `GET /health`

OpenAI 호환 경로(`/v1/chat/completions`, `/v1/responses`)도 있으나 앱은 **`/v1/runs` 경로를 사용한다.**
승인 이벤트와 툴 진행 상황이 이쪽에만 노출되기 때문.

### 1.4 SSE 이벤트 타입

`GET /v1/runs/{run_id}/events`가 흘리는 프레임은 **전부 이름 없는 `data:` 프레임**이고,
이벤트 종류는 JSON 본문의 `event` 키에 들어 있다. SSE `event:` 라인은 이 경로에서 쓰이지 않는다.
모든 프레임이 `run_id`와 `timestamp`를 함께 싣는다.

| event | 추가 필드 | 의미 |
|---|---|---|
| `message.delta` | `delta` | 어시스턴트 텍스트 조각. 대화 본문은 전부 이걸로 온다 |
| `reasoning.available` | `text`, `error` | 추론 텍스트 |
| `tool.started` | `tool`, `preview` | 도구 실행 시작 |
| `tool.completed` | `tool`, `preview`, `duration`, `error` | 도구 실행 종료 |
| `approval.request` | `choices`, `command` 등 | 승인 요청. 실행이 멈춘다 |
| `approval.responded` | `choice`, `resolved` | 승인 처리됨 |
| `run.completed` | `output`, `usage` | 정상 종료 |
| `run.failed` | `error` | 오류 종료 |
| `run.cancelled` | — | 취소됨 |

스트림에 **나오지 않는** 것 두 가지 — 혼동하기 쉬우니 명시한다:

- `run.stopping` — 이벤트가 아니라 실행 상태값이다. `GET /v1/runs/{run_id}`의 `last_event`로만 관측된다
- `hermes.tool.progress` — `/v1/chat/completions` 스트리밍 경로의 named SSE 이벤트다.
  `/v1/runs` 경로는 이 이벤트를 쓰지 않는다

### 1.5 승인 페이로드

`approval.request` 이벤트 본문:

```json
{
  "event": "approval.request",
  "run_id": "...",
  "timestamp": 1234567890.0,
  "command": "<자격증명 리댁션 적용됨>",
  "choices": ["once", "session", "always", "deny"],
  "smart_denied": false,
  "allow_permanent": true
}
```

`choices`는 서버가 계산해서 내려준다 (`_approval_event_choices`):

- `smart_denied: true` → `["once", "deny"]`
- `allow_permanent: false` → `["once", "session", "deny"]`
- 그 외 → `["once", "session", "always", "deny"]`

**앱은 choices를 자체 계산하지 않는다.** 서버가 준 배열만 그대로 버튼으로 렌더링한다. 정책이 업스트림에서
바뀌어도 앱 수정이 필요 없게 하는 지점.

`POST /v1/runs/{run_id}/approval` 요청 본문은 선택한 값 하나. 서버는 `once|session|always|deny` 외의 값을
`invalid_approval_choice`로 거부한다.

### 1.6 인증

Bearer 토큰. 서버는 `API_SERVER_KEY` 시크릿과 대조한다.

```
Authorization: Bearer <API_SERVER_KEY>
```

CORS 허용 헤더는 `Authorization, Content-Type, Idempotency-Key`.

---

## 2. 백엔드 준비

1. gateway 설정에서 `api_server` 플랫폼 활성화
2. `API_SERVER_KEY` 시크릿 설정 (앱이 쓸 강한 랜덤 토큰)
3. `platforms.api_server.max_concurrent_runs` 기본값 10 — 폰 단독 사용이면 그대로 둔다
4. gateway 재시작

에이전트 소스 변경 없음. `config.yaml` + 시크릿만.

동작 확인은 두 줄이면 된다. `/health`는 인증 없이 응답하므로 도달성만,
`/v1/models`는 키 없이 401을 반환하므로 인증이 살아 있는지를 각각 증명한다.

```bash
curl -s http://<gateway>:8642/health
curl -s -o /dev/null -w '%{http_code}\n' http://<gateway>:8642/v1/models   # 401 이어야 정상
```

---

## 3. 네트워크

**접속 경로는 앱이 정하지 않는다.** 사용자가 정한다.

게이트웨이에 닿는 방법은 여러 가지가 있고 — VPN·메시 터널, LAN, 공개 도메인 앞의
TLS 역프록시 — 위협 모델이 서로 다르다. 어느 쪽이 적절한지는 서버를 운영하는
사람만 안다. 앱은 주소를 받아서 붙을 뿐이다.

- 게이트웨이 기본 포트는 **8642**
- `http://`, `https://` 둘 다 지원한다. 사설 CA도 신뢰하므로 자체 서명 인증서를
  쓰는 구성에서 앱을 다시 빌드할 필요가 없다
- 서버 미도달과 인증 실패를 구분해서 표시한다. `/health`는 인증이 없어서
  도달성만 증명하므로, 키 검증은 `/v1/models`를 한 번 더 찔러서 판정한다

**초기 구현의 오류 기록**: 처음에는 `network_security_config.xml`에서 평문을
`*.ts.net`에만 허용했다. Tailscale을 쓰는 구성 하나만 상정한 결정이었고, LAN 주소나
자체 도메인 같은 다른 정상적인 구성을 전부 조용히 막았다. 지금은 평문을 허용하되
**설정 화면에서 `http://` 사용 시 암호화되지 않는다는 사실을 표시**한다.
위험을 보이게 하는 것은 정직하지만, 선택을 대신 하는 것은 아니다.

---

## 4. 앱 아키텍처

### 4.1 스택

- Kotlin, Jetpack Compose, Material 3 (Expressive)
- 최소 SDK 26 / 타깃 최신
- HTTP: Ktor client (OkHttp 엔진) — SSE 스트리밍을 라인 파서로 직접 처리
- 직렬화: kotlinx.serialization, 미지의 필드는 무시 (업스트림 필드 추가에 안 깨지도록)
- 저장: DataStore(설정) + Android Keystore(API 키)
- 스트리밍 지속: foreground service — 앱이 백그라운드로 가도 실행 유지
- 알림: 승인 요청 도착 시 고우선 알림 + 알림에서 바로 응답 액션

### 4.2 코드 배치

설계 초안은 4개 Gradle 모듈이었으나 **단일 모듈 + 패키지**로 구현했다.
앱 전체가 3천 줄 규모이고 모듈 경계가 강제할 규칙이 없다. 모듈을 나누면
Gradle 설정만 네 배가 되고 빌드는 느려진다. 경계가 필요해지면 그때 쪼갠다.

```
io.github.lesj0610.hermes/
  net/       Dto.kt  RunEvent.kt  Sse.kt  HermesApi.kt
  core/      Graph.kt  Settings.kt  SecretStore.kt  Language.kt
  data/      Transcript.kt  RunEngine.kt
  ui/        HermesShell.kt  AppViewModel.kt
             chat/  sessions/  settings/  components/  theme/
  service/   RunService.kt  Notifications.kt  ApprovalActionReceiver.kt
```

핵심 배치 근거 하나: `RunEngine`은 ViewModel이 아니라 **애플리케이션 스코프**
(`Graph`)에 있다. 실행은 사용자가 다른 화면으로 가거나 앱을 내려도 계속되어야
하고, 그 사이 도착한 승인 요청이 알림 계층에 닿아야 한다. ViewModel에 두면
화면을 벗어나는 순간 스트림이 끊긴다.

### 4.3 화면 (Claude Android 베이스)

| 화면 | 내용 |
|---|---|
| 대화 | 스트리밍 메시지, 도구 실행 카드, 중단 바, 인라인 오류 |
| 승인 시트 | 하단 시트. 명령 전문 + 서버 `choices` 그대로 렌더링 |
| 세션 목록 | 목록 + 검색(제목·미리보기·모델), 상태 점 |
| 설정 | 서버 주소/키, 모델, 언어, 알림, 연결 배너 |

### 4.4 폰과 태블릿

레이아웃은 폭 **840dp**를 기준으로 갈린다. Material의 expanded 분기점이고,
데스크탑 앱이 자체 레일을 접는 768px과도 대체로 맞는다.

- **폰(< 840dp)** — 한 번에 한 패널. 세션 목록 → 대화 → 설정
- **태블릿(≥ 840dp)** — 데스크탑 셸 구조 그대로:
  좌측 세션 레일(300dp) │ 중앙 대화 │ 우측 활동 레일(300dp)

데스크탑 우측 레일은 files / terminal / review 탭이지만, 셋 다 Electron
로컬 접근이라 HTTP로 재현할 수 없다. 그래서 태블릿 우측 레일은 게이트웨이가
실제로 노출하는 것 — 열린 세션의 도구 실행 목록 — 을 보여준다.
없는 기능을 흉내내지 않는다.

태블릿에는 데스크탑처럼 **하단 상태바**가 붙는다. 데스크탑 statusbar 항목 중
HTTP로 넘어오는 네 가지만 싣는다:

| 항목 | 출처 |
|---|---|
| 게이트웨이 상태 + 지연 | `/health` 왕복 시간, `/v1/models` 인증 판정 |
| 실행 타이머 | 실행 시작 시각부터 1초 간격. 유휴일 때는 타이머가 아예 돌지 않는다 |
| 컨텍스트 사용량 | `run.completed`의 `usage` (`input_tokens`/`output_tokens`) |
| 모델 | 설정에서 고른 값, 없으면 서버 기본값 |

데스크탑의 나머지 항목(cron, agents, terminal, command center, approval mode)은
api_server 표면에 대응물이 없어서 넣지 않았다.

### 4.6 권한

둘 다 없으면 앱이 조용히 망가지는 종류라, 설정에 사유와 함께 노출하고 실제로 요청한다.

- **알림(POST_NOTIFICATIONS)** — 없으면 승인 요청이 사용자에게 도달하지 않고
  에이전트는 영원히 멈춰 있는다. 첫 실행 때 한 번 자동으로 묻는다.
  API 33 미만은 매니페스트 선언으로 충분하고, 꺼져 있으면 시스템 설정으로 보낸다.
- **배터리 최적화 예외** — 없으면 긴 실행 도중 포그라운드 서비스가 얼어붙어
  이벤트 스트림이 끊긴다. 시스템 화면에서 승인되므로 돌아왔을 때 상태를 다시 읽는다.

배터리 예외는 원탭 다이얼로그(`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`)가 아니라
설정 목록을 여는 방식이다. 원탭 쪽은 매니페스트 권한이 필요한데 Play가 그 권한을
알람·VoIP 등 특정 유형에만 허용하기 때문이다. **Play 배포가 요구사항이므로** 두 탭 더
걸리는 쪽을 택했다. 자세한 내용은 [PLAY.md](PLAY.md).

### 4.5 언어

앱이 자체 문자열 리소스를 소유한다(`values/`, `values-ko/`). 선택이 아니라
제약이다: api_server는 locale을 아예 노출하지 않아서 백엔드를 고치지 않는 한
읽어올 값이 없고, 백엔드 수정은 이 프로젝트 범위 밖이다. 결과적으로 뒤에 붙은
에이전트가 한국어를 하든 말든 앱 화면은 한국어로 동작한다.

언어 **코드 체계**는 에이전트 `locales/` 세트를 따른다
(af ar de en es fr ga hu it ja ko pt ru tr uk zh zh-hant).
현재 실제 번역이 있는 것은 en/ko 두 개이고, 추가는 `values-<code>/strings.xml`
하나와 `Language.SUPPORTED` 한 줄이면 끝난다.

설정에서 언어를 바꾸면 Activity를 재생성한다. 리소스는 `attachBaseContext`
시점에 결정되므로 이미 만들어진 Activity가 나중에 다시 해석할 방법이 없다.

### 4.4 승인 상태 기계

```
run 진행 → approval.request 수신 → run 상태 "waiting_for_approval"
  → 알림 + 시트 표시 → 사용자 선택 → POST /approval
  → approval.responded 수신 → 스트림 재개
```

앱 종료·프로세스 사망 후 재진입 시: `GET /v1/runs/{run_id}`로 상태 복구 후 SSE 재연결.

---

## 5. 유지보수 경계

앱이 의존하는 것과 의존하지 않는 것을 명시한다.

**의존한다 (깨지면 앱 수정 필요)**
- 위 엔드포인트 경로와 HTTP 메서드
- SSE 이벤트 이름 7종
- 승인 choice 문자열 `once|session|always|deny`

**의존하지 않는다**
- 응답 JSON의 추가 필드 (무시 파싱)
- Hermes 내부 툴·스킬·프로바이더 구성
- 웹 대시보드 / TUI / desktop 앱의 어떤 것도

---

## 6. 툴체인 요구사항

현재 개발 머신 상태:

- JDK 21 — 있음
- Tailscale — 있음
- **Android SDK — 없음** (`ANDROID_HOME` 미설정, `adb` 없음)
- **Gradle — 없음** (프로젝트에 wrapper 포함하면 해결)

착수 전 필요: Android command-line tools + platform + build-tools 설치, SDK 라이선스 수락.
용량 수 GB 규모.

---

## 7. 마일스톤

1. 저장소 스캐폴드 + Gradle wrapper + 모듈 골격
2. `core-net` — 인증, `/health`, `/v1/models` 연결 확인
3. SSE 파서 + `/v1/runs` 스트리밍 대화 (최소 UI)
4. 승인 시트 + 알림 액션
5. 세션 목록·히스토리·분기
6. Claude Android 기반 UI 다듬기 (테마, 도구 카드, 코드 블록)
7. foreground service + 프로세스 사망 복구
8. 릴리스 빌드 / 서명 / 사이드로드 배포
