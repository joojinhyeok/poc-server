plugins {
    java
    id("org.springframework.boot") version "3.5.11"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.danalfintech"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot Starters
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Messaging & Caching
    implementation("org.springframework.boot:spring-boot-starter-amqp")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // Database
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // Monitoring
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    // .env 파일 로딩
    implementation("me.paulschwarz:spring-dotenv:4.0.0")

    // JWT
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // Swagger / OpenAPI
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.6")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("com.h2database:h2")

    // Testcontainers (통합 테스트용)
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("com.redis:testcontainers-redis:2.2.4")
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8")

    // Windows 한글 경로 ClassNotFoundException 우회:
    // 한글 경로의 build output을 영문 temp 디렉토리에 junction으로 연결
    val tempTestClasses = layout.buildDirectory.dir("tmp/test-cp")
    doFirst {
        val tmpDir = tempTestClasses.get().asFile
        tmpDir.mkdirs()
        val nonAsciiDirs = classpath.files.filter { it.path.any { c -> c.code > 127 } && it.isDirectory }
        nonAsciiDirs.forEach { dir ->
            val linkName = dir.name + "-" + dir.path.hashCode().toUInt().toString(16)
            val link = File(tmpDir, linkName)
            if (!link.exists()) {
                // Windows directory junction (관리자 권한 불필요)
                Runtime.getRuntime().exec(arrayOf("cmd", "/c", "mklink", "/J", link.absolutePath, dir.absolutePath)).waitFor()
            }
            classpath = classpath.minus(files(dir)).plus(files(link))
        }
    }
}