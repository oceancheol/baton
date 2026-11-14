# Baton (바통)

주차장을 떠나는 차가 조작 없이 "곧 자리가 납니다"를 알리고, 가장 먼저 닿을 수 있는 차 한 대를 그 자리로 안내하는 차량용(Android Automotive, 가로 화면) 앱.

현재 단계는 M1이다. Pleos Connect 차량 서비스가 있는 기기(Pleos Connect 에뮬레이터)에서는 운전석 문·착좌·브레이크 페달·주차 브레이크 경고등을 Pleos Vehicle SDK로 실제로 읽고, 화면 맨 위 줄에 `Pleos 차량 신호`라고 표시한다(값을 읽기 전에는 `Pleos 연결 중`, 읽지 못하면 `Pleos 연결 불가`). 착좌 값의 방향 같은 해석은 이 에뮬레이터에서만 확인했으므로, 그 밖의 환경(실차 포함)에서는 착좌를 쓰지 않아 자동 예고가 꺼진다(아래 "실제 차량 신호의 처리"). 차량 서비스가 없는 기기(일반 Android 에뮬레이터 등)에서는 차량 SDK 없이 돌아가며 차량 신호도 앱 안의 모의 구현이다(`모의 신호`). 위치와 상대 차는 어느 쪽에서든 모의 구현이다. 모의 요소는 맨 위 줄에 하나씩 따로 표시한다(`모의 신호` / `모의 위치` / `모의 상대 차` / `시간 전진 +N초`).

## 구성

| 모듈 | 내용 |
|---|---|
| `:core` | 순수 Kotlin/JVM. Android 의존 없음. `signal`(신호 모델·상태, `SignalSource`), `departure`(출차 판정 상태기계), `match`(안내 순서 계산, 예고·안내 조정, 상태기계→안내 다리, 모의 상대 차) |
| `:app` | Android, Jetpack Compose. 화면 4종(나가는 차 / 들어오는 차·대기 / 들어오는 차·우선 안내 / 주차 완료)과 시연 패널, `PleosSignalSource`(Pleos Vehicle SDK), `FakeSignalSource`(모의) |

출차 판정 (`DepartureStateMachine`)
- `PARKED → BOARDING → ANNOUNCING → DEPARTING → VACATED`.
- 120초는 예고 시작(ANNOUNCING 진입)부터 한 번만 센다. 단계가 바뀌어도 다시 세지 않는다. 문 재개방·착좌 해제는 60초 뒤 취소(BOARDING 중에 열린 문은 예고가 시작된 때부터 60초).
- 최소 신호는 운전석 착좌와 위치다. 둘 중 하나라도 읽지 못하면(미지원·일시 불가·아직 미수신) 자동 예고를 끄고 이유를 화면에 한 줄로 적는다. 보조 신호(문, 벨트, 주차 브레이크 경고등, 페달)는 미지원·일시 불가면 빼고 대체 규칙으로 판정한다.
- 신호마다 상태(미수신 / 사용 가능 / 미지원 / 일시 불가)와 갱신 시각을 둔다. `SignalSource`는 "현재 값 조회(`currentSnapshot`) → 이후 변경 수신(`events`)" 순서로 쓰고, 상태기계는 `restore(snapshot)`으로 초기값을 '변화'로 세지 않고 복원한다. 이미 앉아 있는 채로 구독을 시작하면 탑승한 상태로 복원하고, 이미 꺼져 있던 주차 브레이크는 "해제"로 세지 않되 페달 밟음이나 "꺼진 채 이동 시작"으로 출차 임박을 판정한다.
- 신호에는 관측 시각과 주차 세대를 붙인다. 더 오래된 관측과 이전 주차 세대의 신호는 거부한다. 현재 값 스냅샷도 같다: 주차 세대와 조회 요청 번호를 붙여, 다른 세대이거나 지난 조회의 답이면 통째로 버리고, 신호마다 이미 반영한 관측보다 오래된 값은 덮어쓰지 않는다. 새 주차는 세대를 한 번만 올린 뒤 같은 세대의 조회 답으로 복원한다.
- 앱을 처음 연결할 때의 복원과 새 주차의 기준값 복원을 구분한다. 처음 연결할 때 이미 앉아 있으면 탑승한 상태로 복원한다. 새 주차(`다음 바통`, `처음부터`)에서 현재 값을 비동기로 읽는 경우(실제 차량 신호)에는 그 답을 "새 주차의 기준값"으로만 쓰고, 계속 앉아 있다는 이유로 예고하지 않는다. 문 여닫힘(문 신호가 없으면 하차 후 재착좌)을 본 뒤에 판정한다.
- 입력 하나가 낸 전이 목록을 순서대로 돌려주고, `DepartureGuidanceBridge`가 그대로 예고 생성 → 안내 시작 → 비움/취소로 옮긴다(예고 세대로 예고 ID를 만든다).
- "이번엔 예고 안 함"은 차가 움직이는 중(출차 임박 이후 또는 이동 관측 뒤)에는 받지 않는다. 버튼도 꺼진다.

