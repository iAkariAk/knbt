@file:OptIn(ExperimentalWasmDsl::class, ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

val kotlinx_serialization_version = extra["kotlinx_serialization_version"].toString()
val okio_version = extra["okio_version"].toString()

plugins {
    kotlin("multiplatform") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
    id("org.jetbrains.dokka") version "2.2.0"
    id("com.vanniktech.maven.publish") version "0.34.0"
}

repositories {
    mavenCentral()
}

kotlin {
    explicitApi()

    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_1_8
        }
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }

    js {
        browser {
            testTask {
                useKarma {
                    useFirefoxHeadless()
                    useChromeHeadless()
                }
            }
        }
        nodejs()
    }

    wasmJs {
        browser()
        nodejs()
    }
    wasmWasi {
        nodejs()
    }

    linuxX64()
    linuxArm64()
    //androidNativeArm32() // Not supported by Okio yet
    //androidNativeArm64() // https://github.com/square/okio/issues/1242#issuecomment-1759357336
    //androidNativeX86()
    //androidNativeX64()
    macosArm64()
    iosSimulatorArm64()
    watchosSimulatorArm64()
    watchosArm32()
    watchosArm64()
    tvosSimulatorArm64()
    tvosArm64()
    iosArm64()
    watchosDeviceArm64()
    mingwX64()

    applyDefaultHierarchyTemplate {
        common {
            group("wasmCommon") {
                withWasmJs()
                withWasmWasi()
            }
        }
    }

    sourceSets {
        configureEach {
            languageSettings.apply {
                optIn("kotlin.contracts.ExperimentalContracts")
                optIn("net.benwoodworth.knbt.InternalNbtApi")
            }
        }

        getByName("commonMain") {
            dependencies {
                api("org.jetbrains.kotlinx:kotlinx-serialization-core:$kotlinx_serialization_version")
                implementation("com.squareup.okio:okio:$okio_version")
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinx_serialization_version")
                implementation("com.benwoodworth.parameterize:parameterize-core:0.4.1")
            }
        }
        getByName("jvmTest") {
            dependencies {
                implementation(kotlin("reflect"))
            }
        }
        getByName("jsMain") {
            dependencies {
                implementation(npm("pako", "2.1.0"))
            }
        }
        getByName("wasmCommonMain") {
            dependencies {
                implementation("dev.karmakrafts.kompress:kompress-core:2.1.0")
                implementation("dev.karmakrafts.kompress:kompress-core:2.1.0")
                implementation("org.jetbrains.kotlinx:kotlinx-io-okio:0.9.1")
            }
        }
    }

    @OptIn(ExperimentalAbiValidation::class) abiValidation()
}

dokka {
    dokkaPublications.configureEach {
        failOnWarning = true
    }

    dokkaSourceSets.all {
        documentedVisibilities = setOf(VisibilityModifier.Public)
        skipDeprecated = true
    }
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/iakariak/knbt")
            credentials {
                username = project.findProperty("gpr.user")?.toString() ?: System.getenv("GITHUB_ACTOR")
                password = project.findProperty("gpr.key")?.toString() ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

mavenPublishing {
    pom {
        name = "knbt"
        description = "Minecraft NBT support for kotlinx.serialization"
        url = "https://github.com/iAkariAk/knbt"

        licenses {
            license {
                name = "GNU Lesser General Public License"
                url = "https://www.gnu.org/licenses/lgpl-3.0.txt"
            }
        }
        developers {
            developer {
                id = "BenWoodworth"
                name = "Ben Woodworth"
                email = "ben@benwoodworth.net"
            }
        }
        scm {
            connection = "scm:git:git://github.com:iAkariAk/knbt.git"
            developerConnection = "scm:git:ssh://github.com:iAkariAk/knbt.git"
            url = "https://github.com/iAkariAk/knbt"
        }
    }
}
