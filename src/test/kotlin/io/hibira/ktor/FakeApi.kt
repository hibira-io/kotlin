package io.hibira.ktor

import com.sun.net.httpserver.HttpServer
import io.hibira.HibiraClient
import io.hibira.HibiraOptions
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals

data class Seen(val method: String, val path: String, val query: Map<String, String>, val body: String)

class FakeApi(responses: List<Pair<Int, String>> = emptyList()) : AutoCloseable {
    val seen = mutableListOf<Seen>()
    private val queue = ArrayDeque(responses)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val body = ex.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val query = (ex.requestURI.rawQuery ?: "").split("&").filter { it.isNotEmpty() }.associate {
                val (k, v) = it.split("=", limit = 2) + ""
                URLDecoder.decode(k, StandardCharsets.UTF_8) to URLDecoder.decode(v, StandardCharsets.UTF_8)
            }
            synchronized(seen) { seen += Seen(ex.requestMethod, ex.requestURI.rawPath, query, body) }
            val (status, text) = synchronized(queue) { queue.removeFirstOrNull() } ?: (200 to "{}")
            val bytes = text.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
            ex.close()
        }
        start()
    }

    val client: HibiraClient = HibiraClient.create(
        HibiraOptions.builder().appId("1").key("app-key").secret("app-secret").host("127.0.0.1")
            .port(server.address.port).useTls(false).build(),
    )

    override fun close() = server.stop(0)
}

fun hmacHex(secret: String, message: ByteArray): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
    return mac.doFinal(message).joinToString("") { "%02x".format(it) }
}

fun assertSigned(s: Seen) {
    val q = s.query.filterKeys { it != "auth_signature" }.toSortedMap()
    val sorted = q.entries.joinToString("&") { "${it.key}=${it.value}" }
    val path = URLDecoder.decode(s.path.replace("+", "%2B"), StandardCharsets.UTF_8)
    assertEquals(hmacHex("app-secret", "${s.method}\n$path\n$sorted".toByteArray()), s.query["auth_signature"], "signature of $s")
    if (s.body.isNotEmpty()) {
        val md5 = MessageDigest.getInstance("MD5").digest(s.body.toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(md5, s.query["body_md5"])
    }
}

fun userQuery(s: Seen): Map<String, String> = s.query.filterKeys { !it.startsWith("auth_") && it != "body_md5" }
