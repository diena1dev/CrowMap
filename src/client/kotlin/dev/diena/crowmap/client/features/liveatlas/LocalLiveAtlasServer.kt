package dev.diena.crowmap.client.features.liveatlas

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.diena.crowmap.client.CrowmapClient
import dev.diena.crowmap.client.config.CrowmapConfig
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.BindException
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

object LocalLiveAtlasServer {
    private val logger = CrowmapClient.logger

    private const val VERSION = "3.1.0"
    private const val DOWNLOAD_URL =
        "https://github.com/JLyne/LiveAtlas/releases/download/v$VERSION/live-atlas-$VERSION.zip"

    enum class State { IDLE, DOWNLOADING, READY, ERROR }

    @Volatile var state: State = State.IDLE
        private set

    private var httpServer: HttpServer? = null
    @Volatile private var _localUrl: String = "http://127.0.0.1:25581/"
    val localUrl: String get() = _localUrl

    private val http = OkHttpClient()

    private val cacheDir: File
        get() = File(Minecraft.getInstance().gameDirectory, "config/crowmap/liveatlas-$VERSION")

    private val MIME = mapOf(
        "html"  to "text/html; charset=utf-8",
        "js"    to "application/javascript",
        "css"   to "text/css",
        "json"  to "application/json",
        "png"   to "image/png",
        "jpg"   to "image/jpeg",
        "ico"   to "image/x-icon",
        "svg"   to "image/svg+xml",
        "woff"  to "font/woff",
        "woff2" to "font/woff2",
        "ttf"   to "font/ttf"
    )

    // ── Lifecycle ─────────────────────────────────────────────────────────

    /**
     * Downloads LiveAtlas if not cached, then starts the local HTTP server.
     * [onReady] fires on the background thread once the server is up.
     */
    fun startAsync(onReady: (() -> Unit)? = null) {
        if (state == State.READY || state == State.DOWNLOADING) return
        state = State.DOWNLOADING

        Thread {
            try {
                val dir = cacheDir
                if (!File(dir, "index.html").exists()) {
                    logger.info("[LiveAtlas] Downloading v$VERSION (~150 KB)…")
                    downloadAndExtract(dir)
                    logger.info("[LiveAtlas] Download complete")
                }
                launchServer(dir)
                state = State.READY
                logger.info("[LiveAtlas] Local server ready at $_localUrl")
                onReady?.invoke()
            } catch (e: Exception) {
                logger.error("[LiveAtlas] Startup failed", e)
                state = State.ERROR
            }
        }.apply {
            isDaemon = true
            name = "crowmap-liveatlas"
        }.start()
    }

    fun stop() {
        httpServer?.stop(0)
        httpServer = null
        state = State.IDLE
    }

    /**
     * Registers a tick handler that reacts to runtime config toggles of
     * [CrowmapConfig.useLocalLiveAtlas].
     */
    fun registerConfigWatcher(
        onEnable: (String) -> Unit,
        onDisable: () -> Unit
    ) {
        var last = CrowmapConfig.useLocalLiveAtlas
        ClientTickEvents.END_CLIENT_TICK.register { _ ->
            val cur = CrowmapConfig.useLocalLiveAtlas
            if (cur == last) return@register
            last = cur
            if (cur) {
                startAsync { onEnable(localUrl) }
            } else {
                stop()
                onDisable()
            }
        }
    }

    // ── Download & extraction ─────────────────────────────────────────────

    private fun downloadAndExtract(destDir: File) {
        destDir.mkdirs()

        val request = Request.Builder().url(DOWNLOAD_URL).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body

            // Buffer all entries in memory first so we can detect a common ZIP prefix
            val entries = mutableListOf<Pair<String, ByteArray>>()
            ZipInputStream(body.byteStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        entries += Pair(entry.name.replace('\\', '/'), zis.readBytes())
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            // Strip a common leading directory if the ZIP wraps everything in one
            val prefix = entries
                .map { it.first.substringBefore('/') }
                .toSet()
                .singleOrNull()
                ?.let { if (entries.all { e -> e.first.startsWith("$it/") }) "$it/" else "" }
                ?: ""

            entries.forEach { (name, bytes) ->
                val rel = name.removePrefix(prefix).ifEmpty { return@forEach }
                val out = File(destDir, rel)
                if (!out.canonicalPath.startsWith(destDir.canonicalPath)) return@forEach
                out.parentFile?.mkdirs()
                out.writeBytes(bytes)
            }
        }
    }

    // ── HTTP server ───────────────────────────────────────────────────────

    private fun launchServer(distDir: File) {
        val server = try {
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 128)
        } catch (e: BindException) {
            throw IOException("Could not bind LiveAtlas server", e)
        }
        server.createContext("/") { ex ->
            try { handleRequest(ex, distDir) }
            catch (e: Exception) {
                CrowmapClient.debug("[LiveAtlas] Handler error: ${e.message}")
                runCatching { ex.sendResponseHeaders(500, -1); ex.close() }
            }
        }
        server.executor = Executors.newCachedThreadPool()
        server.start()
        httpServer = server
        _localUrl = "http://127.0.0.1:${server.address.port}/"
    }

    private fun handleRequest(exchange: HttpExchange, distDir: File) {
        val rawPath = exchange.requestURI.path.trimStart('/')
        val filePath = rawPath.ifEmpty { "index.html" }
        val candidate = File(distDir, filePath)

        if (candidate.isFile &&
            candidate.canonicalPath.startsWith(distDir.canonicalPath + File.separator)) {
            serveFile(exchange, candidate)
        } else {
            proxyToDynmap(exchange)
        }
    }

    private fun serveFile(exchange: HttpExchange, file: File) {
        val ct = MIME[file.extension.lowercase()] ?: "application/octet-stream"
        val bytes = if (file.name == "index.html") {
            injectLiveAtlasConfig(file.readText()).toByteArray(Charsets.UTF_8)
        } else {
            file.readBytes()
        }
        exchange.responseHeaders.set("Content-Type", ct)
        exchange.responseHeaders.set("Cache-Control", "no-cache")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun injectLiveAtlasConfig(html: String): String {
        val c = CrowmapConfig
        val script = """<script>
(function(){var d=window.liveAtlasConfig||{};window.liveAtlasConfig=Object.assign({},d,{ui:Object.assign({},d.ui||{},{
    playersAboveMarkers:  ${c.liveAtlasPlayersAboveMarkers},
    playersSearch:        ${c.liveAtlasPlayersSearch},
    compactPlayerMarkers: ${c.liveAtlasCompactPlayerMarkers},
    disableContextMenu:   true,
    disableMarkerUI:      false
})});})();
</script>"""
        return if ("</body>" in html) html.replace("</body>", "$script\n</body>")
               else "$html\n$script"
    }

    private fun proxyToDynmap(exchange: HttpExchange) {
        val base = CrowmapConfig.mapUrl.trimEnd('/')
        val target = "$base${exchange.requestURI}"
        try {
            http.newCall(Request.Builder().url(target).build()).execute().use { resp ->
                val body = resp.body
                val bytes = body.bytes()
                body.contentType()?.toString()?.let { ct ->
                    exchange.responseHeaders.set("Content-Type", ct)
                }
                exchange.sendResponseHeaders(resp.code, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        } catch (e: Exception) {
            CrowmapClient.debug("[LiveAtlas] Proxy error for $target: ${e.message}")
            exchange.sendResponseHeaders(502, -1)
            exchange.close()
        }
    }
}
