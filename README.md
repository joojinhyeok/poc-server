# CryptoTax APIs

암호화폐 세금 계산 플랫폼의 백엔드 API 서버입니다.

## 기술 스택

- Java 21 (LTS)
- Spring Boot 3.5.11
- Gradle (Kotlin DSL)
- PostgreSQL 16
- Spring Security + JWT 인증
- Springdoc OpenAPI (Swagger UI)

## 로컬 실행 방법

### 1. PostgreSQL 실행

```bash
docker-compose up -d
```

### 2. 서버 시작

```bash
./gradlew bootRun
```

### 3. 동작 확인

- Health Check: http://localhost:8080/api/health
- Swagger UI: http://localhost:8080/swagger-ui.html

## 프로젝트 구조

```
src/main/java/com/danalfintech/cryptotax/
├── global/          # 공통 설정 (Security, JWT, 에러 처리, Base Entity)
├── portfolio/       # 포트폴리오 모듈
├── tax/             # 세금 계산 모듈
├── explanation/     # 설명 모듈
└── api/             # 외부 API, Health Check
```

## 주요 API

| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/health` | 서버 상태 확인 |
