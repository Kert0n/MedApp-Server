# Multi-stage build for MedAppServer
FROM gradle:9.3-jdk21 AS build
WORKDIR /app

# Copy gradle files for dependency caching
COPY build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle

# Download dependencies
RUN gradle dependencies --no-daemon || true

# Copy source code
COPY src ./src

# Build application
RUN gradle bootJar --no-daemon

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# wget for the healthcheck, openssl for the key generation below.
RUN apk add --no-cache wget openssl

# Create non-root user for security
RUN addgroup -S spring && adduser -S spring -G spring

# JWT signing keys are generated here, during the image build.
#
# The image stays self-contained — nothing to do by hand before running it — but the key
# is unique per build, was never on a developer machine and was never in git. bootJar no
# longer packages any PEM, so this is the only key in the image.
#
# Consequence to know: rebuilding produces a new key, so tokens issued by an older image
# stop validating. They live 10 minutes, and clients re-request them anyway.
# To pin keys across builds, mount them and override RSA_PRIVATE_KEY / RSA_PUBLIC_KEY.
COPY src/main/resources/certs/gen.sh /usr/local/bin/gen-jwt-keys.sh
RUN chmod +x /usr/local/bin/gen-jwt-keys.sh \
    && SECRETS_DIR=/app/secrets /usr/local/bin/gen-jwt-keys.sh \
    && chown -R spring:spring /app/secrets

# Copy built jar from build stage. Done as root, then handed over, so the runtime user
# owns what it needs and nothing else.
COPY --from=build /app/build/libs/*.jar app.jar
RUN chown spring:spring app.jar

USER spring:spring

# These are resource LOCATIONS, not key contents. Named to match rsa.private-key /
# rsa.public-key so Spring's relaxed binding lets them override any value baked into
# application-prod.properties.
ENV RSA_PRIVATE_KEY=file:/app/secrets/jwt-private.pem
ENV RSA_PUBLIC_KEY=file:/app/secrets/jwt-public.pem
ENV SPRING_PROFILES_ACTIVE=prod

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

# Run application
ENTRYPOINT ["java", "-jar", "app.jar"]
