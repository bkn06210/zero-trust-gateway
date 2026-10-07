# Zero-Trust API Gateway

[![CI](https://github.com/bkn06210/zero-trust-gateway/actions/workflows/ci.yml/badge.svg)](https://github.com/bkn06210/zero-trust-gateway/actions/workflows/ci.yml)

모든 요청을 입구에서 검증하는 API Gateway와 인증 서비스를 Spring Boot로 직접 구현했습니다.
Spring Cloud Gateway나 Spring Security의 인증 기능을 쓰지 않고, 토큰 검증 필터·권한 검사·요청 제한·리버스 프록시를 손으로 만들었습니다.
"왜 이렇게 했는가"와 "무엇을 포기했는가"를 [설계 결정 기록](docs/DECISIONS.md)에, 실제로 겪은 문제와 해결 과정을 [트러블슈팅 기록](docs/TROUBLES.md)에 남겼습니다.

## 구조

```
클라이언트 ──▶ gateway :8080 ──▶ auth-service :8081 ──▶ PostgreSQL
                 │  1. 로그인 요청 제한 (Redis)              │
                 │  2. JWT 검증 → 401                     │
                 │  3. 역할 검사 → 403                     │
                 │  4. 신원 헤더 붙여 전달                   │
                 └──▶ Redis
```

바깥으로 열린 포트는 gateway의 8080 하나입니다. auth-service, PostgreSQL, Redis는 컨테이너 내부 네트워크에서만 통합니다.

| 서비스 | 역할 |
|---|---|
| **gateway** | 로그인 요청 제한, JWT 검증, 역할 기반 접근 제어, 뒤쪽 서비스로 요청 전달 |
| **auth-service** | 회원가입, 로그인, access/refresh 토큰 발급·회전·폐기, 관리자 API |

## 기술

Java 21 · Spring Boot 4 · Spring Data JPA · PostgreSQL 16 · Redis 7 · JWT(jjwt) · BCrypt · Docker Compose · JUnit 5 · Testcontainers · GitHub Actions · Semgrep · gitleaks · Trivy

## 구현한 것

### 인증
- 비밀번호는 BCrypt 해시로만 저장
- 로그인 실패 시 "이메일 없음"과 "비밀번호 틀림"을 구분하지 않고, 응답 시간 차이로도 구분되지 않도록 없는 이메일에도 같은 해시 연산을 수행
- access token은 JWT로 1시간. 사용자 id, 역할, 만료, 고유 번호만 담고 개인정보는 넣지 않음
- refresh token은 32바이트 난수. 서버에는 SHA-256 해시만 저장하고, 쓸 때마다 폐기 후 재발급. 폐기된 토큰이 다시 오면 탈취로 보고 해당 사용자의 토큰을 전부 폐기

### Gateway
- 토큰 없이 열어줄 경로만 "메서드 + 경로" 정확 일치로 적는 허용 목록. 기본은 전부 차단
- 서명·만료·필수 클레임 검증. 서명을 비운 토큰(alg=none), 다른 키로 서명한 토큰, 내용을 바꾼 토큰 모두 차단
- 클라이언트가 직접 쓴 신원 헤더는 버리고, 검증된 값으로 `X-User-Id`, `X-User-Role`을 다시 써서 전달
- `/admin/` 아래는 ADMIN만. 인증 실패는 401, 권한 없음은 403
- 로그인 요청 제한: 1분 창에서 (이메일+IP) 5회, (이메일) 20회. Redis Lua 스크립트로 INCR과 EXPIRE를 원자적으로 실행. 로그인 성공 시 카운터 삭제
- 뒤쪽 서비스 호출에 시간 제한. 연결 실패 시 502

### 검증
- 통합 테스트 34건. 테스트용 DB와 Redis는 Testcontainers로 실제 컨테이너를 띄워 개발 환경과 분리
- gateway 테스트는 가짜 뒤쪽 서버를 두어 "무엇을 전달했는지", "차단 시 정말 전달하지 않았는지"까지 확인
- 동시 요청 테스트: 같은 이메일 가입 50건 동시, 같은 refresh 토큰 갱신 20건 동시

### CI 보안 게이트
push마다 자동으로 돌고, 하나라도 실패하면 커밋에 빨간 X가 붙습니다.

| 검사 | 보는 것 |
|---|---|
| 테스트 | 기능이 깨졌는가 |
| Semgrep | 코드에 위험한 패턴이 있는가 |
| gitleaks | 커밋 이력에 비밀값이 들어갔는가 |
| Trivy | 이미지 안 라이브러리에 알려진 취약점이 있는가 |

액션은 태그가 아니라 커밋 SHA로 고정했습니다.

## 실제로 겪고 해결한 문제

**동시에 갱신하면 "성공" 응답으로 이미 폐기된 refresh 토큰이 나갔다.**
동시 요청 테스트를 돌려보니 응답은 200과 401로 정상인데, 직후 DB의 유효한 토큰이 0개였다. 늦게 처리된 요청이 "이미 폐기된 토큰"을 탈취로 판정해 먼저 성공한 요청의 새 토큰까지 폐기한 것. 브라우저 탭 두 개 수준인 동시 2건에서도 매번 재현됐다. 폐기 시각을 기록하고 10초 유예를 두어 해결. 유예 안의 재사용에도 새 토큰은 주지 않는다. [자세히](docs/TROUBLES.md)

**CI 이미지 스캔이 CRITICAL 4건을 잡았는데, 최신 Spring Boot로 올려도 안 고쳐졌다.**
Tomcat 인증 우회 3건 등 14건. Boot 최신 패치의 의존성 목록을 확인하니 고쳐진 Tomcat 버전을 아직 쓰지 않고 있었다. Boot가 관리하는 버전을 개별로 덮어써서 해결. [자세히](docs/TROUBLES.md)

## 실행

Docker가 필요합니다.

```bash
cp .env.example .env        # 값을 채운다. JWT_SECRET은 32바이트 이상
docker compose up -d --build
```

시연 스크립트로 동작을 확인할 수 있습니다.

```bash
bash scripts/p1-demo.sh     # 토큰 없음 401 / 위조 401 / 정상 200 / 신원 헤더 위조 무시
bash scripts/p2-demo.sh     # USER가 관리자 API → 403, ADMIN → 200
bash scripts/p3-demo.sh     # 틀린 비밀번호 6번째부터 429, Redis 카운터 확인
bash scripts/p4-2-demo.sh   # 가짜 키와 SQL 인젝션을 넣은 커밋을 gitleaks와 Semgrep이 잡는지
```

테스트는 서비스 폴더에서 실행합니다.

```bash
cd auth-service && ./gradlew test
cd gateway && ./gradlew test
```

## 알려진 한계와 다음 단계

- **전 구간 평문 HTTP.** 토큰이 평문으로 지나간다. 배포 시 TLS 종료가 필요하다.
- **HS256 대칭키.** 발급과 검증이 같은 키를 공유한다. RS256 공개키 방식으로 바꾸면 Gateway에 비밀키를 두지 않아도 된다.
- **뒤쪽 서비스가 `X-User-*` 헤더를 믿는다.** 네트워크로 직접 접근을 막았지만, 내부 네트워크가 뚫리면 사칭이 가능하다. 서비스 간 인증으로 보완할 수 있다.
- **access token은 만료 전 즉시 무효화가 안 된다.** 짧은 만료로 완화했고, `jti`로 블랙리스트를 붙일 수 있다.
- **IP를 `getRemoteAddr()`로 읽는다.** 로드밸런서 뒤에서는 전부 같은 IP로 보인다. 신뢰하는 프록시의 `X-Forwarded-For` 처리가 필요하다.
- **다음**: Terraform으로 AWS에 배포하고 CI 마지막에 자동 배포 연결. 구조화 로깅과 메트릭.
