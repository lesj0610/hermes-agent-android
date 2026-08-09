# Google Play 배포

Play 배포를 전제로 구성을 맞췄다. 이 문서는 (1) 그 때문에 코드에서 무엇을 바꿨는지,
(2) 콘솔에서 사람이 해야 하는 것이 무엇인지, (3) 아직 남아 있는 위험이 무엇인지를 적는다.

---

## 1. Play 때문에 바꾼 것

### 1.1 배터리 최적화 예외 요청 방식

`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`를 **선언하지 않는다.**

Play는 이 권한을 알람·VoIP·컴패니언 기기 등 정해진 유형에만 허용한다. 에이전트
클라이언트는 해당하지 않고, 선언만으로 심사에서 거부된다.

대신 `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`(권한 불필요)로 배터리 설정
목록을 열고, 설정 화면 문구가 무엇을 골라야 하는지 설명한다. 원탭 다이얼로그보다
두 탭 더 걸리지만 배포 자격과 바꿀 만한 값이 아니다.

### 1.2 AAB 언어 split 비활성화

```kotlin
bundle { language { enableSplit = false } }
```

Play는 AAB를 언어별로 쪼개서 기기 로케일에 맞는 리소스만 설치한다. 그대로 두면
영어 기기에 한국어 문자열이 아예 설치되지 않아, 앱 안의 언어 선택이 한국어를
골라도 영어로 나온다 — 설정이 고장난 것처럼 보인다.

검증됨: 릴리스 APK에서 `permission_grant`/`approval_title`의 `ko` 값이 각각
`허용`, `승인 필요`로 나오고, `BundleConfig.pb`에 LANGUAGE 차원 split 비활성이 기록된다.

### 1.3 R8 활성화 + 직렬화 유지 규칙

릴리스는 `isMinifyEnabled = true`, `isShrinkResources = true`.

kotlinx.serialization은 생성된 serializer를 이름으로 찾기 때문에 R8이 그냥 지운다.
지워지면 **디버그 빌드에서는 절대 재현되지 않는** 런타임 크래시가 된다. `proguard-rules.pro`가
`net` 패키지의 serializer 표면을 유지한다.

검증됨: 릴리스 APK dex에 `io.github.lesj0610.hermes.net.*$$serializer`가 실제로 남아 있다.

### 1.4 서명

`keystore.properties`(git 제외)가 있으면 릴리스에 서명하고, 없으면 서명 없이 빌드된다.
비밀값 없이도 CI와 로컬 검사가 돌아간다.

업로드 키 생성:

```bash
keytool -genkeypair -v -keystore upload.jks -keyalg RSA -keysize 4096 \
        -validity 10000 -alias upload
```

`keystore.properties` (저장소 루트, 커밋 금지):

```properties
storeFile=upload.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

---

## 2. 콘솔에서 해야 하는 것

### 2.1 포그라운드 서비스 선언

매니페스트가 `foregroundServiceType="dataSync"`를 쓴다. Play 콘솔의 포그라운드
서비스 선언 양식에 용도를 적어야 한다. 사실대로 쓰면 된다:

> 사용자가 시작한 에이전트 실행 동안 사용자 본인 서버와의 이벤트 스트림을 유지한다.
> 실행 중에만 동작하고 완료 시 스스로 종료한다. 도구 실행 승인 요청을 즉시
> 전달하기 위해 필요하다.

Android 15부터 `dataSync`는 하루 누적 6시간 제한이 있다. 한 번의 실행 단위로는
문제되지 않지만, 하루 종일 스트림을 붙잡는 용도로 바꾸려면 유형을 재검토해야 한다.

### 2.2 데이터 보안(Data safety) 양식

앱이 수집하거나 제3자에게 보내는 데이터는 **없다**. 다만 양식에는 사실을 정확히 적는다.

- 수집: 없음. 분석 SDK, 광고 SDK, 크래시 리포터 전부 없음
- 공유: 없음
- 사용자가 입력한 프롬프트와 대화는 **사용자 본인이 지정한 서버로만** 전송된다.
  개발자가 운영하는 서버는 존재하지 않는다
- 기기 저장: 게이트웨이 주소와 API 키. 키는 Android Keystore로 봉인하고
  클라우드 백업·기기 이전에서 전면 제외한다(`data_extraction_rules.xml`)

### 2.3 개인정보처리방침 URL

Play는 모든 앱에 방침 URL을 요구한다. 수집이 없더라도 링크 자체는 있어야 한다.
위 2.2 내용을 그대로 담은 정적 페이지면 충분하다.

### 2.4 타깃 API 수준

`targetSdk = 37`. Play의 최소 요구치를 여유 있게 넘는다.

---

## 3. 남아 있는 위험

### 3.1 앱 이름

세 가지 제약이 동시에 걸린다.

**(1) 이름에 "Android"를 넣을 수 없다.** Android 브랜드 가이드라인이 명시한다:

> "Android", or anything confusingly similar to "Android", cannot be used in names
> of applications or accessory products. Instead, use "for Android".
> — https://developer.android.com/distribute/marketing-tools/brand-guidelines

따라서 `Hermes Android`는 안 되고 `... for Android`가 승인된 형태다.
저장소 디렉터리 이름(`hermes-android`)은 제품명이 아니므로 해당 없다.

**(2) Hermès 상표.** "Hermes" 단독은 럭셔리 브랜드와 충돌 소지가 있다.
용도가 드러나는 수식어를 붙이면 혼동 가능성이 줄어든다.

**(3) 업스트림 사칭.** "Hermes Agent"는 NousResearch 프로젝트명이다.
공식 앱으로 읽히면 Play의 Impersonation 정책에 걸린다. 설명문 첫 줄에
비공식 서드파티 클라이언트임을 명시한다.

셋을 동시에 만족하는 조합:

| 위치 | 값 |
|---|---|
| 런처 라벨 (`app_name`) | `Hermes Agent` |
| Play 등록명 | `Hermes Agent for Android` (24자 / 제한 30자) |
| `applicationId` | `io.github.lesj0610.hermes` — 게시 후 변경 불가, 건드리지 말 것 |

등록 설명문에 Android가 처음 나올 때 상표 표기를 붙인다:
"Android is a trademark of Google LLC."

### 3.2 평문 HTTP

게이트웨이는 평문 HTTP이고 기밀성은 WireGuard 터널이 담당한다. Play 정책 위반은
아니지만, 심사에서 질문이 올 수 있다. `network_security_config.xml`이 평문을
기본 차단하고 `*.ts.net`에만 허용하는 것이 그대로 답변이 된다.

### 3.3 실기기 미검증

현재까지 컴파일·유닛 테스트·lint·R8 산출물 검사만 통과했다. 화면 렌더링, 실제 SSE
수신, 알림 액션, 배터리 설정 화면 이동은 실기기에서 확인하지 않았다. 내부 테스트
트랙에 올리기 전에 최소 1회 실기기 확인이 필요하다.

---

## 4. 릴리스 빌드

```bash
source ./env.sh
./gradlew clean testDebugUnitTest lintRelease bundleRelease
# 산출물: app/build/outputs/bundle/release/app-release.aab
```

현재 상태: 테스트 16/16 통과, lint 에러 0, AAB 5.0 MB.
