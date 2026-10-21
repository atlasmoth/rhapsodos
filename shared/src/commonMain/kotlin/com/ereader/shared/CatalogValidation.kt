package com.ereader.shared

internal object CatalogValidation {
    private val identifier = Regex("[a-z0-9][a-z0-9-]{0,95}")
    private val version = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}")
    private val asset = Regex("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*")
    private val hash = Regex("[a-f0-9]{64}")
    private val download = Regex("https://(?:huggingface\\.co/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/resolve|raw\\.githubusercontent\\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)/[a-f0-9]{40}/[A-Za-z0-9_./-]+")

    fun validate(update: ModelCatalogUpdate, current: List<ModelPack>, now: Long) {
        require(update.formatVersion == 1 && update.revision > 0) { "Unsupported catalogue format" }
        require(update.publishedAt > 0 && update.publishedAt <= now + 300_000) { "Catalogue update has an invalid publication date" }
        update.expiresAt?.let { expiry ->
            require(expiry > now && expiry > update.publishedAt && expiry - update.publishedAt <= 90L * 86_400_000) {
                "Catalogue update is expired or has invalid dates"
            }
        }
        require(update.packs.size in 1..64 && update.packs.map { it.id }.toSet().size == update.packs.size) { "Invalid catalogue entries" }
        update.packs.forEach { pack ->
            val profile = ModelCatalog.packs.firstOrNull { it.id == pack.adapter } ?: error("This app cannot run the catalogue's model adapter")
            require(identifier.matches(pack.id) && version.matches(pack.version) && pack.version !in setOf(".", "..")) { "Invalid model identity" }
            require(pack.platform == profile.platform && pack.runtime == profile.runtime && pack.capability == profile.capability &&
                pack.minimumOS >= profile.minimumOS && pack.minimumMemoryMB >= profile.minimumMemoryMB) { "Model adapter requirements do not match this app" }
            require(pack.name.length in 1..120 && pack.name.isNotBlank() && pack.description.length in 1..2_000 &&
                pack.license.length in 1..200 && pack.licenseUrl.startsWith("https://") && pack.licenseUrl.length <= 2_000) { "Invalid model description" }
            require(pack.languageTags.isNotEmpty() && pack.languageTags.size <= 16 &&
                pack.languageTags.all { it in profile.languageTags }) { "Unsupported model language" }
            require(pack.files.size == profile.files.size && pack.files.map { it.path }.toSet() == profile.files.map { it.path }.toSet() &&
                pack.files.map { it.path }.toSet().size == pack.files.size) { "Model files do not match this app's adapter" }
            pack.files.forEach { file ->
                require(asset.matches(file.path) && file.path.split('/').none { it == "." || it == ".." } &&
                    hash.matches(file.sha256) && file.bytes in 1..1_073_741_824L && download.matches(file.url) &&
                    file.url.substringAfter("https://").split('/').none { it == "." || it == ".." }) { "Invalid pinned model asset" }
            }
            require(pack.totalBytes == pack.files.sumOf { it.bytes } && pack.totalBytes <= 2_147_483_648L) { "Invalid model storage size" }
            current.firstOrNull { it.id == pack.id }?.let { prior ->
                require(pack.copy(name = prior.name, description = prior.description) == prior) { "A model edition needs a new identity" }
            }
        }
    }
}
