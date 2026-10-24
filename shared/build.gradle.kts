import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.math.BigInteger
import java.net.URI
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp)
    alias(libs.plugins.sqldelight)
}

abstract class GenerateModelCatalogSource : DefaultTask() {
    @get:Input abstract val catalogueUrl: Property<String>
    @get:Input abstract val publicKey: Property<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val url = catalogueUrl.get()
        if (url.isNotEmpty()) {
            require(url.startsWith("https://", ignoreCase = true) && url.none {
                it.isWhitespace() || it.code <= 0x20 || it.code in 0x7f..0x9f || it == '\\' || it == '#'
            } && !Regex("%(?![0-9a-fA-F]{2})").containsMatchIn(url)) { "RHAPSODOS_CATALOG_URL must be a valid HTTPS URL" }
            val authority = url.substring(8).takeWhile { it != '/' && it != '?' }
            val parsed = requireNotNull(runCatching { URI("https://$authority") }.getOrNull()) {
                "RHAPSODOS_CATALOG_URL has an invalid host"
            }
            require(parsed.host != null && parsed.rawUserInfo == null && parsed.rawAuthority == authority) {
                "RHAPSODOS_CATALOG_URL must have a valid host without credentials"
            }
            if (!parsed.host.startsWith('[')) {
                val host = parsed.host.removeSuffix(".")
                val labels = host.split('.')
                require(host.length in 1..253 && labels.all {
                    it.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"))
                } && (labels.size != 4 || labels.any { label -> label.any { !it.isDigit() } } || labels.all {
                    (it.length == 1 || it.first() != '0') && it.toIntOrNull() in 0..255
                })) { "RHAPSODOS_CATALOG_URL has an invalid host" }
            }
            val port = authority.removePrefix(parsed.host)
            require(port.isEmpty() || port.matches(Regex(":[0-9]+")) && port.drop(1).toIntOrNull() in 1..65535) {
                "RHAPSODOS_CATALOG_URL has an invalid port"
            }
        }
        val encodedKey = publicKey.get().trim()
        require(url.isEmpty() || encodedKey.isNotEmpty()) {
            "Set RHAPSODOS_CATALOG_PUBLIC_KEY when RHAPSODOS_CATALOG_URL is configured"
        }
        if (encodedKey.isNotEmpty()) {
            val key = requireNotNull(runCatching { Base64.getDecoder().decode(encodedKey) }.getOrNull()) {
                "RHAPSODOS_CATALOG_PUBLIC_KEY must be Base64 uncompressed P-256"
            }
            require(key.size == 65 && key[0] == 4.toByte()) { "Catalogue public key must be uncompressed P-256" }
            val prefix = "3059301306072a8648ce3d020106082a8648ce3d030107034200".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val decoded = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(prefix + key)) as ECPublicKey
            val curve = decoded.params.curve
            val modulus = (curve.field as ECFieldFp).p
            val x = decoded.w.affineX
            val y = decoded.w.affineY
            require(x >= BigInteger.ZERO && x < modulus && y >= BigInteger.ZERO && y < modulus &&
                y.modPow(BigInteger.TWO, modulus) ==
                (x.modPow(BigInteger.valueOf(3), modulus) + curve.a * x + curve.b).mod(modulus)) {
                "RHAPSODOS_CATALOG_PUBLIC_KEY must be a point on P-256"
            }
        }
        val target = outputDirectory.file("com/ereader/shared/ModelCatalogSource.kt").get().asFile
        target.parentFile.mkdirs()
        target.writeText("package com.ereader.shared\n\nobject ModelCatalogSource {\n" +
            "    const val httpsUrl: String = ${literal(url)}\n" +
            "    const val publicKeyX963: String = ${literal(encodedKey)}\n}\n")
    }

    private fun literal(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\', '"', '$' -> append('\\').append(character)
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
        append('"')
    }
}

fun localCatalogueSetting(name: String) =
    providers.fileContents(rootProject.layout.projectDirectory.file(".env")).asText.orElse("").map { text ->
        val values = text.lineSequence().mapNotNull { line ->
            Regex("^\\s*(?:export\\s+)?${Regex.escape(name)}\\s*=\\s*(.*?)\\s*$").matchEntire(line)?.groupValues?.get(1)
        }.toList()
        require(values.size <= 1) { "Keep one $name assignment in .env" }
        values.singleOrNull()?.let { value ->
            if (value.length >= 2 && value.first() in listOf('\'', '"') && value.last() == value.first()) value.substring(1, value.lastIndex) else value
        }.orEmpty()
    }
val generateModelCatalogSource = tasks.register<GenerateModelCatalogSource>("generateModelCatalogSource") {
    catalogueUrl.set(providers.environmentVariable("RHAPSODOS_CATALOG_URL").orElse(localCatalogueSetting("RHAPSODOS_CATALOG_URL")))
    publicKey.set(providers.environmentVariable("RHAPSODOS_CATALOG_PUBLIC_KEY").orElse(localCatalogueSetting("RHAPSODOS_CATALOG_PUBLIC_KEY")))
    outputDirectory.set(layout.buildDirectory.dir("generated/modelCatalog/commonMain"))
}

kotlin {
    android {
        namespace = "com.osuji.ereaderapp.shared"
        compileSdk = 37
        minSdk = 28
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "SharedCore"
            isStatic = true
        }
    }
    sourceSets {
        named("commonMain") { kotlin.srcDir(generateModelCatalogSource.flatMap { it.outputDirectory }) }
        commonMain.dependencies {
            implementation(libs.serialization.json)
            implementation(libs.coroutines.core)
            implementation(libs.sqldelight.runtime)
            implementation(libs.ktor.client.core)
            implementation(libs.koin.core)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.native.driver)
            implementation(libs.ktor.client.darwin)
        }
    }
}

sqldelight {
    databases {
        create("ReaderDatabase") { packageName.set("com.ereader.shared.database") }
    }
}
