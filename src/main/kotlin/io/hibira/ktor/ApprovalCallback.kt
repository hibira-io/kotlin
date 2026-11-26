package io.hibira.ktor

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/** A verified Approvals callback: `approval.decided` or `approval.expired`. */
public data class ApprovalCallback(
    val id: String,
    val type: String,
    /** The approval, with `status`, `decision` and the rest. */
    val approval: JsonNode,
    val raw: JsonNode,
)

/** How old (seconds) a callback's timestamp may be. */
public const val CALLBACK_TOLERANCE_SECONDS: Long = 300

private val mapper = ObjectMapper()

/**
 * Checks a `callback_url` delivery (signed with the app secret) or an Approvals
 * workspace webhook (with that endpoint's secret). `signatureHeader` is the
 * `Hibira-Signature` header, `t=<unix>,v1=<hex>`; `body` is the raw body.
 * Returns null when the signature, the timestamp or the body is wrong.
 */
public fun verifyApprovalCallback(
    secret: String,
    signatureHeader: String?,
    body: ByteArray,
    nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
): ApprovalCallback? {
    if (secret.isEmpty() || signatureHeader.isNullOrEmpty()) return null
    var ts: String? = null
    val sigs = mutableListOf<String>()
    for (part in signatureHeader.split(',')) {
        val i = part.indexOf('=')
        if (i < 0) continue
        when (part.substring(0, i).trim()) {
            "t" -> ts = part.substring(i + 1).trim()
            "v1" -> sigs += part.substring(i + 1).trim()
        }
    }
    val t = ts?.toLongOrNull() ?: return null
    if (sigs.isEmpty() || abs(nowEpochSeconds - t) > CALLBACK_TOLERANCE_SECONDS) return null
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
    mac.update("$ts.".toByteArray())
    val want = mac.doFinal(body)
    val ok = sigs.any { s -> decodeHex(s)?.let { MessageDigest.isEqual(it, want) } == true }
    if (!ok) return null
    val node = runCatching { mapper.readTree(body) }.getOrNull() ?: return null
    val approval = node.path("data").path("approval")
    if (!node.isObject || approval.isMissingNode) return null
    return ApprovalCallback(node.path("id").asText(), node.path("type").asText(), approval, node)
}

private fun decodeHex(s: String): ByteArray? {
    if (s.length % 2 != 0) return null
    return runCatching { ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }.getOrNull()
}
