plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    application
}

group = "com.constrasset"
version = "1.0.0"

repositories {
    mavenCentral()
}

val ktorVersion = "3.0.3"
val exposedVersion = "0.54.0"
val koinVersion = "4.0.2"

dependencies {
    // ---- Ktor server (Netty engine, per Global Constraints) ----
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jwt-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-cors-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-swagger-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-openapi-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-java-jvm:$ktorVersion")

    // ---- Serialization: kotlinx.serialization ONLY (Jackson/Gson forbidden) ----
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // ---- ORM: Exposed SQL DSL ONLY (Hibernate/JPA forbidden) ----
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")

    // ---- PostgreSQL 16+ ----
    implementation("org.postgresql:postgresql:42.7.4")

    // ---- Redis 7+ (JWT blacklist / refresh tokens / short-link / pack TTL) ----
    implementation("redis.clients:jedis:5.2.0")

    // ---- MinIO (S3-compatible storage) ----
    implementation("io.minio:minio:8.5.13")

    // ---- Password hashing: Argon2id ONLY (BCrypt/MD5 forbidden) ----
    implementation("de.mkammerer:argon2-jvm:2.11")

    // ---- EXIF reading for thumbnail pipeline (§4.3) ----
    implementation("com.drewnoakes:metadata-extractor:2.19.0")

    // ---- DI ----
    implementation("io.insert-koin:koin-core:$koinVersion")
    implementation("io.insert-koin:koin-ktor:$koinVersion")

    // ---- Logging ----
    implementation("ch.qos.logback:logback-classic:1.5.12")

    // ---- Config ----
    implementation("com.typesafe:config:1.4.3")

    // ---- Tests (test-first policy, Critical Note #10) ----
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
    testImplementation("io.ktor:ktor-client-content-negotiation-jvm:$ktorVersion")
    testImplementation("org.testcontainers:postgresql:1.20.4")
    testImplementation("org.testcontainers:minio:1.20.4")
    testImplementation("com.github.codemonstur:embedded-redis:1.4.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

application {
    mainClass.set("com.constrasset.ApplicationKt")
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}
