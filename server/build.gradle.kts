plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    application
    id("com.gradleup.shadow") version "8.3.5"
}

group = "com.acendas.androiddebugger"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // MCP server SDK (official Anthropic Kotlin SDK)
    implementation("io.modelcontextprotocol:kotlin-sdk:0.12.0")

    // JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // Coroutines (for MCP SDK + event loop)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // Logging — slf4j + simple impl that writes to stderr (stdout is the MCP transport)
    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("org.slf4j:slf4j-simple:2.0.16")

    // JDI is bundled with the JDK at com.sun.jdi — no extra dependency needed.
    // It's added to the classpath via tools/lib in older JDKs; on JDK 9+ it lives in jdk.jdi module.

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    // Mockito for mocking JDI interfaces (ThreadReference etc.) — used by AnrWatchdog
    // unit tests. Per Story 7.1.3.
    testImplementation("org.mockito:mockito-core:5.14.2")
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.acendas.androiddebugger.MainKt")
}

// JDI lives in the jdk.jdi module on JDK 9+. Make sure compile + run see it.
val jdiArgs = listOf("--add-modules=jdk.jdi")

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(jdiArgs)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.addAll(listOf("-Xjdk-release=17"))
    }
}

tasks.named<JavaExec>("run") {
    jvmArgs(jdiArgs)
    standardInput = System.`in`
}

tasks.test {
    useJUnitPlatform()
    jvmArgs(jdiArgs)
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("android-debugger-server")
    archiveClassifier.set("")
    archiveVersion.set("")
    mergeServiceFiles()
    manifest {
        attributes["Main-Class"] = "com.acendas.androiddebugger.MainKt"
        // Ensure the runtime jvm picks up jdk.jdi when launched via `java -jar`.
        // (Add-Modules manifest entry is honored by `java -jar` since JDK 9.)
        attributes["Add-Modules"] = "jdk.jdi"
    }
    // Drop the jar into ../dist so `.mcp.json` can reference ${CLAUDE_PLUGIN_ROOT}/dist/...
    destinationDirectory.set(file("$projectDir/../dist"))
}

// Make the default `build` task produce the shadow jar in dist/.
tasks.named("build") {
    dependsOn("shadowJar")
}