실제 차량 신호의 처리 (`PleosSignalSource`, 순번 정리는 `SignalSequencer`)
- 관측 시각·순번·주차 세대는 SDK 콜백에 들어온 순간에 찍고 처리할 때까지 그대로 쓴다. 새 주차 전에 받아 아직 처리하지 않은 값은 옛 세대로 나가 상태기계가 거부한다.
- 현재 값 조회는 한 번에 하나만 돈다(앱 시작 때의 조회도 한 번이다). 조회를 열고 → 변경 구독을 등록하고 → 현재 값을 묻는다. 조회하는 동안 들어온 변경은 모아 두었다가, 답(기준값) 뒤에 순번대로 내보낸다. 그래서 조회 중의 문 여닫힘이 사라지지 않고, 늦게 돌아온 답이 그 뒤의 구독 값을 덮어쓰지 못한다.
- 10초마다 현재 값을 다시 묻는다. 하나도 읽지 못하면(실패 또는 3초 무응답) 차량 신호를 모두 일시 불가로 바꾼다. 착좌가 최소 신호이므로 진행 중인 예고는 취소되고 화면에 `Pleos 연결 불가`와 이유가 나온다. 읽지 못하는 동안에는 확인할 때마다 SDK 연결을 놓았다가 다시 잡고, 다시 읽히면 현재 값을 다시 읽어 복원한다.
- 에뮬레이터에서만 확인한 해석 세 가지(착좌 값 뒤집어 읽기, 안전벨트 제외, "경고등 목록에 항목 없음 = 꺼짐")는 확인된 환경(`Build.FINGERPRINT`가 `Pleos/crp_avd`로 시작하는 Pleos Connect 에뮬레이터)에서만 쓴다. 그 밖의 환경에서는 착좌를 일시 불가로 두어 자동 예고를 끄고(화면에 이유 표시), 주차 브레이크 경고등은 빼고 페달·이동 대체 규칙으로 판정하며, 벨트는 SDK의 지원 확인 결과를 따른다.
- 화면이 끝나면(`BatonViewModel.onCleared`) 구독을 풀고 `Vehicle.release()`를 부른다.
- 차량 상태 값이 바뀔 때마다 남기는 로그(Logcat `BatonPleos`)는 debug 빌드에서만 남긴다. release 빌드는 연결·지원 확인·실패만 남긴다.

한 대에게만 안내 (`GuidanceCoordinator`)
- 순서: (서행 기준 도착 예상 − 대기 보정)을 1초 단위로 반올림해 작은 차가 먼저. 그 값이 같을 때만 양보 기록이 많은 차가 먼저다. 양보 기록 저장소는 하나다.
- 안내는 min(배정 + 180초, 앞 차 이탈 + 180초)까지만 유효하다. 자리도 이탈 후 180초가 지나면 안내·집계·추천에서 빠지며, 중복 비움 통보는 이 시각을 늘리지 않는다. 유효성은 타이머가 아니라 조회·보고 시점의 시각으로 본다.
- 예고와 안내에 ID가 있고, 주차·지나침 보고는 현재 유효한 안내 ID와 일치할 때만 받는다.
- 위치 품질이 낮으면 안내 해상도를 구역으로 내려 화면에 줄 대신 구역만 표시한다. 안내 중에 품질이 낮아져도 상태 전이를 기다리지 않고 바로 낮춘다. 한 번 구역으로 낮춘 예고는 품질이 돌아와도 줄 안내로 되돌리지 않는다.
- 세 값을 잰다: 예고부터 이탈까지 시간, 잘못 보낸 안내 수/전체 안내 수, 안내 뒤 주차 성사 수/전체 안내 수. '잘못 보낸 안내'는 보낸 뒤 그 예고가 취소된 안내이며, 지나침·만료로 먼저 끝난 안내도 예고가 취소되면 포함한다(예고마다 한 번만 센다). 누적값은 보관량이 제한된 결과 기록과 별도의 카운터로 센다.

