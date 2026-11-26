plugins {
    kotlin("jvm") version "2.4.21"
    id("com.vanniktech.maven.publish") version "0.37.0"
}

repositories {
    mavenCentral()
}

val ktorVersion = "3.6.2"

dependencies {
    api("io.hibira:hibira-java:0.3.0")
    api("io.ktor:ktor-server-core:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.test {
    useJUnitPlatform()
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
    coordinates("io.hibira", "hibira-ktor", version.toString())
    pom {
        name.set("hibira-ktor")
        description.set("Ktor plugin for Hibira: Pusher-protocol channel auth and webhooks, waiting rooms, Chat API, Approvals and Live Chat verified visitors")
        url.set("https://github.com/hibira-io/kotlin")
        licenses {
            license {
                name.set("MIT")
                url.set("https://opensource.org/licenses/MIT")
            }
        }
        developers {
            developer {
                id.set("hibira")
                name.set("Hibira")
                url.set("https://hibira.io")
            }
        }
        scm {
            url.set("https://github.com/hibira-io/kotlin")
            connection.set("scm:git:https://github.com/hibira-io/kotlin.git")
            developerConnection.set("scm:git:ssh://git@github.com/hibira-io/kotlin.git")
        }
    }
}
