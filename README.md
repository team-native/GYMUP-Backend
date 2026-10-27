# GymUp Backend

FastAPI + PostgreSQL로 구성한 GymUp MVP 서버입니다. 전달받은 API 계약의 38개 메서드/경로를 서버에 등록했고, 핵심 운동 흐름을 구현했습니다.

## 구성

- `app/`: FastAPI 라우터, JWT 검증, 비동기 SQLAlchemy PostgreSQL 접근
- `alembic/`: 도메인 테이블 분리 마이그레이션 (`documents`는 기존 데이터의 점진 이전을 위해 읽기 호환으로 유지)
- `compose.yaml`: PostgreSQL 16, API, 업로드 미디어 볼륨
- `openapi.json`, `spec/api-spec.tsv`: 제공된 API 계약 및 원본
- `CONTRIBUTING.md`: 팀 작업 규칙

## 로컬 실행 (PowerShell)

1. Python 3.11 이상과 Docker Desktop을 설치합니다.
2. `.env.example`을 `.env`로 복사합니다.
3. DB만 실행하려면 `docker compose up -d postgres`를 실행합니다.
4. 가상환경을 만들고 서버를 준비합니다:

   ```powershell
   py -3.11 -m venv .venv
   .\.venv\Scripts\Activate.ps1
   python -m pip install -e .
   alembic upgrade head
   uvicorn app.main:app --reload
   ```

DB와 API를 함께 컨테이너로 실행하려면 `.env` 파일을 준비하고 `docker compose up --build`를 실행합니다. API 문서는 `http://localhost:8000/docs`, DB 상태는 `GET /api/v1/health`에서 확인할 수 있습니다.

## 개발용 인증

명세에는 JWT 발급 방식이 없어 `POST /api/v1/auth/dev-token`을 개발 환경에 추가했습니다. 응답의 `accessToken`을 Swagger의 Authorize에 Bearer 토큰으로 입력하면 됩니다. 이 경로는 `GYMUP_ENVIRONMENT=development`에서만 동작합니다. 배포 환경에서는 `production`으로 바꾸고 실제 인증 제공자와 연결하세요.

## 구현 범위와 임시 규칙

- 운동 루틴, 세션, 세트, 휴식, 완료/보상, 기록 조회, 목표/성장, 원판 계산, 사진 업로드·앨범·다운로드 및 편집 작업 경로를 제공합니다.
- 사진은 로컬 파일 볼륨에 저장합니다. 필터와 스탬프는 FastAPI 프로세스의 백그라운드 작업으로 처리하며 `GET /api/v1/photos/jobs/{jobId}`로 상태를 조회합니다. 여러 서버 인스턴스 운영 전에는 내구성 있는 작업 큐와 오브젝트 스토리지로 교체해야 합니다.
- 운동 가이드 자료가 없어 가이드 조회는 `GUIDE_NOT_FOUND`, 아바타 아이템 카탈로그가 없어 빈 목록을 돌려줍니다.
- 성장/XP 계산 공식이 명세에 없어 임시로 완료 세션당 Growth 10, 세트당 XP 10, 운동 완료 XP 50을 사용합니다.
- 새 데이터는 `user_profiles`, `user_settings`, `routines`, `routine_exercises`, `workouts`, `workout_sets`, `goals`, `weekly_goals`, `rewards`, `rest_timers`, `progress_photos`, `photo_jobs`, `avatar_items`로 나뉩니다. JSONB는 루틴 스냅샷과 확장 필드에만 씁니다. 기존 `documents` 행은 읽을 때 도메인 테이블로 승격되고 이후 레거시 행은 삭제됩니다.
- 수정 요청은 행 잠금(`SELECT FOR UPDATE`)과 세트·활성 세션·활성 목표·활성 휴식의 DB 제약으로 직렬화합니다. 운동 세션에는 `version`이 있으며 완료 요청은 최신 `expectedVersion`을 보내야 합니다. 충돌과 중복 제약 위반은 409 응답으로 변환됩니다. 자세한 내용은 `docs/concurrency.md`를 참고하세요.
- 실제 서버의 `/docs`가 현재 등록된 라우트 문서입니다. 제공된 `openapi.json`은 원본 명세를 보존한 계약 파일입니다.


