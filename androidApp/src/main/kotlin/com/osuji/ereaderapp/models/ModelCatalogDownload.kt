package com.osuji.ereaderapp.models

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.URI
import kotlin.coroutines.coroutineContext

internal suspend fun downloadModelCatalog(client: HttpClient, source: String, publicKey: String): String = withContext(Dispatchers.IO) {
    val origin = URI(source)
    require(origin.scheme == "https" && origin.host != null && origin.userInfo == null && origin.fragment == null && (origin.port == -1 || origin.port == 443)) { "The catalogue address is invalid." }
    var url = source
    repeat(4) {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host.equals(origin.host, ignoreCase = true) && uri.userInfo == null && uri.fragment == null && (uri.port == -1 || uri.port == 443)) { "The catalogue redirected outside its approved host." }
        var redirect: String? = null
        var payload: String? = null
        client.prepareGet(url) {
            timeout { connectTimeoutMillis = 15_000; socketTimeoutMillis = 15_000; requestTimeoutMillis = 30_000 }
        }.execute { response ->
            if (response.status.value in 300..399) {
                redirect = response.headers[HttpHeaders.Location]?.let { uri.resolve(it).toString() }
                require(redirect != null) { "The catalogue returned an invalid redirect." }
            } else {
                require(response.status == HttpStatusCode.OK) { "The model catalogue could not be reached. Try again later." }
                response.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let { require(it in 1..ModelCatalogVerifier.MAX_ENVELOPE_BYTES.toLong()) { "The model catalogue exceeds its size limit." } }
                val channel = response.bodyAsChannel()
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = channel.readAvailable(buffer, 0, buffer.size)
                    if (count < 0) break
                    if (count == 0) continue
                    require(output.size() + count <= ModelCatalogVerifier.MAX_ENVELOPE_BYTES) { "The model catalogue exceeds its size limit." }
                    output.write(buffer, 0, count)
                }
                payload = ModelCatalogVerifier.verify(output.toByteArray(), publicKey)
            }
        }
        payload?.let { return@withContext it }
        url = redirect ?: error("The catalogue response was empty.")
    }
    error("The catalogue redirected too many times.")
}
