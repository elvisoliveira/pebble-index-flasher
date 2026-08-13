import groovy.json.JsonSlurper
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
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
        versionCode = 1
        versionName = "1.0"
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

// Fetches both firmwares into assets/ BEFORE mergeAssets, so no binaries live in
// git. The build uses the network; the app does not. There is no committed
// fallback — a fetch failure fails the build (an APK missing an image would crash
// at runtime).
//
//   official.bin  the factory firmware, base64-embedded in the official update
//                 manifest (already 0x7051-headered) — decoded verbatim.
//   cfw.bin       the latest pebble-index-cfw release (raw DA14531_App.bin) with
//                 the 0x7051 header + CRC32 applied here (a port of mkimage.py).
//   versions.json {official, cfw} for the UI, from the same two sources.
val bundleFirmware by tasks.registering {
    val assets = file("src/main/assets")
    val officialBin = File(assets, "official.bin")
    val cfwBin = File(assets, "cfw.bin")
    val versions = File(assets, "versions.json")
    outputs.files(officialBin, cfwBin, versions)

    val manifestUrl = "https://raw.githubusercontent.com/HyperionSensing/firmware_releases/main/haversine_update.json"
    val cfwRawUrl = "https://github.com/elvisoliveira/pebble-index-cfw/releases/latest/download/DA14531_App.bin"
    val cfwLatestApi = "https://api.github.com/repos/elvisoliveira/pebble-index-cfw/releases/latest"

    doLast {
        assets.mkdirs()

        // official: decode the base64 image from the update manifest, as-is.
        @Suppress("UNCHECKED_CAST")
        val manifest = JsonSlurper().parseText(URL(manifestUrl).readText()) as Map<String, Any>
        val officialImage = Base64.getMimeDecoder().decode(manifest["image"] as String)
        require(officialImage.size >= 4 && officialImage[0].toInt() and 0xFF == 0x70 &&
            officialImage[1].toInt() and 0xF0 == 0x50) { "manifest image is not a 0x7051 image" }
        officialBin.writeBytes(officialImage)
        val officialVer = "${manifest["firmwareVersionMajor"]}.${manifest["firmwareVersionMinor"]}"

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

        versions.writeText("""{ "official": "$officialVer", "cfw": "$cfwVer" }""" + "\n")
        logger.lifecycle("bundleFirmware: official=$officialVer (${officialImage.size} B), cfw=$cfwVer (${cfwBin.length()} B)")
    }
}

tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(bundleFirmware) }
