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
        versionCode = 2
        versionName = "1.1"
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

    doLast {
        assets.mkdirs()

        // cfw: raw release body + 0x7051 header (mkimage.py port).
        val body = URL(cfwRawUrl).openStream().use { it.readBytes() }
        require(body.size <= 0x7FC0) { "cfw image too big: 0x${body.size.toString(16)}" }
        val bb = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(byteArrayOf(0x70, 0x51, 0xAA.toByte(), 0x00))
        bb.putInt(body.size)
        bb.putInt((CRC32().apply { update(body) }.value and 0xFFFFFFFFL).toInt())
        bb.put("CFW".toByteArray().copyOf(16))   // version[16]
        bb.putInt(0)                             // timestamp
        cfwBin.writeBytes(bb.array() + body)
        val cfwVer = try {
            @Suppress("UNCHECKED_CAST")
            ((JsonSlurper().parseText(URL(cfwLatestApi).readText()) as Map<String, Any>)["tag_name"] as String)
        } catch (e: Exception) { "latest" }

        versions.writeText("""{ "cfw": "$cfwVer" }""" + "\n")
        logger.lifecycle("bundleFirmware: cfw=$cfwVer (${cfwBin.length()} B)")
    }
}

tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(bundleFirmware) }
