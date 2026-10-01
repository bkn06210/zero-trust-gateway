# 트러블슈팅 기록

> 형식: 증상 / 처음 세운 가설 / 틀린 이유 / 실제 원인 / 해결 / 배운 것
> 실제로 겪은 것만 기록한다. 겪지 않은 문제는 지어내지 않는다.

---

## 2026-10-02 — Testcontainers 의존성을 찾지 못함 (`org.testcontainers:postgresql FAILED`)

- **증상**: build.gradle에 `testImplementation 'org.testcontainers:postgresql'`을 버전 없이 추가하고 의존성을 확인하니 `org.testcontainers:postgresql FAILED`. 테스트 컴파일 자체가 불가.
- **처음 세운 가설**: Spring Boot가 버전을 관리해주니 이름만 적으면 된다. (검색하면 나오는 예제 대부분이 이 이름을 쓴다.)
- **틀린 이유**: 버전은 Boot가 관리하는 게 맞지만, 그 버전에 그 이름의 라이브러리가 없었다. `gradlew dependencies --configuration testRuntimeClasspath`로 확인.
- **실제 원인**: Spring Boot 4가 관리하는 Testcontainers는 2.x이고, 2.x에서 모듈 이름에 `testcontainers-` 접두사가 붙었다(`testcontainers-postgresql`). 클래스 위치도 `org.testcontainers.containers.PostgreSQLContainer` → `org.testcontainers.postgresql.PostgreSQLContainer`로 바뀌었다.
- **해결**: `org.testcontainers:testcontainers-postgresql`로 변경 → 2.0.5로 해석됨. import도 새 패키지로.
- **배운 것**: 의존성이 안 잡히면 추측하지 말고 `gradlew dependencies`로 실제 해석 결과부터 본다. 메이저 버전이 바뀐 라이브러리는 인터넷 예제가 옛 이름일 수 있으니, Boot가 실제로 관리하는 버전을 먼저 확인한다.

<!--
템플릿 (실제 문제를 만나면 이 형식으로 위에 추가):

## YYYY-MM-DD — (한 줄 제목)

- **증상**: 무엇이 어떻게 잘못 동작했나 (에러 메시지 원문 포함)
- **처음 세운 가설**: 원인이 뭐라고 생각했나
- **틀린 이유**: 그 가설이 왜 틀렸나 (확인 방법 포함)
- **실제 원인**: 진짜 원인
- **해결**: 어떻게 고쳤나
- **배운 것**: 다음에 같은 부류의 문제를 어떻게 더 빨리 잡을까
-->
