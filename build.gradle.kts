// The Spring Boot plugin is deliberately not put on the root classpath: gateway and
// ledger-service pin different Boot versions (see gradle/libs.versions.toml).
subprojects {
    group = "io.ledger"
    version = "0.1.0-SNAPSHOT"

    apply(plugin = "java")

    repositories {
        mavenCentral()
    }

    // Compile for Java 21 regardless of the JDK running Gradle.
    tasks.withType<JavaCompile>().configureEach {
        options.release = 21
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
