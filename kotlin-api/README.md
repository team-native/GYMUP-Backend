# GymUp Kotlin API

GymUp의 `/api/v1` API 계약을 제공하는 Kotlin + Spring Boot 서버입니다. 기존 PostgreSQL 테이블과 Alembic 마이그레이션을 그대로 사용하고, Python은 사진 필터 및 스탬프 편집 워커를 처리합니다.

## 포함 범위

- 사용자 온보딩 및 설정, 루틴 CRUD 일부
- 운동 세션·세트·기록·통계, 휴식, 목표·성장·보상
- 운동 가이드 조회, 원판 계산
- 사진 업로드·앨범·다운로드·편집 작업 API
- JWT Bearer 인증 및 개발용 JWT 발급

## 실행

저장소 루트에서 `.env`를 준비한 후 `docker compose up --build`를 실행합니다. Compose는 DB 마이그레이션을 적용하고 Kotlin API와 Python `photo-worker`를 시작합니다. 호스트 포트 `8000`에서 API에 접근할 수 있습니다.

Kotlin API만 로컬에서 실행하려면 JDK 21과 Gradle 8.14 이상 또는 9.x, 실행 중인 PostgreSQL 16이 필요합니다.

```powershell
$env:GYMUP_JDBC_DATABASE_URL = "jdbc:postgresql://localhost:5432/gymup"
$env:GYMUP_DATABASE_USER = "gymup"
$env:GYMUP_DATABASE_PASSWORD = "gymup"
$env:GYMUP_JWT_SECRET = "local-development-only-change-this-secret"
$env:GYMUP_ENVIRONMENT = "development"
gradle bootRun
```

직접 실행할 때 기본 포트는 `8001`입니다. `GET /api/v1/health`는 `SELECT 1`로 DB 연결을 확인합니다.

## 호환성과 작업 처리

- 계약은 저장소 루트의 `openapi.json`과 `spec/api-spec.tsv`를 기준으로 합니다.
- Kotlin 저장소는 기존 도메인 테이블과 JSONB 확장 데이터를 읽고 씁니다. 레거시 `documents` 데이터는 읽거나 수정할 때 도메인 테이블로 승격합니다.
- 사진 편집 API는 `photo_jobs`에 작업을 적재합니다. Python 워커가 PostgreSQL 잠금으로 작업을 가져와 기존 Pillow 로직으로 처리합니다.
- FastAPI 전체 서버는 Compose의 `legacy-api` profile에서만 실행하도록 남겼습니다.
