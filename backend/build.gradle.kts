plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    id("io.ktor.plugin") version "3.5.2"
}

val integrationTest by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += output + compileClasspath
}

configurations[integrationTest.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())

group = "com.nexi"
version = "0.1.0"

application {
    mainClass.set("io.ktor.server.netty.EngineMain")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-netty")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    implementation("io.ktor:ktor-server-auth")
    implementation("io.ktor:ktor-server-auth-jwt")
    implementation("io.ktor:ktor-server-status-pages")
    implementation("io.ktor:ktor-server-call-logging")
    implementation("io.ktor:ktor-server-cors")

    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("org.postgresql:postgresql:42.7.13")
    implementation("org.flywaydb:flyway-core:13.3.0")
    implementation("org.flywaydb:flyway-database-postgresql:13.3.0")
    implementation("de.mkammerer:argon2-jvm:2.12")
    implementation("software.amazon.awssdk:s3:2.54.2")
    implementation("software.amazon.awssdk:url-connection-client:2.54.2")
    implementation("ch.qos.logback:logback-classic:1.6.3")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host")

    add(integrationTest.implementationConfigurationName, platform("org.testcontainers:testcontainers-bom:2.0.5"))
    add(integrationTest.implementationConfigurationName, "org.testcontainers:testcontainers-junit-jupiter")
    add(integrationTest.implementationConfigurationName, "org.testcontainers:testcontainers-postgresql")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    // Sıralama eşitlik dosyasını yeniden üretmek için:
    //   gradle test --tests '*RankingParityTest*' -Dnexi.parity.write=true
    // Gradle bu bayrağı kendiliğinden test JVM'ine geçirmiyor.
    System.getProperty("nexi.parity.write")?.let { systemProperty("nexi.parity.write", it) }
}

tasks.register<Test>("integrationTest") {
    description = "Runs PostgreSQL integration and concurrency tests with Testcontainers."
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.test)
}