기타
- 모의 상대 차(`MockPeerService`)는 지난 구간을 예정 시각 순서대로 처리한다. 30초를 한 번에 건너뛰든 1초씩 30번 가든 결과가 같다. 안내가 발급되기 전 구간에는 이동을 적용하지 않고, 자리가 비기 전 구간에서는 도착 처리하지 않는다. 밖에서 들어오는 입력(신호·버튼·보고)은 그 시각까지 세계를 먼저 진행한 뒤에 적용한다.
- 주차 위치·걸린 시간·기록은 메모리에만 둔다(앱을 끄면 지워진다). 기기 저장은 다음 단계다.
- 신호 소스 주입 지점: `app/src/main/kotlin/app/baton/parking/signal/SignalSourceProvider.kt` 한 곳. Pleos 차량 서비스가 있으면 `PleosSignalSource`, 없거나 SDK 초기화가 실패하면(SDK 클래스를 읽지 못하는 경우 포함) `FakeSignalSource`를 쓴다. SDK 호출은 `PleosSignalSource.kt` 한 파일에만 있고, 화면 쪽은 `BatonSignalSource` 인터페이스만 본다.

## 실행 방법

필요한 것: JDK 17 이상(개발은 21), Android SDK(platform `android-35`, build-tools `35.0.0`). `ANDROID_HOME` 환경 변수 또는 `local.properties`의 `sdk.dir`로 SDK 위치를 알려 준다.

```bash
./gradlew :core:test            # 단위 시험 (판정·안내 규칙)
./gradlew :app:testDebugUnitTest  # 화면 상태 시험 (에뮬레이터 없이 실행)
./gradlew :app:assembleDebug    # debug APK
./gradlew :app:assembleRelease  # release APK
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n app.baton.parking/.MainActivity
```

`adb install -r`이 서명 불일치(`INSTALL_FAILED_UPDATE_INCOMPATIBLE`)로 실패하면 기존 앱을 지우고 다시 설치한다: `adb uninstall app.baton.parking` 뒤 `adb install app/build/outputs/apk/release/app-release.apk`. release 빌드는 빌드한 컴퓨터의 debug 키로 서명하므로(아래 "APK 위치"), 다른 컴퓨터에서 빌드한 APK와는 서명이 다르다.

### 시연 순서

앱 오른쪽의 시연 패널(개발용)에서 조작한다. 패널은 맨 위 줄의 "시연 패널 닫기/열기"로 여닫는다. 역할을 바꿔도 세계(예고·대기·기록·양보 기록)는 그대로 남고, `처음부터`와 고정 시나리오만 세계를 새로 만든다.

고정 시나리오(버튼 하나로 재생)
- `정상 출차`: 탑승 → 예고 → 한 대에만 안내 → 이탈 → 상대 차 주차(양보 기록 +1)
- `짐만 싣기`: 문만 여닫고 앉지 않음 → 예고가 뜨지 않는다
- `예고 후 무이동 취소`: 예고·안내 뒤 움직이지 않음 → 예고 시작 120초에 예고와 안내가 함께 취소
- `지나침 복귀`: 안내받고 지나침 → 기다린 시간을 보존한 채 대기 복귀 → 다음 예고에서 다시 안내
- `다음 바통`: 들어오는 차가 주차를 마친 뒤 누르면, 그 차가 그 자리에서 다음 '나가는 차'가 된다

손으로 하는 나가는 차
1. `운전석 문 열림`을 켰다 끈다 → BOARDING
2. `운전석 착좌`, `안전벨트 체결`을 켠다 → ANNOUNCING (자리 예고 중)
3. `주차 브레이크 경고등`을 끄거나 `브레이크 페달`을 켠다 → DEPARTING (한 대에게 안내 시작)
4. `이동 (20m 벗어남)`을 켠다 → VACATED. 잠시 뒤 모의 상대 차가 주차하면 양보 기록이 1 오른다.
- 2~3단계에서 `시간 +30초`를 네 번 누르면 예고 시작 120초 자동 취소를 볼 수 있다.
- `위치 정확도 낮음`을 켜고 진행하면 상대 차에게 구역까지만 안내한다. `위치 신호 끊김`을 켜면 자동 예고가 꺼지고 이유가 화면에 나온다.

