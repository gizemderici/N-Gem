# syntax=docker/dockerfile:1
FROM gradle:8.14.4-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/home/gradle/.gradle \
    gradle --no-daemon clean installDist

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/build/install/nexi-backend/ ./
EXPOSE 8080
ENTRYPOINT ["./bin/nexi-backend"]
