import java.security.KeyPairGenerator
import java.util.Base64

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

val generatedTestResources = layout.buildDirectory.dir("generated-test-resources")

val generateTestRsaKeys by tasks.registering {
    description = "Generates an ephemeral RSA key pair for the test profile"
    outputs.dir(generatedTestResources)
    outputs.upToDateWhen { false }

    doLast {
        val certificateDirectory = generatedTestResources.get().dir("certs").asFile
        certificateDirectory.mkdirs()

        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(2048)
        val keyPair = keyPairGenerator.generateKeyPair()
        val mimeEncoder = Base64.getMimeEncoder(64, "\n".toByteArray())

        fun pem(type: String, encoded: ByteArray): String = buildString {
            appendLine("-----BEGIN $type-----")
            appendLine(mimeEncoder.encodeToString(encoded))
            appendLine("-----END $type-----")
        }

        certificateDirectory.resolve("private.pem")
            .writeText(pem("PRIVATE KEY", keyPair.private.encoded))
        certificateDirectory.resolve("public.pem")
            .writeText(pem("PUBLIC KEY", keyPair.public.encoded))
    }
}

sourceSets {
    main {
        // Signing keys are runtime inputs and must never be packaged into an artifact.
        resources.exclude("certs/*.pem")
    }
    test {
        resources.srcDir(generatedTestResources)
    }
}

dependencies {
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.1")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.springframework.boot:spring-boot-configuration-processor")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("com.sksamuel.aedile:aedile-core:3.0.2")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
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

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("-XX:+EnableDynamicAgentLoading")
    systemProperty("updateOpenApi", System.getProperty("updateOpenApi", "false"))
}

tasks.named<ProcessResources>("processTestResources") {
    dependsOn(generateTestRsaKeys)
}
