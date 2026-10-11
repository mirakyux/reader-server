import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.spring") version "2.4.21"
    id("org.springframework.boot") version "4.1.1"
}

group = "com.htmake"
version = "2.5.4"

// The API and web application have independent build outputs and deployments.
layout.buildDirectory = layout.projectDirectory.dir("build/backend")

repositories {
    mavenCentral()
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget = JvmTarget.JVM_21
}

// Vert.x's Java 21 runtime supports Jackson 3; keep only the shared Jackson annotations.
configurations.configureEach {
    exclude(group = "com.fasterxml.jackson.core", module = "jackson-core")
    exclude(group = "com.fasterxml.jackson.core", module = "jackson-databind")
    exclude(group = "com.fasterxml.jackson.module", module = "jackson-module-kotlin")
}

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    // Upgrade direct libraries; reuse their upstream management for transitive dependencies.
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:2.4.21"))
    implementation(platform("io.vertx:vertx-stack-depchain:5.2.1"))
    implementation(platform("com.squareup.okhttp3:okhttp-bom:5.5.0"))

    constraints {
        implementation("org.antlr:antlr4-runtime") {
            version { strictly("4.7.2") }
            because("JsoupXpath 2.5.5 embeds ATN format 3; newer runtimes require regenerated parsers")
        }
    }

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("io.vertx:vertx-core")
    implementation("io.vertx:vertx-lang-kotlin-coroutines")
    implementation("io.vertx:vertx-web")
    implementation("io.vertx:vertx-web-client")

    implementation("com.google.code.gson:gson:2.14.0")
    implementation("tools.jackson.module:jackson-module-kotlin:3.2.3")
    implementation("io.github.oshai:kotlin-logging-jvm:8.0.4")
    implementation("com.google.guava:guava:33.7.2-jre")
    implementation("com.squareup.okhttp3:okhttp")
    implementation("com.squareup.okhttp3:logging-interceptor")
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("org.mozilla:rhino:1.9.1")
    implementation("org.mozilla:rhino-engine:1.9.1")
    implementation("org.jsoup:jsoup:1.23.2")
    implementation("cn.wanghaomiao:JsoupXpath:2.5.5")
    implementation("com.jayway.jsonpath:json-path:3.0.0")
    implementation("xmlpull:xmlpull:1.1.3.4a")
    implementation("cn.hutool:hutool-crypto:5.8.48")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

springBoot {
    mainClass = "com.htmake.reader.ReaderApplicationKt"
}

tasks.test {
    useJUnitPlatform()
    // Legacy file helpers also use the process directory; isolate their storage too.
    val testWorkspace = layout.buildDirectory.dir("test-workspace")
    workingDir(testWorkspace)
    doFirst { testWorkspace.get().asFile.mkdirs() }
}

tasks.bootJar {
    archiveFileName = "reader.jar"
}

tasks.jar {
    enabled = false
}

tasks.processResources {
    exclude("web/**")
}
