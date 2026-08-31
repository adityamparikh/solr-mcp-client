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
  //  implementation(libs.spring.ai.openai)
    implementation(libs.spring.ai.anthropic)

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
    }
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
        property("sonar.organization", "adityaparikh91087")
        property("sonar.projectKey", "adityaparikh91087_spring-ai-mcp-client")
        property("sonar.host.url", "https://sonarcloud.io")
        property(
            "sonar.coverage.jacoco.xmlReportPaths",
            layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml").get().asFile.path
        )
    }
}
