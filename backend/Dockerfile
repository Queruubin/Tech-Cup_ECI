# Build stage: compiles the Spring Boot jar. Uses the Maven image instead of ./mvnw so the
# build does not depend on the wrapper script's line endings on Windows checkouts.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Resolve dependencies in their own layer so code changes do not re-download them.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Tests run in the regular build (./mvnw test); the image only packages the application.
RUN mvn -B -q -DskipTests package

# Runtime stage: JRE only, running as an unprivileged user so a compromised process cannot
# write outside its own working directory.
FROM eclipse-temurin:17-jre
RUN groupadd --system techcup && useradd --system --gid techcup --no-create-home --shell /usr/sbin/nologin techcup
WORKDIR /app
COPY --from=build --chown=techcup:techcup /app/target/*.jar app.jar
USER techcup
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
