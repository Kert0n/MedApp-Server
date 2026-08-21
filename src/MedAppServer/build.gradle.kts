plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.spring") version "2.4.10"
    id("org.springframework.boot") version "4.0.2"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("plugin.serialization") version "2.4.10"
}

group = "org.kert0n"
version = "0.0.1-SNAPSHOT"
description = "MedAppServer"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.1")
    // Стартер под Spring Boot 4 — отдельный артефакт, как вофициальном samples/exposed-spring.
    // Обычный exposed-spring-boot-starter собран под Boot 3 и ищет автоконфигурацию по
    // старым адресам.
    implementation("org.jetbrains.exposed:exposed-spring-boot4-starter:1.4.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:1.4.0")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.springframework.boot:spring-boot-starter-kotlinx-serialization-json")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("com.sksamuel.aedile:aedile-core:3.0.2")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("-XX:+EnableDynamicAgentLoading")
    // Lets OpenApiSnapshotTest rewrite open-api.yaml instead of asserting against it:
    //     ./gradlew test -DupdateOpenApi=true
    systemProperty("updateOpenApi", System.getProperty("updateOpenApi") ?: "false")
    // Tests read these two files, so a change to either must invalidate the results.
    // Without this a broken db/schema.sql leaves the previous green run in place: the task
    // is up to date because neither file is on the classpath.
    inputs.file("db/schema.sql").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file("open-api.yaml").withPathSensitivity(PathSensitivity.RELATIVE)
}
