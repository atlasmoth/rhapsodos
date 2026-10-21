package com.ereader.shared

internal object PhononCatalog {
    private const val androidId = "android-phonon-english"
    private const val defaultOnlyVersion = "0.4.0-eos1-52433c46975b"
    private const val expandedVersion = "0.4.0-eos1-52433c46975b-voices-da6c9ec"

    fun resolve(pack: ModelPack): ModelPack {
        val profile = ModelCatalog.packs.firstOrNull { it.id == pack.adapter } ?: return pack
        return if (isDefaultOnlyAndroid(pack, profile)) {
            profile.copy(name = pack.name, description = pack.description)
        } else {
            pack.copy(voices = profile.voices)
        }
    }

    fun supportsInstalledUpgrade(pack: ModelPack, installedPath: String): Boolean {
        val profile = ModelCatalog.packs.firstOrNull { it.id == androidId } ?: return false
        return profile.version == expandedVersion &&
            installedPath == "Models/$androidId/$defaultOnlyVersion" &&
            pack.copy(name = profile.name, description = profile.description) == profile
    }

    private fun isDefaultOnlyAndroid(pack: ModelPack, profile: ModelPack): Boolean {
        if (profile.id != androidId || profile.version != expandedVersion) return false
        val coreFiles = profile.files.filterNot { it.path.startsWith("voices/") }
        val previous = profile.copy(version = defaultOnlyVersion, files = coreFiles,
            totalBytes = coreFiles.sumOf { it.bytes }, voices = emptyList(), name = pack.name, description = pack.description)
        return pack.copy(voices = emptyList()) == previous
    }
}
