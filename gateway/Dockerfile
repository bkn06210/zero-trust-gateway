# ---- 1단계: 빌드 ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

COPY gradlew build.gradle settings.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon -q || true

COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test

# ---- 2단계: 실행 ----
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN useradd --system --uid 1001 --no-create-home app
# 베이스 이미지에 들어 있는 Go 바이너리. 우리 컨테이너는 java만 실행하므로 쓰지 않는다.
# Trivy가 여기서 HIGH 취약점을 잡았다. 예외로 두기보다 쓰지 않는 파일을 지워 공격면을 줄인다.
RUN rm -f /usr/bin/pebble
COPY --from=build /app/build/libs/*.jar app.jar
USER app

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
