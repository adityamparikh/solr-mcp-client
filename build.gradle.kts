/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone

plugins {
    java
    jacoco
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.cyclonedx)
    alias(libs.plugins.sonarqube)
    alias(libs.plugins.errorprone)
    // Adds nativeCompile/nativeTest and makes the Spring Boot plugin contribute its AOT tasks.
    // The reachability-metadata repository is on by default in 1.x; the regular jvm build and
    // `check` are untouched (nothing depends on the native tasks).
    alias(libs.plugins.graalvm.buildtools.native)
}

group = "org.apache.solr"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.jspecify)
    implementation(libs.spring.boot.actuator)
    implementation(libs.spring.boot.opentelemetry)
    implementation(libs.spring.boot.webmvc)
    implementation(libs.spring.boot.validation)
    implementation(libs.spring.boot.oauth2.client)
    implementation(libs.springdoc.openapi.webmvc.ui)
    implementation(libs.spring.ai.mcp.client)
    implementation(libs.mcp.client.security)
    implementation(libs.spring.ai.openai)
  //  implementation(libs.spring.ai.anthropic)
    implementation(libs.spring.shell.starter.ffm)

    testImplementation(libs.spring.boot.actuator.test)
    testImplementation(libs.spring.boot.opentelemetry.test)
    testImplementation(libs.spring.boot.webmvc.test)
    testImplementation(libs.spring.security.test)
    testRuntimeOnly(libs.junit.platform.launcher)

    errorprone(libs.error.prone.core)
    errorprone(libs.nullaway)
}

// NullAway is the only Error Prone check this build enforces. Error Prone's own several hundred
// checks are switched off rather than adopted silently: turning them on is a separate decision with
// its own backlog, and leaving them at warning level would train everyone to ignore the output.
//
// OnlyNullMarked is preferred over AnnotatedPackages so that @NullMarked in package-info.java is the
// single source of truth for what is checked. A package list here would be a second place to keep
// in step, and a new package would silently go unchecked until someone remembered to add it.
tasks.withType<JavaCompile>().configureEach {
    // Spring's AOT processor (compileAotJava/compileAotTestJava, from the GraalVM native plugin)
    // generates bean-definition sources into Spring's own packages, which Framework 7 marks
    // @NullMarked — NullAway would fail the build on generated code this project doesn't own.
    if (name.startsWith("compileAot")) {
        options.errorprone.enabled = false
        return@configureEach
    }
    options.errorprone {
        disableAllChecks = true
        check("NullAway", CheckSeverity.ERROR)
        option("NullAway:OnlyNullMarked", "true")
        option("NullAway:JSpecifyMode", "true")
    }
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:${libs.versions.spring.ai.get()}")
        mavenBom("org.springframework.shell:spring-shell-dependencies:${libs.versions.spring.shell.get()}")
    }
}

// JLine's FFM terminal provider calls JEP 472 restricted methods, which the JDK warns about on
// stderr from JDK 24 onwards. Following the Spring Shell build docs
// (https://docs.spring.io/spring-shell/reference/building.html#_ffm), the executable jar grants
// itself native access through its manifest, so plain `java -jar` starts the REPL warning-free.
// The attribute is read only by the `java -jar` launcher: bootRun (which runs from classes) passes
// the equivalent flag below, and a GraalVM native image would instead take
// `--enable-native-access=ALL-UNNAMED` as a native-image build argument.
tasks.bootJar {
    manifest {
        attributes("Enable-Native-Access" to "ALL-UNNAMED")
    }
}

// The native image counterpart of the manifest grant above: a native image never reads a jar
// manifest, so JEP 472 native access (needed by JLine's FFM terminal provider) is granted at
// image build time instead. GraalVM 25+ compiles FFM downcalls by default. The plugin finds
// native-image via GRAALVM_HOME, then JAVA_HOME (toolchain detection is off by default).
//
// Reflection the reachability-metadata repository misses is registered in
// src/main/resources/META-INF/native-image/org.apache.solr/solr-mcp-client-protobuf/
// reachability-metadata.json (JSON allows no comment, hence this one; the directory is NOT the
// project's own GAV because Spring AOT generates its metadata at that path, and bootJar rejects
// the duplicate entry — native-image scans every namespace under META-INF/native-image/**, so a
// sibling directory is honored the same): protobuf's
// ExtensionRegistryFactory reflectively probes for the full (non-lite) runtime when Micrometer's
// OtlpMeterRegistry builds its first OTLP message, which only surfaces when the image runs.
graalvmNative {
    binaries {
        named("main") {
            buildArgs.add("--enable-native-access=ALL-UNNAMED")
            // Workaround for https://github.com/spring-projects/spring-ai/issues/4714: GraalVM 25
            // bakes BaseAdvisor into the image with its DEFAULT_SCHEDULER static left null, and
            // every advisor built without an explicit scheduler (ours, and the ToolCallingAdvisor
            // Spring AI auto-registers per request) then fails "scheduler cannot be null" — at
            // run time only, the build stays green. Deferring the interface's initialization to
            // run time lets its initializer actually run. Drop when the upstream fix lands.
            buildArgs.add("--initialize-at-run-time=org.springframework.ai.chat.client.advisor.api.BaseAdvisor")
        }
    }
}

// Gradle gives the forked JVM an empty stdin, so under the cli profile the interactive shell
// would read EOF and exit immediately. Wiring the build's own stdin through makes
// `bootRun --args='--spring.profiles.active=cli,mcp-stdio'` usable; `java -jar` remains the
// canonical way to run the REPL.
//
// The Unsafe flag silences the JDK 24+ (JEP 498) startup warning triggered by protobuf-java's
// sun.misc.Unsafe.arrayBaseOffset use (pulled in by the OTLP exporter). Unlike native access,
// JEP 498 defines no manifest attribute, so `java -jar` runs pass this one flag by hand (see
// README). The native-access flag repeats the bootJar manifest grant for bootRun, which does not
// launch through the jar.
tasks.bootRun {
    standardInput = System.`in`
    jvmArgs("--sun-misc-unsafe-memory-access=allow", "--enable-native-access=ALL-UNNAMED")
}

// Every jar this project publishes carries the licence texts in META-INF, as an ASF release
// requires. Applied to all Jar tasks rather than to bootJar alone: the Spring Boot plugin also
// produces the plain `-plain` jar, and a sources or javadoc jar added later would otherwise ship
// without them. `from` takes the repository-root files, so the two copies cannot drift.
tasks.withType<Jar>().configureEach {
    metaInf {
        from(layout.projectDirectory.file("LICENSE"))
        from(layout.projectDirectory.file("NOTICE"))
    }
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

tasks.withType<Test> {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                counter = "INSTRUCTION"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
    classDirectories.setFrom(
        files(classDirectories.files.map {
            fileTree(it) { exclude("**/SolrMcpClientApplication.class") }
        })
    )
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

sonar {
    properties {
        property("sonar.organization", "adityamparikh")
        property("sonar.projectKey", "adityamparikh_solr-mcp-client")
        property("sonar.host.url", "https://sonarcloud.io")
        property(
            "sonar.coverage.jacoco.xmlReportPaths",
            layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml").get().asFile.path
        )
    }
}
