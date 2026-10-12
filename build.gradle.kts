import java.util.Base64

plugins {
    application
    `maven-publish`
    signing
    id("tech.yanand.maven-central-publish") version "1.3.0"
}

group = "io.github.sina-rz"
version = "0.1.6"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
    withJavadocJar()
}

repositories {
    mavenCentral()
}

val guiOnly by configurations.creating

dependencies {
    // JLayer 1.0.1 - LGPL (see README "Dependencies"). Pure-Java MPEG-1/2/2.5
    // Layer I/II/III decoder. Referenced ONLY from audio.decoder.Mp3Decoder.
    implementation("javazoom:jlayer:1.0.1")

    guiOnly("com.formdev:flatlaf:3.7.2")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "io.github.sinaraz.cli.Main"
}

tasks.register<JavaExec>("runGui") {
    group = "application"
    description = "Starts the Swing GUI player. Options: -PguiArgs=\"--null-audio song.mp3\""
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.sinaraz.gui.GuiMain")
    val guiArgs = (findProperty("guiArgs") as String?) ?: ""
    args = guiArgs.split(" ").filter { it.isNotBlank() }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial", "-Werror"))
}

sourceSets {
    create("bench") {
        compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
        runtimeClasspath += output + compileClasspath
    }
    main {
        compileClasspath += guiOnly
        runtimeClasspath += guiOnly
    }
}

tasks.register<JavaExec>("benchmark") {
    group = "verification"
    description = "Measures seek latency and decode speed. Pass -PbenchFiles=a.mp3,b.wav"
    classpath = sourceSets["bench"].runtimeClasspath
    mainClass.set("io.github.sinaraz.bench.Benchmark")
    val files = (findProperty("benchFiles") as String?) ?: ""
    args = files.split(",").filter { it.isNotBlank() }
}

sourceSets {
    create("samples") {
        compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
        runtimeClasspath += output + compileClasspath
    }
}

// Runs one sample program: ./gradlew runSample -Psample=QuickStart [-PsampleArgs="a.mp3"]
tasks.register<JavaExec>("runSample") {
    group = "application"
    description = "Runs a sample from src/samples. Options: -Psample=QuickStart|SeekAndSpeed|DecodeOffline|OfflineProcessing|StreamOverHttp|PitchPreservingSpeed (tutorial.Step1..Step6 too)"
    classpath = sourceSets["samples"].runtimeClasspath
    mainClass.set("io.github.sinaraz.samples." + ((findProperty("sample") as String?) ?: "QuickStart"))
    val sampleArgs = (findProperty("sampleArgs") as String?) ?: ""
    args = sampleArgs.split(" ").filter { it.isNotBlank() }
}


tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.named<Javadoc>("javadoc") {
    exclude("io/github/sinaraz/internal/**")
    exclude("io/github/sinaraz/cli/**")
    exclude("io/github/sinaraz/gui/**")
}

tasks.jar {
    exclude("io/github/sinaraz/gui/**")
    exclude("io/github/sinaraz/cli/**")
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            pom {
                name.set("Judio")
                description.set("Pure-Java audio playback library for JVM.")
                url.set("https://github.com/sina-rz/judio")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("sina-rz")
                        name.set("Sina Raz")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/sina-rz/judio.git")
                    developerConnection.set("scm:git:ssh://github.com:sina-rz/judio.git")
                    url.set("https://github.com/sina-rz/judio")
                }
            }
        }
    }
}

signing {
    sign(publishing.publications["mavenJava"])
}

mavenCentral {
    // Reads the credentials you put in ~/.gradle/gradle.properties
    authToken.set(
        Base64.getEncoder().encodeToString(
            "${project.findProperty("sonatypeUsername")}:${project.findProperty("sonatypePassword")}".toByteArray()
        )
    )
    publishingType.set("AUTOMATIC") // or "USER_MANAGED"
}
