# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:25-jdk-alpine AS build

WORKDIR /workspace

ARG GITHUB_USER

COPY gradlew gradle.properties settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY buildSrc ./buildSrc
COPY buildsystem-api ./buildsystem-api
COPY buildsystem-core ./buildsystem-core
COPY buildsystem-grounds ./buildsystem-grounds

RUN --mount=type=cache,target=/root/.gradle \
    --mount=type=secret,id=github_token,required=true \
    --mount=type=secret,id=gradle_init,target=/root/.gradle/init.d/grounds-maven-mirror.init.gradle.kts,required=false \
    GITHUB_ACTOR="$GITHUB_USER" GITHUB_TOKEN="$(cat /run/secrets/github_token)" \
    ./gradlew --no-daemon clean :buildsystem-core:shadowJar :buildsystem-grounds:shadowJar && \
    test "$(find build/libs -maxdepth 1 -type f -name 'BuildSystem-*.jar' | wc -l)" -eq 1 && \
    test "$(find build/libs -maxdepth 1 -type f -name 'GroundsMaps-*.jar' | wc -l)" -eq 1

FROM ghcr.io/groundsgg/paper:1.4.2

COPY --from=build /workspace/build/libs/BuildSystem-*.jar /app/plugins/
COPY --from=build /workspace/build/libs/GroundsMaps-*.jar /app/plugins/
COPY --from=ghcr.io/groundsgg/plugin-permissions:1.4.0@sha256:d668305f815e6a20a9ee6aa2df3e6cc42763def264fc3944e685125ca87594e1 /jar/paper.jar /app/plugins/plugin-permissions.jar

# The release data image must contribute one, and only one, Paper permissions plugin.
RUN test -f /app/plugins/plugin-permissions.jar && \
    test "$(find /app/plugins -maxdepth 1 -type f -name '*permissions*.jar' | wc -l)" -eq 1
