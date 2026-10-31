package sn.tvremote.agent

import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors

/** Requête HTTP reçue (en-têtes en minuscules). */
data class HttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: String,
    val remoteAddress: String,
)

data class HttpResponse(val status: Int, val body: String)

/**
 * Serveur HTTP/1.1 minimal, sans dépendance : une requête par connexion
 * (Connection: close), corps JSON limité à 16 Kio.
 *
 * Suffisant pour une télécommande : quelques requêtes par seconde au plus.
 */
class HttpServer(
    private val port: Int,
    private val handler: (HttpRequest) -> HttpResponse,
) {
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    // 4 fils : appuis rapides successifs sans blocage, ressources bornées
    private val workers = Executors.newFixedThreadPool(4)

    fun start() {
        if (running) return
        running = true
        Thread({ acceptLoop() }, "tv-agent-http").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        workers.shutdownNow()
    }

    private fun acceptLoop() {
        try {
            val ss = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
            serverSocket = ss
            Log.i(TAG, "Serveur à l'écoute sur le port $port")
            while (running) {
                val client = try {
                    ss.accept()
                } catch (e: SocketException) {
                    break // socket fermé par stop()
                }
                workers.execute { serve(client) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Serveur arrêté : ${e.message}")
        }
    }

    private fun serve(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 5_000
                val input = BufferedInputStream(s.getInputStream())
                val request = parse(input, s.inetAddress?.hostAddress ?: "?")
                val response = if (request == null) {
                    HttpResponse(400, Json.error("requête invalide"))
                } else {
                    try {
                        handler(request)
                    } catch (e: Exception) {
                        Log.e(TAG, "Erreur de traitement", e)
                        HttpResponse(500, Json.error("erreur interne"))
                    }
                }
                write(s, response)
            } catch (e: Exception) {
                Log.w(TAG, "Connexion abandonnée : ${e.message}")
            }
        }
    }

    private fun parse(input: InputStream, remote: String): HttpRequest? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null

        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            if (headers.size > 50) return null
        }

        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length < 0 || length > MAX_BODY) return null
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val r = input.read(buf, read, length - read)
            if (r < 0) break
            read += r
        }
        return HttpRequest(
            method = parts[0].uppercase(),
            path = parts[1].substringBefore('?'),
            headers = headers,
            body = String(buf, 0, read, Charsets.UTF_8),
            remoteAddress = remote,
        )
    }

    /** Lit une ligne terminée par \n (le \r est ignoré). Null en fin de flux. */
    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var c = input.read()
        if (c == -1) return null
        while (c != -1 && c != '\n'.code) {
            if (c != '\r'.code) out.write(c)
            if (out.size() > MAX_LINE) return null
            c = input.read()
        }
        return out.toString("UTF-8")
    }

    private fun write(socket: Socket, response: HttpResponse) {
        val body = response.body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 ${response.status} ${reason(response.status)}\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(head.toByteArray(Charsets.US_ASCII))
            write(body)
            flush()
        }
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        422 -> "Unprocessable Entity"
        429 -> "Too Many Requests"
        503 -> "Service Unavailable"
        else -> "Internal Server Error"
    }

    private companion object {
        const val TAG = "TvAgentHttp"
        const val MAX_BODY = 16 * 1024
        const val MAX_LINE = 8 * 1024
    }
}
