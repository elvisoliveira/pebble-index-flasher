import groovy.json.JsonSlurper
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

plugins {
    id("com.android.application") version "8.9.2"
    id("org.jetbrains.kotlin.android") version "2.1.20"
}

android {
    namespace = "poc.ringclick"
    compileSdk = 36

    defaultConfig {
        applicationId = "poc.ringclick"
        minSdk = 31
        targetSdk = 36
        versionCode = 3
        versionName = "1.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Same version the official app uses (mobileapp libs.versions.toml)
    implementation("io.github.coredevices.haversine:haversine:f8d8bd7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("com.google.android.material:material:1.12.0")
}

// Fetches the CFW into assets/ BEFORE mergeAssets, so no binaries live in git.
// The build uses the network; the app does not. There is no committed fallback —
// a fetch failure fails the build (an APK missing the image would crash at runtime).
//
//   cfw.bin       the latest pebble-index-cfw release (raw DA14531_App.bin) with
//                 the 0x7051 header + CRC32 applied here (a port of mkimage.py).
//   versions.json {cfw} for the UI, from the same source.
val bundleFirmware by tasks.registering {
    val assets = file("src/main/assets")
    val cfwBin = File(assets, "cfw.bin")
    val versions = File(assets, "versions.json")
    outputs.files(cfwBin, versions)

    val cfwRawUrl = "https://github.com/elvisoliveira/pebble-index-cfw/releases/latest/download/DA14531_App.bin"
    val cfwLatestApi = "https://api.github.com/repos/elvisoliveira/pebble-index-cfw/releases/latest"

    // Wrap the raw DA14531 body in the bootloader's 64-byte 0x7051 image header
    // (a port of the SDK's mkimage.py). Little-endian throughout.
    fun mkimage(body: ByteArray): ByteArray {
        require(body.size <= 0x7FC0) { "cfw image too big: 0x${body.size.toString(16)}" }
        val header = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(byteArrayOf(0x70, 0x51, 0xAA.toByte(), 0x00))                    // magic + valid flag
            putInt(body.size)                                                    // body size
            putInt((CRC32().apply { update(body) }.value and 0xFFFFFFFFL).toInt())  // CRC32 of body
            put("CFW".toByteArray().copyOf(16))                                  // version[16]
            putInt(0)                                                            // timestamp
        }
        return header.array() + body
    }

    // The release tag, for the UI only. Best-effort: "latest" if the API call fails.
    fun latestReleaseTag(): String = try {
        @Suppress("UNCHECKED_CAST")
        ((JsonSlurper().parseText(URL(cfwLatestApi).readText()) as Map<String, Any>)["tag_name"] as String)
    } catch (e: Exception) { "latest" }

    doLast {
        assets.mkdirs()

        // Source: a locally-built firmware (-PcfwLocal=<path to DA14531_App.bin>, for
        // testing an unreleased build) or the latest pebble-index-cfw GitHub release.
        val local = findProperty("cfwLocal") as String?
        val body = if (local != null) File(local).readBytes()
                   else URL(cfwRawUrl).openStream().use { it.readBytes() }
        val version = if (local != null) "local" else latestReleaseTag()

        cfwBin.writeBytes(mkimage(body))
        versions.writeText("""{ "cfw": "$version" }""" + "\n")
        logger.lifecycle("bundleFirmware: cfw=$version (${cfwBin.length()} B)" +
            (local?.let { " [local: $it]" } ?: ""))
    }
}

tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(bundleFirmware) }
