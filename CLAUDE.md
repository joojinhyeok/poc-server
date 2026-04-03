# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 프로젝트 개요

암호화폐 세금 계산 플랫폼 백엔드 API 서버 (다날핀테크 인턴 POC)

- **기술 스택**: Java 21, Spring Boot 3.5.11, Gradle Kotlin DSL
- **데이터베이스**: PostgreSQL 16 (Docker, 포트 5432)
- **메시지 큐**: RabbitMQ 3 (Docker, 포트 5672, 관리 UI 15672)
- **캐시/동시성**: Redis 7 (Docker, 포트 6379)
- **인증**: Stateless JWT (jjwt 0.12.6) + Spring Security, BCrypt
- **API 문서**: Springdoc OpenAPI / Swagger UI (`/swagger-ui.html`)
- **Virtual Threads**: 활성화됨

## 주요 명령어

```bash
# 인프라 실행 (PostgreSQL, Redis, RabbitMQ)
docker-compose up -d

# 서버 실행 (local 프로필, 포트 8080)
./gradlew bootRun

# 전체 테스트
./gradlew test

# 단일 테스트 클래스 실행
./gradlew test --tests "com.danalfintech.cryptotax.SomeTest"

# 테스트 제외 빌드
./gradlew build -x test
```

## 아키텍처

베이스 패키지: `com.danalfintech.cryptotax`

### 도메인 모듈 구조

각 비즈니스 도메인(`portfolio`, `tax`, `explanation`, `collection`, `exchange`)은 `controller/`, `service/`, `domain/`, `dto/` 계층 구조를 따른다. `api/` 모듈은 Health Check 등 외부 공개 엔드포인트를 담당한다.

### 글로벌 인프라 (`global/`)

- **`config/`** — SecurityConfig (Stateless, CORS `localhost:5173`), JwtConfig, JpaConfig (auditing), RabbitConfig, RedisConfig, ExchangeProperties
- **`security/`** — JwtProvider (access/refresh 토큰, HMAC-SHA), JwtAuthenticationFilter, CustomUserDetails
- **`error/`** — GlobalExceptionHandler (`@RestControllerAdvice`), BusinessException + ErrorCode enum 패턴
- **`common/`** — BaseTimeEntity (`createdAt`/`updatedAt`, JPA auditing) — 모든 엔티티는 이것을 상속. RedisKeyBuilder (모든 Redis 키 중앙 생성)
- **`infra/exchange/`** — ExchangeConnector/ExchangeCollector 인터페이스 (거래소 공통 추상화), ExchangeContext, RestClientFactory
- **`infra/redis/`** — 분산 Rate Limiter (FixedWindow/WeightBudget 정책), Lease 기반 동시성 제어, Lua 스크립트(`resources/scripts/`)

### 비동기 수집 파이프라인 (핵심 흐름)

```
CollectionService (Job 생성, afterCommit 메시지 발행)
  → RabbitMQ [queue.collection.high / low]
    → CollectionProcessor (Lease 획득 + Virtual Thread 하트비트)
      → CollectionFacade (심볼 목록 조회 → N개 메시지 분해 발행)
        → RabbitMQ [queue.collection.symbol]
          → SymbolCollectionWorker × 10 (심볼별 독립 트랜잭션)
            → ExchangeCollector.collectSymbol() → ExchangeConnector (Rate Limiter → API 호출)
              → PostgreSQL (거래 내역 저장 + SyncCursor 갱신)
```

**트랜잭션 분리 설계**: Job 하나를 심볼 단위 메시지로 분해하여 각각 독립 트랜잭션으로 처리. DB 커넥션 장시간 점유 방지 + 부분 실패 허용 (COMPLETED / PARTIAL / FAILED 3단계 상태).

**진행률 추적**: `CollectionJobRepository`의 `RETURNING` 네이티브 쿼리로 원자적 증가 (`incrementProgressAndGet`, `incrementFailureAndGet`). 10개 워커 동시 실행 시 lost update 방지.

**실패 관리**: `CollectionFailure` 테이블에 (jobId, symbol, reason, retried) 기록. Job 테이블에는 `failedSymbols` 카운터만 유지. 재시도 시 `findAllByJobIdAndRetriedFalse()`로 실패 심볼만 조회.

### RabbitMQ 토폴로지

```
exchange.collection (Direct)
  ├─ routing.high   → queue.collection.high   (18 concurrent, INCREMENTAL용)
  ├─ routing.low    → queue.collection.low    (6 concurrent, FULL용)
  └─ routing.symbol → queue.collection.symbol (10 concurrent, 심볼별 처리)

exchange.collection.dlq (Direct)
  └─ routing.dlq    → queue.collection.dlq    (Dead Letter Queue)
```

Manual ACK, Prefetch 1. NACK 시 DLQ로 이동.

### Redis 인프라

- **Rate Limiter**: Lua 스크립트로 원자적 카운팅. `FixedWindowPolicy`(초당 N회) / `WeightBudgetPolicy`(가중치 예산). `RateLimiterRegistry`가 `ExchangeProperties` 설정 기반으로 거래소별 정책 자동 매핑. Redis 장애 시 Fail-Open.
- **Lease Manager**: Redis Hash (`exchange:leases:{exchange}`)에 workerId → expirationTimestamp 저장. Lua 스크립트로 원자적 acquire/release. Virtual Thread 30초 하트비트 → 60초 TTL 갱신. 10초 주기 만료 Lease 정리.
- **Progress**: `ProgressData` JSON을 Redis에 저장 (2시간 TTL). 프론트엔드 실시간 진행률 표시용.

### 거래소 추상화 (2단)

- **ExchangeConnector**: 거래소 API 통신 전담 (`getBalances`, `getTrades`, `getMarkets`, `verify`)
- **ExchangeCollector**: 수집 비즈니스 로직 (`collectAll`, `collectIncremental`, `collectSymbol`)
- **ExchangeContext**: `(exchange, serverIp)` record. v1에서 serverIp는 null, v2에서 IP 분산 시 활성화. Rate Limiter/Lease/Redis 키 모두 이 객체 기반.

거래소 추가 시: 두 인터페이스 구현체 작성 + `application.yml`에 rate-limit/max-concurrent 설정 추가.

### 핵심 패턴

- **에러 처리**: `BusinessException(ErrorCode.XXX)` throw. 새 에러 코드는 `ErrorCode` enum에 HttpStatus, 코드 문자열, 한글 메시지와 함께 추가
- **보안**: `/auth/**`, `/api/health`, Swagger 경로는 공개. 나머지는 JWT Bearer 토큰 필수
- **프로필**: `application.yml`(공통) + `application-local.yml`(개발). `application-test.yml`(H2 인메모리, RabbitMQ/Redis 비활성)
- **DB 마이그레이션**: Flyway (`db/migration/` V1~V4). local에서는 `ddl-auto: validate`, 운영도 동일

### 주요 설정 (`application.yml`)

거래소별 동시성/Rate Limit 설정이 `app.exchange.*`에 정의됨:
- `max-concurrent`: 거래소별 동시 워커 수 (UPBIT: 3, BINANCE: 5 등)
- `rate-limit`: 거래소별 정책 타입/한도/윈도우 (UPBIT: FIXED_WINDOW 8/1sec, BINANCE: WEIGHT_BUDGET 5000/60sec 등)
