# Multi-stage build for MedAppServer
FROM gradle:9.3-jdk21 AS build
WORKDIR /app

# Copy gradle files for dependency caching
COPY build.gradle.kts settings.gradle.kts gradlew ./
COPY gradle ./gradle

# Resolve and verify dependencies before copying sources, preserving the cache layer.
RUN ./gradlew dependencies --no-daemon

# Copy source code
COPY src ./src

# Build application
RUN ./gradlew bootJar --no-daemon

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Install only the healthcheck client, then run without root privileges.
RUN apk upgrade --no-cache && apk add --no-cache wget

# Create non-root user for security
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

# Copy built jar from build stage
COPY --from=build /app/build/libs/*.jar app.jar

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

# Run application
ENTRYPOINT ["java", "-jar", "app.jar"]