손으로 하는 들어오는 차
1. 역할을 `들어오는 차`로 바꾸면 구역 카드 화면(대기)이 나온다.
2. 약 6초 뒤(또는 `상대 차 출차 임박 당기기`) 우선 안내 화면으로 바뀌고 남은 초가 줄어든다.
3. 도착하면 주차 완료 화면이 나온다. `지나침 (주차 안 함)`을 누르면 대기로 돌아간다. `상대 차 예고 추가`로 예고를 더 만들 수 있다.

Pleos Connect 에뮬레이터에서 실제 차량 신호로 (Virtual Console)
- 준비: `adb forward tcp:8088 tcp:8088` 뒤 브라우저에서 `http://127.0.0.1:8088/`(Virtual Console)를 열어 둔다. 앱 맨 위 줄에 `Pleos 차량 신호`가 보이면 실제 신호를 읽는 중이다. 이때 시연 패널에는 모의 위치 스위치만 남고, 고정 시나리오 4개(정상 출차 / 짐만 싣기 / 예고 후 무이동 취소 / 지나침 복귀)는 모두 꺼진다(모의 신호 전용이며 패널에 이유가 한 줄 나온다). `다음 바통`, `처음부터`, `시간 +30초`, 역할 전환은 쓸 수 있다.
- 시작 상태: 콘솔의 Safety > Telltales에서 Parking Brake를 On으로 둔다(주차 중).
1. Door Position의 Front left를 Open → Close → BOARDING
2. Seat Occupancy의 Driver를 Occupied → ANNOUNCING (이 에뮬레이터에서는 시연 설정으로 안전벨트를 빼고 착좌만으로 판정한다. 화면에는 `시연 설정으로 벨트 제외`라고 나온다)
3. Telltales의 Parking Brake를 Off, 또는 Brake Pedal을 0보다 크게 → DEPARTING
4. 앱 시연 패널의 `이동 (20m 벗어남)`(모의 위치) → VACATED
- 콘솔의 Driver를 Occupied로 둔 채 `처음부터`를 누르면 새 주차가 되어 PARKED에 머문다. 문을 다시 여닫으면 예고가 시작된다. 같은 상태에서 앱을 껐다 켜면(처음 연결) 탑승한 상태로 복원되어 바로 예고가 시작된다.
- 콘솔 화면 대신 같은 HTTP API를 직접 불러도 된다(브라우저를 열어 두지 않으면 먼저 `POST /browser/session?client=이름`을 1초마다 보내 콘솔을 활성 상태로 둔다): `PUT /vehicle/body?door_fl=true`, `PUT /vehicle/seat?kind=occupied&value=true&area=driver`, `PUT /vehicle/telltales/simulator?color=Red&state=Off&type=ParkingBrake`, `PUT /vehicle/controls?speed=0&accelerator=0&brake=60`.

패널 아래 "관찰" 영역에 상대 차 ID, 진행 중인 안내와 끝난 안내(결과, 대기 시간 보존 여부), 세 측정값이 나온다.

## 테스트 환경과 필요한 설정

확인 환경 (2026-11-14)

| 항목 | Pleos Connect 에뮬레이터 | 일반 Android 에뮬레이터 |
|---|---|---|
| 에뮬레이터 | Pleos Connect v2.1.0 (API 34, arm64, user 빌드), 차량 서비스 `ai.pleos.playground.service.vehicle` 2.3.22 | Android Emulator, AOSP 기본 시스템 이미지 Android 14 (API 34) arm64-v8a |
| 화면 규격 | 2560×1440 가로 (앱은 홈 화면 오른쪽 분할 영역에 뜬다) | 기기 프로필 Pixel Tablet, 2560×1600 가로 |
| 차량 신호 | Pleos Vehicle SDK 실제 값 (`Pleos 차량 신호`) | 모의 (`모의 신호`) |
| 위치 · 상대 차 | 모의 | 모의 |

