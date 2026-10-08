package com.osuji.ereaderapp.models

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECFieldFp
import java.security.spec.ECPublicKeySpec
import java.util.Base64

object ModelCatalogVerifier {
    const val MAX_ENVELOPE_BYTES = 3 * 1024 * 1024
    private const val MAX_PAYLOAD_BYTES = 2 * 1024 * 1024

    fun verify(envelope: ByteArray, publicKeyX963: String): String {
        require(envelope.size in 1..MAX_ENVELOPE_BYTES) { "The model catalogue exceeds its size limit." }
        val fields = Json.parseToJsonElement(utf8(envelope)).jsonObject
        require(fields.keys == setOf("payload", "signature")) { "The model catalogue has an invalid signature envelope." }
        val payload = decode(fields.getValue("payload").jsonPrimitive.contentOrNull ?: error("Missing catalogue payload"))
        val signature = decode(fields.getValue("signature").jsonPrimitive.contentOrNull ?: error("Missing catalogue signature"))
        require(payload.size in 1..MAX_PAYLOAD_BYTES && signature.size in 8..80) { "The model catalogue has an invalid size." }
        val encodedKey = decode(publicKeyX963)
        require(encodedKey.size == 65 && encodedKey[0] == 4.toByte()) { "The catalogue verification key is invalid." }
        val parameters = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
        val point = ECPoint(BigInteger(1, encodedKey.copyOfRange(1, 33)), BigInteger(1, encodedKey.copyOfRange(33, 65)))
        val prime = (parameters.curve.field as ECFieldFp).p
        require(point.affineX < prime && point.affineY < prime && point.affineY.modPow(BigInteger.valueOf(2), prime) == (point.affineX.modPow(BigInteger.valueOf(3), prime) + parameters.curve.a * point.affineX + parameters.curve.b).mod(prime)) { "The catalogue verification key is invalid." }
        val key = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, parameters))
        val verified = Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(payload); verify(signature) }
        require(verified) { "The model catalogue signature could not be verified." }
        return utf8(payload)
    }

    private fun decode(value: String): ByteArray {
        require(value.isNotEmpty() && value.matches(Regex("[A-Za-z0-9+/]*={0,2}"))) { "The catalogue encoding is invalid." }
        return Base64.getDecoder().decode(value)
    }

    private fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
}
