# syntax=docker/dockerfile:1
# Image of cart-service. Build from this directory: docker build -t vanmool/cart-service .
# Tests are not run here: they run with ./mvnw verify before an image is built.

# Stage 1: compile with the same Maven and JDK on every machine, whatever is installed where the image is built
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
COPY src/main src/main
# The cache mount keeps downloaded dependencies between builds without putting them into the image. Locked: the
# eight images build at the same time, and two Maven runs writing the same files of one cache can fail
RUN --mount=type=cache,target=/root/.m2,sharing=locked mvn -B -q package -Dmaven.test.skip=true
# Split the jar into layers: libraries change rarely, the code often, so a rebuild reuses the library layer
RUN cp target/*.jar application.jar \
    && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# Stage 2: only a Java runtime and the application, no Maven or JDK
FROM eclipse-temurin:17-jre-noble
RUN useradd --system --no-create-home --shell /usr/sbin/nologin vanmool
WORKDIR /app
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./
# The service does not need root inside the container
USER vanmool
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "application.jar"]
