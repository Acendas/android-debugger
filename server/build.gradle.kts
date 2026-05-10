plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    application
    id("com.gradleup.shadow") version "8.3.5"
}

group = "com.acendas.androiddebugger"
version = "1.1.1"

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
    //
    // R-28: works fine on the JDK 17 toolchain. If the toolchain bumps to JDK 21+
    // and tests start failing with `InaccessibleObjectException`, mockito-core needs
    // `--add-opens=java.base/java.lang=ALL-UNNAMED` (and friends) on the test JVM.
    // We don't run on 21+ today; documenting here so the trip wire is set.
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

    // Per R-09: the MCP SDK 0.12.0 transitively pulls in ktor for HTTP / WebSocket /
    // SSE transports. We only use [StdioServerTransport], so excluding those classes
    // shaves several MB off the fat jar without changing runtime behavior. Smoke-tested
    // post-build by sending an `initialize` request and confirming a JSON-RPC reply.
    //
    // We exclude by file pattern (works against the merged shadow content) rather than
    // by Gradle dependency() matcher — the latter requires a tight artifact-name match
    // and silently no-ops when a transitive jar's classifier (e.g. `-jvm`) keeps it
    // alive.
    exclude("io/ktor/client/**")
    exclude("io/ktor/server/**")
    exclude("io/ktor/network/**")
    exclude("io/ktor/websocket/**")
    exclude("io/ktor/websockets/**")
    exclude("io/ktor/sse/**")
    exclude("io/ktor/http/cio/**")
    exclude("io/ktor/serialization/**")
    exclude("io/ktor/events/**")
    // Streamable HTTP and SSE MCP transports we don't use — we only wire StdioServerTransport.
    exclude("io/modelcontextprotocol/kotlin/sdk/client/**")
    exclude("io/modelcontextprotocol/kotlin/sdk/server/StreamableHttp*")
    exclude("io/modelcontextprotocol/kotlin/sdk/server/SseServerTransport*")
    // jansi ships native libs for every OS/arch under the sun. We don't use jansi for
    // anything (slf4j-simple writes plain stderr); drop the native bundles. Doesn't
    // affect runtime because nothing in our stack invokes the Jansi loader.
    exclude("org/fusesource/jansi/internal/native/**")
    // kotlin-reflect is transitively pulled in by the MCP SDK but our code never
    // performs reflection at runtime. The Anthropic SDK builds its tool registry from
    // schemas we hand it directly (ToolSchema with explicit JSON), not via KClass
    // introspection. Smoke test (initialize + tools/list + a tool call) confirms the
    // server boots and answers without kotlin-reflect on the classpath.
    exclude("kotlin/reflect/jvm/internal/**")
    exclude("kotlin/reflect/full/**")
}

// Make the default `build` task produce the shadow jar in dist/.
tasks.named("build") {
    dependsOn("shadowJar")
}

// Per R-29: regression-test jar size. Fails the build if the fat jar grows beyond the
// threshold so we don't silently re-bloat after R-09.
val maxFatJarBytes: Long = 10L * 1024L * 1024L // 10 MB

tasks.register("checkJarSize") {
    description = "Fail the build if the shaded fat jar exceeds the configured size cap."
    group = "verification"
    dependsOn("shadowJar")
    doLast {
        val jar = file("$projectDir/../dist/android-debugger-server.jar")
        if (!jar.exists()) {
            throw GradleException("Expected fat jar not found at ${jar.absolutePath}")
        }
        val size = jar.length()
        val mb = size / 1024.0 / 1024.0
        logger.lifecycle("android-debugger-server.jar: %.2f MB (cap %.2f MB)".format(mb, maxFatJarBytes / 1024.0 / 1024.0))
        if (size > maxFatJarBytes) {
            throw GradleException(
                "Fat jar is ${'$'}size bytes, exceeds cap of ${'$'}maxFatJarBytes. " +
                    "Trim shadowJar `dependencies { exclude(...) }` or raise the cap with intent."
            )
        }
    }
}

tasks.named("check") {
    dependsOn("checkJarSize")
}
