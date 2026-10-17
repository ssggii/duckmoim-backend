<h1>덕모임</h1>

서울의 콘서트·팝업·생일카페 정보를 모아 보여주고, 행사마다 함께 갈 사람을 모집하는 동행 매칭 서비스 [**덕모임**](https://duckmoim.com)입니다.

> 이 저장소는 팀 프로젝트 원본 [potenup-final/duckmoim-backend](https://github.com/potenup-final/duckmoim-backend)를 fork하여, <br>
> 프로젝트 종료 후 안정성 개선을 개인적으로 이어 간 저장소입니다. 개선 작업은 [아래](#개선-작업-fork)에 있습니다.

## 주요 기능

<p>
  <img src="docs/assets/screen-home.png" width="150" alt="홈">
  <img src="docs/assets/screen-event.png" width="150" alt="행사 상세">
  <img src="docs/assets/screen-companion.png" width="150" alt="동행 목록">
  <img src="docs/assets/screen-post.png" width="150" alt="모집글">
  <img src="docs/assets/screen-chat.png" width="170" alt="채팅">
</p>

| 기능        | 내용                                       | 배포      |
|-----------|------------------------------------------|---------|
| 행사 탐색     | 서울 콘서트·팝업·생일카페를 종류, 지역, 기간, 키워드로 찾기      | 1차      |
| 동행 모집글    | 행사를 골라 만남 시각과 지도 핀으로 동행 모집, 만남 임박순 목록    | 1차      |
| 댓글과 비밀 댓글 | 대댓글과 비밀 댓글. 비밀 댓글 본문은 작성자와 방장에게만 응답      | 1차      |
| 회원        | 카카오 로그인, 만 14세 미만 가입 제한, 프로필과 내 활동       | 1차      |
| 신고와 제재    | 회원·글·댓글·채팅 신고, 백오피스 처리와 제재, 관리자 행위 감사 로그 | 1차 · 2차 |
| 채팅        | 모집글마다 채팅방, SSE 실시간 수신과 끊긴 구간 재전송, 사진 보호  | 2차      |
| 알림        | 댓글·답글·채팅 알림을 알림함과 웹 푸시로, 종류별 수신 설정       | 2차      |

## 담당 역할 및 성과

백엔드 3인 중 **알림 파이프라인**, **신고·제재 백오피스**, **요구사항·설계 문서**와 **AI 개발 워크플로우**를 맡았습니다.

### 알림 파이프라인

- 댓글 작성과 알림 발송이 따로 처리되는 이중 쓰기 문제를 Transactional Outbox 패턴으로 해결해, 알림 채널이 실패해도 댓글 작성이 실패하지 않도록 분리
- 워커 2대가 같은 아웃박스 행을 집는 문제를 `FOR UPDATE SKIP LOCKED` 기반 리스 선점으로 해결해 충돌 168건 → 0건, 200건 처리 983ms → 512ms
- 실패한 알림은 최대 3회 시도 후 DLQ로 격리하고, 인앱 알림과 웹 푸시를 트랜잭션 단계로 나눠 푸시가 실패해도 알림함은 남도록 설계
- 채팅 알림은 지금 그 방을 보고 있는 멤버를 Redis 접속 상태로 판정해 만들지 않도록 처리
- 30일이 지난 알림을 지우는 만료 배치는 두 인스턴스가 함께 돌아도 결과가 같도록 잠금 없이 멱등으로 설계

### 신고·제재 백오피스

- 오프라인 만남의 안전을 위해 신고 접수·처리와 제재 백오피스를 구현
- 관리자의 제재·해제·비밀 댓글 열람을 수정하거나 삭제할 수 없는 감사 로그로 기록

### 요구사항·설계 문서

- 1·2차 요구사항 106개와 불변식을 ID로 정리하고, 설계 결정을 ADR 7건으로 기록
- 설계 문서 정합성을 점검해 불일치 92건을 정정하고, 2차 제품 결정 안건 23건을 회의 한 번에 확정

### AI 개발 워크플로우와 품질 게이트

- AI 에이전트가 요구사항 ID로 위키를 조회해 테스트 목록을 뽑고 구현하며, 빌드 게이트에 실패하면 스스로 고치는 워크플로우 구축
- 강제력이 없던 위키 컨벤션 944줄을 Checkstyle·ArchUnit 빌드 게이트로 옮겨, 규칙을 어기면 빌드가 깨지도록 구성
- Docker 기반 Testcontainers로 통합 테스트 환경을 구성해 동시성과 트랜잭션 경계를 실제 MySQL에서 검증

## 개선 작업 (fork)

팀 프로젝트가 끝난 뒤 코드에서 찾은 문제를 로컬에서 재현하고, 개선 전후를 측정했습니다. 작업마다 이슈에 문제와 원인을, PR에 해결 과정과 측정 결과를 남겼습니다.

| 문제                                                                              | 해결                                                      | 결과                                                | 기록                                                                                                                        |
|---------------------------------------------------------------------------------|---------------------------------------------------------|---------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------|
| 푸시 장애로 DLQ에 쌓인 알림을 장애가 끝난 뒤에도 재발송할 수 없음. DLQ로 옮길 때 원본 아웃박스 행을 지우는데 워커는 아웃박스만 조회 | 운영자가 장애 구간을 지정하면 DLQ 알림을 원래 ID로 아웃박스에 재등록해 기존 워커가 다시 발송 | 미발송 푸시 0 / 1,100건 → 1,100 / 1,100건 재발송, 알림함 중복 0건 | [이슈 #1](https://github.com/ssggii/duckmoim-backend/issues/1) · [PR #2](https://github.com/ssggii/duckmoim-backend/pull/2) |

### 측정 방식

- 환경: Testcontainers MySQL 8.4, 실제 발송 워커에 가짜 푸시 발송기를 꽂아 장애를 재현
- 규모: 서비스가 자리 잡은 상황을 가정해 피크 분당 푸시 18건으로 잡고, 푸시 서비스 30분 장애(430건)와 잘못된 푸시 설정 1시간 배포(1,100건)를 재현
- 비교: 개선 전후의 차이는 재처리 호출 하나뿐이고 건수·발송기·워커는 같은 조건

### 재현 방법

| 폴더                                                 | 내용          |
|----------------------------------------------------|-------------|
| [measure/C-dlq-reprocess](measure/C-dlq-reprocess) | 개선 전후 측정 결과 |

```bash
MEASURE=true MEASURE_LABEL=after ./gradlew test --tests 'com.duckmoim.notification.service.NotificationDlqRecoveryMeasureTest' --rerun
```

측정 테스트는 `MEASURE=true` 일 때만 돌고 평소 빌드에는 들어가지 않습니다.

## PoC

주제를 정하기 전에 후보 셋을 실제로 만들어 주말 하루씩 열어 뒀습니다.

<p>
  <img src="docs/assets/poc-birthday-cafe.png" width="200" alt="생일카페 정보">
  <img src="docs/assets/poc-album-benefits.png" width="300" alt="앨범 특전 정보">
  <img src="docs/assets/poc-comeback.png" width="280" alt="컴백 카운트다운">
</p>

| 후보       | 하루 방문 | 체류    |
|----------|-------|-------|
| 생일카페 정보  | 155   | 약 2분  |
| 앨범 특전 정보 | 31    | 1분 미만 |
| 컴백 카운트다운 | 23    | 1분 미만 |

<p>
  <img src="docs/assets/poc-metrics.png" width="640" alt="생일카페 정보 PoC 지표">
</p>

방문 5배, 체류 2배 차이로 생일카페와 팝업을 축으로 정했습니다.

## 팀원

<table>
  <tr>
    <td align="center"><a href="https://github.com/ssggii"><img src="https://github.com/ssggii.png?size=100" width="100" alt=""><br><b>한슬기</b></a><br>백엔드</td>
    <td align="center"><a href="https://github.com/J-DONGHYUN"><img src="https://github.com/J-DONGHYUN.png?size=100" width="100" alt=""><br><b>장동현</b></a><br>백엔드</td>
    <td align="center"><a href="https://github.com/Aru428"><img src="https://github.com/Aru428.png?size=100" width="100" alt=""><br><b>권소희</b></a><br>백엔드</td>
    <td align="center"><a href="https://github.com/parkhb1181"><img src="https://github.com/parkhb1181.png?size=100" width="100" alt=""><br><b>박호빈</b></a><br>프론트엔드 · 기획</td>
  </tr>
</table>

팀 너 스타야? · 2026.08.24 ~ 09.17 · 주 단위 스프린트 4회

## 시스템 아키텍처

<p>
  <img src="docs/assets/architecture.png" alt="시스템 아키텍처" width="900">
</p>

## 도메인 구조

| 컨텍스트         | 등급 | 패키지                | 맡는 것           | 요구사항 ID | 마이그레이션 |
|--------------|----|--------------------|----------------|---------|--------|
| Identity     | 핵심 | `identity`, `auth` | 로그인, 토큰, 프로필   | AU      | V10 ~  |
| Companion    | 핵심 | `companion`        | 모집글, 댓글        | PO, CM  | V20 ~  |
| Chat         | 핵심 | `chat`             | 채팅방, 메시지, 사진   | CH      | V700 ~ |
| Catalog      | 지원 | `catalog`          | 행사, 지역         | EV      | V1 ~   |
| Safety       | 지원 | `safety`           | 신고, 제재, 감사 기록  | SF      | V30 ~  |
| Notification | 지원 | `notification`     | 알림, 아웃박스, 웹 푸시 | NT      | V800 ~ |
| Admin        | 일반 | `admin`            | 백오피스 인가        | AD      | V30 ~  |

## 품질 게이트

규칙을 문서에서 빌드로 옮겼습니다. 어기면 빌드가 깨지고 머지가 안 됩니다.

| 게이트        | 막는 것                               |
|------------|------------------------------------|
| Spotless   | 포맷                                 |
| Checkstyle | 로그에 남기면 안 되는 값 (본문, 닉네임, 출생연도, 토큰) |
| ArchUnit   | 레이어 의존 방향, 트랜잭션 위치                 |
| 위키 참조 검증   | 코드가 인용한 위키 경로가 실제로 있는지             |

- `develop`은 빌드만, `main`은 ECR 푸시 후 블루/그린 배포
- 운영 Swagger는 닫고, 스펙을 CI에서 뽑아 [정적 페이지](https://potenup-final.github.io/duckmoim-backend/)로 배포

## AI 협업

잘 부탁하는 법 대신, 다시 설명하지 않아도 되게 저장소를 설정했습니다. \
`CLAUDE.md`, 커밋 훅, 위키 참조가 그 자리입니다.

```
[0] 티켓 제목에서 요구사항 ID 를 읽는다
[1] ID → 위키 → 통과해야 하는 테스트 목록
[2] 계획을 이슈에 올린다            ← 사람이 본다
[3] 계획 한 줄 = 커밋 하나          ← 사람이 본다
[4] ./gradlew check  실패하면 [3] 으로
[5] PR
```

## 프로젝트 구조

```
src/main/java/com/duckmoim
├── identity      회원, 프로필
├── auth          카카오 로그인, 토큰
├── catalog       행사, 지역
├── companion     모집글, 댓글
├── chat          채팅방, 메시지, 사진, SSE
├── safety        신고, 제재, 감사 기록
├── notification  알림, 아웃박스, 웹 푸시
├── admin         백오피스 인가
└── common        공통
src/main/resources/db/migration   Flyway
docs/wiki                         요구사항, 불변식, ADR (서브모듈)
docs/harness/backend.md           AI 작업 루프
config/checkstyle/                로그 금지 값 규칙
.github/workflows/                CI/CD, API 문서, Jira 연동
```

## 시작하기

```bash
git clone --recurse-submodules https://github.com/ssggii/duckmoim-backend.git
cd duckmoim-backend
cp .env.example .env
docker compose up -d          # MySQL 8.4, Redis
./gradlew bootRun             # localhost:8080
```

```bash
./gradlew build               # 포맷, 컨벤션, 아키텍처, 테스트. CI 와 같다
./gradlew spotlessApply       # 포맷 자동 수정
```

## 기술 스택

<p>
  <img src="https://img.shields.io/badge/Java%2017-007396?logo=openjdk&logoColor=white">
  <img src="https://img.shields.io/badge/Spring%20Boot%203.5-6DB33F?logo=springboot&logoColor=white">
  <img src="https://img.shields.io/badge/Spring%20Security-6DB33F?logo=springsecurity&logoColor=white">
  <img src="https://img.shields.io/badge/JPA%20%2F%20Hibernate-59666C?logo=hibernate&logoColor=white">
  <img src="https://img.shields.io/badge/Flyway-CC0200?logo=flyway&logoColor=white">
  <img src="https://img.shields.io/badge/Gradle%209-02303A?logo=gradle&logoColor=white">
</p>
<p>
  <img src="https://img.shields.io/badge/MySQL%208.4-4479A1?logo=mysql&logoColor=white">
  <img src="https://img.shields.io/badge/Redis-DC382D?logo=redis&logoColor=white">
  <img src="https://img.shields.io/badge/Amazon%20S3-569A31?logo=amazons3&logoColor=white">
</p>
<p>
  <img src="https://img.shields.io/badge/AWS%20ALB%20%2F%20EC2-FF9900?logo=amazonaws&logoColor=white">
  <img src="https://img.shields.io/badge/Amazon%20ECR-FF9900?logo=amazonaws&logoColor=white">
  <img src="https://img.shields.io/badge/GitHub%20Actions-2088FF?logo=githubactions&logoColor=white">
  <img src="https://img.shields.io/badge/Docker-2496ED?logo=docker&logoColor=white">
  <img src="https://img.shields.io/badge/Grafana-F46800?logo=grafana&logoColor=white">
  <img src="https://img.shields.io/badge/Metabase-509EE3?logo=metabase&logoColor=white">
</p>
<p>
  <img src="https://img.shields.io/badge/Spotless-000000">
  <img src="https://img.shields.io/badge/Checkstyle-000000">
  <img src="https://img.shields.io/badge/ArchUnit-000000">
  <img src="https://img.shields.io/badge/JUnit%205-25A162?logo=junit5&logoColor=white">
</p>

## 문서

- [위키](https://github.com/potenup-final/duckmoim-wiki) 요구사항 ID, 불변식, ADR, 회의록
- [API 문서](https://potenup-final.github.io/duckmoim-backend/) CI가 추출한 OpenAPI 스펙
- [AI 작업 루프](docs/harness/backend.md)
- [프론트엔드 저장소](https://github.com/potenup-final/duckmoim-frontend)