공통

| 항목 | 값 |
|---|---|
| 호스트 | macOS (Apple Silicon) |
| 앱 설정 | minSdk 28, compileSdk/targetSdk 35, 가로 고정, `android.hardware.type.automotive`(required=false) |
| 빌드 도구 | Gradle 8.11.1(wrapper), AGP 8.8.2, Kotlin 2.0.21, Compose BOM 2024.12.01 |
| 차량 SDK | `ai.pleos.playground:Vehicle:2.3.2` (저장소 `https://nexus-playground.pleos.ai/repository/maven-releases/`) |
| 권한 (모두 조회용) | Pleos 권한: `pleos.car.permission.CAR_DOORS`, `CAR_SEATS`, `CAR_DRIVE`, `CAR_SAFETY`, `PRIVATE_CAR_SAFETY`, 그리고 SDK가 스스로 넣는 `ai.pleos.playground.service.vehicle.VEHICLE_BINDING`. 이 에뮬레이터에서는 여섯 개 모두 보호 수준이 normal이라 설치할 때 허용된다(`pm list permissions -f`로 확인). AndroidX 내부 권한: `app.baton.parking.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`은 AndroidX가 앱 안에서만 쓰려고 넣는 것으로 보호 수준이 signature다(앱이 스스로 정의하고 스스로 쓴다). `INTERNET` 권한은 없다 |

Pleos Connect 에뮬레이터에서 확인한 것 (Virtual Console에서 값을 바꾸고 앱 화면·로그로 확인)

| 신호 | SDK API | 결과 |
|---|---|---|
| 운전석 문 | `Door.getCurrentDoorPosition` / `registerDoorPosition` (1열 왼쪽) | 콘솔의 Open/Close가 화면 "운전석 문" 줄에 반영 |
| 운전석 착좌 | `Seat.getCurrentSeatOccupancy` / `registerSeatOccupancy` (1열 왼쪽) | 반영. 단 이 조합에서는 SDK 값이 콘솔과 반대로 와서(콘솔 Occupied → SDK false) 앱이 뒤집어 읽는다. 뒤집기는 이 에뮬레이터에서만 적용한다 |
| 브레이크 페달 | `Brake.getCurrentBrakePedalStatus` / `registerBrakePedalStatus` | 콘솔 Brake Pedal 값이 0보다 크면 "페달 밟음" |
| 주차 브레이크 경고등 | `Safety.getTelltalesInfo` / `registerTelltales`의 `PARKING_BRAKE` | 콘솔 Telltales의 Parking Brake On/Off 반영. 목록에 켜진 경고등만 들어 있어 "항목 없음 = 꺼짐"으로 읽는다(이 에뮬레이터에서만 적용). `PRIVATE_CAR_SAFETY` 권한이 있어야 한다(`CAR_SAFETY`만으로는 SDK가 거부) |
| 안전벨트 | `Seat.getCurrentSeatBeltBuckledState` / `registerSeatBeltBuckledState` | 조회·구독 호출은 되지만 Virtual Console에 벨트 조작이 없어 값 변화는 확인하지 못했다. 이 에뮬레이터에서는 시연 설정으로 판정에서 빼고 착좌만으로 판정한다(벨트 기능이 없다는 뜻이 아니다) |

- 위 신호만으로 PARKED → BOARDING → ANNOUNCING → DEPARTING까지 바뀌는 것, 모의 위치 스위치로 VACATED까지 가는 것 (캡처 `docs/shots/pleos_*.png`)
- release APK 설치·실행, 앱 오류(FATAL EXCEPTION) 0건
- 새 주차와 처음 연결의 구분(release APK): 콘솔 착좌를 Occupied로 둔 채 `처음부터` → PARKED 유지, 문을 여닫은 뒤 예고 시작. 같은 상태에서 앱을 껐다 켜면 탑승한 상태로 복원되어 ANNOUNCING
- 차량 서비스 단절(debug APK, `adb shell am force-stop ai.pleos.playground.service.vehicle`로 서비스를 멈춤): 다음 확인에서 `Pleos 연결 불가`가 되고 진행 중이던 예고가 취소됨. 이후 앱이 SDK 연결을 다시 잡아 서비스가 다시 뜨고, 현재 값을 다시 읽어 복원한 뒤 문 변경이 다시 수신됨
- 검증되지 않은 환경의 동작(debug APK를 `--ez baton_unverified true`로 실행해 강제): 착좌 `일시 불가`, 자동 예고 꺼짐과 이유 표시, 경고등 `일시 불가`, 문을 여닫고 콘솔에서 착좌를 켜도 예고하지 않음

