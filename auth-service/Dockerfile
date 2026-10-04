# ---- 1단계: 빌드. JDK와 Gradle로 jar를 만든다. 이 단계의 내용물은 최종 이미지에 남지 않는다. ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# 의존성 목록을 먼저 복사해 받아 둔다. 소스만 바뀌면 이 층은 캐시되어 다시 내려받지 않는다.
COPY gradlew build.gradle settings.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon -q || true

COPY src ./src
# 테스트는 CI에서 따로 돈다. 이미지 빌드에서는 jar만 만든다.
RUN ./gradlew bootJar --no-daemon -x test

# ---- 2단계: 실행. JRE만 있는 작은 이미지에 jar 하나만 담는다. ----
FROM eclipse-temurin:21-jre
WORKDIR /app

# root로 실행하지 않는다. 컨테이너가 뚫려도 안에서 할 수 있는 일이 줄어든다.
RUN useradd --system --uid 1001 --no-create-home app
COPY --from=build /app/build/libs/*.jar app.jar
USER app

EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
