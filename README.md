# GymUp Backend

GymUp MVP API는 Kotlin + Spring Boot, PostgreSQL로 실행합니다. Python은 사진 필터와 Workout Stamp 합성 작업을 처리하는 별도 워커로 유지합니다. 기존 FastAPI 서버는 이관 확인과 롤백을 위한 `legacy-api` Compose profile에 남아 있습니다.

## 구성

- `kotlin-api/`: Kotlin REST API, JWT 검증, JDBC 접근
- `app/`: 기존 FastAPI 및 Python 사진 처리 워커
- `alembic/`: 기존 DB 구조를 유지하는 스키마 마이그레이션
- `openapi.json`, `spec/api-spec.tsv`: API 계약 원본
- `docs/concurrency.md`: 행 잠금, 버전 검사, DB 제약 규칙
- `CONTRIBUTING.md`: Issue·Branch·Commit·PR 규칙

## Docker Compose 실행

1. Docker Desktop을 설치합니다.
2. `.env.example`을 `.env`로 복사합니다.
3. 서버를 실행합니다.

   ```powershell
   docker compose up --build
   ```

Kotlin API는 `http://localhost:8000`에서 응답합니다. `GET /api/v1/health`는 PostgreSQL 연결을 확인합니다. 사진 편집 요청은 PostgreSQL 작업 행으로 적재되고 `photo-worker`가 처리합니다. DB 마이그레이션은 Kotlin API 시작 전에 `migrate` 서비스가 적용합니다.

## Kotlin API 로컬 실행

- JDK 21
- Gradle 8.14 이상 또는 Gradle 9.x
- PostgreSQL 16

```powershell
$env:GYMUP_JDBC_DATABASE_URL = "jdbc:postgresql://localhost:5432/gymup"
$env:GYMUP_DATABASE_USER = "gymup"
$env:GYMUP_DATABASE_PASSWORD = "gymup"
$env:GYMUP_JWT_SECRET = "local-development-only-change-this-secret"
$env:GYMUP_ENVIRONMENT = "development"
gradle -p kotlin-api bootRun
```

로컬 실행 기본 포트는 `8001`이며 `GYMUP_KOTLIN_PORT`로 바꿀 수 있습니다. Kotlin 서버는 기존 Alembic 스키마를 사용하고 자체적으로 테이블을 변경하지 않습니다.

## 개발용 인증

`GYMUP_ENVIRONMENT=development`에서 `POST /api/v1/auth/dev-token`으로 테스트용 JWT를 발급받습니다. 응답의 `accessToken`을 API의 Bearer 토큰으로 사용합니다. 이 경로는 운영 환경에서 404를 반환합니다.

## 기존 FastAPI 서버

기존 서버를 별도 포트로 실행하려면 다음을 사용합니다.

```powershell
docker compose --profile legacy-api up --build api
```

기존 API는 호스트 포트 `8002`로 열립니다. Kotlin API와 동시에 실행해 동작을 비교할 수 있습니다.

## 동작 범위와 임시 정책

- API는 사용자 설정, 루틴, 운동 세션·세트·휴식, 보상, 기록·통계, 목표·아바타, 사진 업로드·앨범·편집 작업 경로를 제공합니다.
- 사진은 공유 Docker volume에 저장합니다. Kotlin API는 작업을 `photo_jobs`에 기록하고 Python 워커가 `FOR UPDATE SKIP LOCKED`로 하나씩 가져갑니다.
- 운동 가이드 데이터가 없어 가이드 조회는 `GUIDE_NOT_FOUND`, 아바타 아이템 카탈로그가 없어 빈 목록을 반환합니다.
- 성장·XP 공식은 명세에 없어 완료 세션당 Growth 10, 세트당 XP 10, 운동 완료 XP 50을 사용합니다.
- 레거시 `documents` 행은 Kotlin에서 읽고 쓸 때 도메인 테이블로 승격합니다. 새 쓰기는 정규화된 테이블과 하위 행을 사용합니다.
- 수정 요청은 DB 행 잠금과 유일 제약으로 직렬화하며, 운동 완료는 최신 `expectedVersion`을 요구합니다. 중복 제약 충돌은 409로 반환합니다.
- `openapi.json`은 전달된 계약 원본입니다. 코드 이관 후 계약 비교와 서버 실행 검증이 필요합니다.


