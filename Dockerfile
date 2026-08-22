# Multi-stage build for MedAppServer
#
# JDK 25, потому что столько требует toolchain проекта (Kotlin 2.4, #97). На jdk21 сборка
# образа падала на `./gradlew dependencies`: «Cannot find a Java installation matching
# languageVersion=25», а скачивать toolchain Gradle не настроен. В CI это не всплывало —
# на раннере GitHub JDK 25 стоит по случайности образа.
#
# Образ temurin, а не gradle: свой Gradle базового образа здесь всё равно не используется,
# сборка идёт обёрткой из репозитория.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app

# Copy gradle files for dependency caching. The wrapper is committed, so the build uses the
# Gradle version the project pins rather than whatever the base image happens to ship.
COPY build.gradle.kts settings.gradle.kts gradlew ./
COPY gradle ./gradle

# Resolve dependencies in their own layer. No `|| true`: a failure here used to be swallowed
# and resurfaced later as a confusing build error.
RUN ./gradlew dependencies --no-daemon

# Copy source code
COPY src ./src

# Make sure a signing key pair exists before packaging. The script is idempotent: keys
# copied in with the sources are left alone, and only a missing pair is generated. So the
# image needs no key handed to it and no key committed to git, and there is nothing to
# configure either way.
RUN sh src/main/resources/certs/gen.sh

# Build application
RUN ./gradlew bootJar --no-daemon

# Runtime stage
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# Install wget for healthcheck
RUN apk add --no-cache wget

# Create non-root user for security
RUN addgroup -S spring && adduser -S spring -G spring

# Copy built jar from build stage as root, then hand it to the runtime user, so the files
# are not left owned by root.
COPY --from=build /app/build/libs/*.jar app.jar
RUN chown spring:spring app.jar

USER spring:spring

ENV SPRING_PROFILES_ACTIVE=mock-prod,prod

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

# Run application
ENTRYPOINT ["java", "-jar", "app.jar"]