일반 Android 에뮬레이터에서 확인한 것
- 같은 release APK 설치·실행: 차량 서비스가 없어 모의 신호로 내려가고 맨 위 줄에 `모의 신호`가 표시된다
- 고정 시나리오 4개(정상 출차 / 짐만 싣기 / 예고 후 무이동 취소 / 지나침 복귀)와 `다음 바통` (차량 SDK 연결 전 빌드에서 확인)

아직 확인하지 않은 것과 모의인 것
- 실제 차량에서는 확인하지 않았다. 착좌 값의 방향, 경고등 목록이 켜진 항목만 담는지, `PRIVATE_CAR_SAFETY` 권한이 실차·심사에서 허용되는지, 안전벨트 값은 실차에서 다시 확인해야 한다. 확인하기 전까지 실차(검증되지 않은 환경)에서는 착좌를 쓰지 않으므로 자동 예고가 동작하지 않는다.
- 위치와 상대 차는 실제 차량·서버 값이 아니라 앱 안의 모의 구현이다(모의 위치, `MockPeerService`). 위치 SDK(FusedLocation)는 연결하지 않았다.
- 차량 서비스 단절은 에뮬레이터에서 서비스를 강제로 멈추는 방법으로만 확인했다. 실차에서 연결이 끊기는 다른 양상(일부 신호만 멈춤, 콜백만 멈추고 조회는 되는 경우 등)은 확인하지 않았다. 단절은 10초 간격의 확인으로 알아차리므로 그 사이에는 마지막 값을 쓴다.
- SDK 클래스를 읽지 못하는 APK(SDK 누락·버전 불일치)에서 모의 신호로 내려가는지는 시험하지 않았다(코드 경로만 있다).

알려진 제한
- 주행 중 터치 차단은 구현하지 않았다. "이번엔 예고 안 함"만 차가 움직이는 중에 꺼지고, 시연 패널(역할 전환, `처음부터`, `시간 +30초` 등)은 출차 임박·이동 중에도 눌린다.
- 지원 확인(최대 3초)과 현재 값 조회(최대 3초)의 제한 시간은 따로 센다. 첫 조회는 최대 약 6초 걸릴 수 있다.
- 구독 등록이 실패한 신호는 10초 간격의 현재 값 확인으로만 읽는다(등록은 3번까지 다시 시도).

화면은 1920×1080 시안을 기준으로 실제 화면 크기에 비율대로 맞춘다.

화면 캡처: `docs/shots/`

## APK 위치

| 종류 | 경로 |
|---|---|
| 제출용 release | `apk/개인_Baton_release.apk` (개인 참가) |
| 빌드 출력 (debug) | `app/build/outputs/apk/debug/app-debug.apk` |
| 빌드 출력 (release) | `app/build/outputs/apk/release/app-release.apk` |

제출용 APK는 `./gradlew :app:assembleRelease`의 빌드 출력을 `apk/` 폴더에 복사해 둔다(빌드 출력 경로는 저장소에 올라가지 않는다). release 빌드는 이번 단계에서 별도 키스토어를 만들지 않고 debug 서명 설정을 그대로 쓴다. 배포용 서명은 이후 단계에서 바꾼다.

## 개인정보

주차장 안 위치와 시각을 임의 식별자로 다루며, 차량 번호와 운전자 정보는 다루지 않는다. 차량에서는 운전석 문 열림·닫힘, 운전석 착좌, 운전석 안전벨트 체결, 브레이크 페달 밟음, 경고등 목록(주차 브레이크 항목만 사용)을 조회 전용으로 읽는다. 읽은 값은 메모리에서 출차 판정에만 쓰고 저장하지 않는다. release 빌드는 이 값들을 로그에 남기지 않는다(debug 빌드는 값 변경을 Logcat에 남긴다). M1은 기기 밖으로 아무것도 보내지 않는다(`INTERNET` 권한 없음).
