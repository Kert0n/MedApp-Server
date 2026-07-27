plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.spring") version "2.2.21"
    id("org.springframework.boot") version "4.0.2"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("plugin.jpa") version "2.2.21"
}

group = "org.kert0n"
version = "0.0.1-SNAPSHOT"
description = "MedAppServer"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.1")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.springframework.boot:spring-boot-configuration-processor")
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
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    // Проверка слоёв: ArchitectureTest читает исходники и валит сборку на запрещённом
    // импорте. Аналог ArchUnit для Kotlin — тот работает с байткодом и не видит ни
    // top-level функций, ни расширений, которых здесь достаточно.
    testImplementation("com.lemonappdev:konsist:0.17.3")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testImplementation("org.mockito:mockito-inline:5.2.0")
    testImplementation("com.h2database:h2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

/**
 * Отдельный набор для проверки планов запросов: src/queryPlanTest/kotlin.
 *
 * Почему не в `test`. Он сеет синтетику на десятки тысяч строк и гоняет EXPLAIN — это
 * минуты, а не секунды, и в обычном прогоне такое место занимать не должно. Запуск явный:
 *
 *     ./gradlew queryPlanTest
 *
 * В `check` намеренно не включён: пусть падение обычных тестов и падение планов
 * различаются по команде, а не по строчке в общем логе.
 */
testing {
    suites {
        register<JvmTestSuite>("queryPlanTest") {
            useJUnitJupiter()
            dependencies {
                implementation(project())
                implementation("org.springframework.boot:spring-boot-starter-test")
                implementation("org.springframework.boot:spring-boot-starter-data-jpa")
                implementation("org.springframework.boot:spring-boot-testcontainers")
                implementation("org.jetbrains.kotlin:kotlin-test-junit5")
                implementation("org.testcontainers:testcontainers-junit-jupiter")
                implementation("org.testcontainers:testcontainers-postgresql")
                implementation("tools.jackson.core:jackson-databind")
            }
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("-XX:+EnableDynamicAgentLoading")
    // Lets OpenApiSnapshotTest rewrite open-api.yaml instead of asserting against it:
    //     ./gradlew test -DupdateOpenApi=true
    systemProperty("updateOpenApi", System.getProperty("updateOpenApi") ?: "false")
    // QuantityArithmeticBenchmarkTest is skipped unless this is set. It measures rather than
    // asserts, so it has no place in a normal run:
    //     ./gradlew test --tests "*QuantityArithmeticBenchmarkTest*" -DrunBenchmark=true
    systemProperty("runBenchmark", System.getProperty("runBenchmark") ?: "false")
}
