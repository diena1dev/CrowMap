package dev.diena.crowmap.client.features.browser

import com.mojang.blaze3d.systems.RenderSystem
import dev.diena.crowmap.client.CrowmapClient
import dev.diena.crowmap.client.config.CrowmapConfig
import net.ccbluex.liquidbounce.mcef.MCEF
import net.ccbluex.liquidbounce.mcef.cef.MCEFBrowser
import net.ccbluex.liquidbounce.mcef.cef.MCEFBrowserSettings
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import org.cef.CefApp
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandler
import org.cef.network.CefRequest
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages the shared MCEF browser instance used by the screen, HUD, and world projection.
 */
object BrowserManager {
    private val logger = CrowmapClient.logger

    /** The shared browser instance. */
    var browser: MCEFBrowser? = null
        private set

    /** Whether the MCEF backend has been initialized. */
    var initialized: Boolean = false
        private set

    /** Maximum browser resolution (keeps web content usable on HiDPI). */
    const val MAX_BROWSER_WIDTH = 1920
    const val MAX_BROWSER_HEIGHT = 1080

    /** Tick counter for periodic diagnostic logging. */
    private var tickCount = 0
    private var hasLoggedTextureReady = false

    /** Last window size used for browser resize — lets us detect changes. */
    private var lastWindowWidth = 0
    private var lastWindowHeight = 0

    /** Track if we've seen a paint event from CEF at all. */
    @Volatile
    private var paintEventReceived = false
    private var paintEventThread: String? = null

    // ── Custom CSS injection ─────────────────────────────────────────────

    /** Registered CSS snippets keyed by a caller-chosen id. Re-injected on every page load. */
    private val customCssMap = ConcurrentHashMap<String, String>()

    /** Whether the load handler for CSS re-injection has been installed. */
    private var cssLoadHandlerInstalled = false

    /**
     * Initializes the MCEF backend and registers diagnostic tick handler.
     * Should be called early in the client lifecycle.
     */
    fun init() {
        try {
            MCEFBackend.init()
            initialized = true
            logger.info("MCEF backend initialized successfully")
            logger.info("MCEF isInitialized=${MCEF.INSTANCE.isInitialized}, client=${MCEF.INSTANCE.client != null}")

            // Install the JS→Java message router used by WebDataReader
            WebDataReader.install()

            // Install load handler for CSS re-injection on every page load
            installCssLoadHandler()
        } catch (e: Exception) {
            logger.error("Failed to initialize MCEF backend", e)
            return
        }

        // Periodic diagnostics + CEF message loop pump + forced invalidation
        ClientTickEvents.END_CLIENT_TICK.register { _ ->
            if (!initialized || !MCEFBackend.isInitialized) return@register

            // Pump CEF's NATIVE message loop.
            // On macOS, the Java wrapper doMessageLoopWork(long) is a no-op,
            // but the native N_DoMessageLoopWork() actually processes pending CEF
            // IPC events — including asynchronous browser creation from N_CreateBrowser.
            try {
                CefApp.getInstance().N_DoMessageLoopWork()
            } catch (e: Exception) {
                if (tickCount % 200 == 0) {
                    logger.debug("[CrowMap] N_DoMessageLoopWork failed: ${e.message}")
                }
            }

            tickCount++
            val b = browser ?: return@register

            // Detect window resize and update the browser + projection accordingly
            val mc = CrowmapClient.mc
            val curW = mc.window.width
            val curH = mc.window.height
            if (curW != lastWindowWidth || curH != lastWindowHeight) {
                lastWindowWidth = curW
                lastWindowHeight = curH
                val (bw, bh) = computeBrowserSize()
                b.resize(bw, bh)
                // Let the world projection know the window changed
                dev.diena.crowmap.client.features.world.WorldProjectionScreen.onWindowResize()
            }

            // Every 5 seconds, log detailed diagnostic state
            if (tickCount % 100 == 0 && !hasLoggedTextureReady) {
                val renderer = b.renderer

                // Check native browser handle and loading state
                var nativeRef = 0L
                var browserUrl = "<error>"
                var loading = false
                var hasDoc = false
                var browserId = -1
                try {
                    nativeRef = b.getNativeRef("CefBrowser")
                    browserUrl = b.getURL() ?: "<null>"
                    loading = b.isLoading
                    hasDoc = b.hasDocument()
                    browserId = b.identifier
                } catch (e: Exception) {
                    logger.warn("[CrowMap Diag] Error querying browser state: ${e.message}")
                }

                logger.info(
                    "[CrowMap Diag] nativeRef=$nativeRef, id=$browserId, url=$browserUrl, " +
                    "loading=$loading, hasDoc=$hasDoc, " +
                    "textureReady=${b.isTextureReady}, " +
                    "texSize=${renderer.textureWidth}x${renderer.textureHeight}, " +
                    "unpainted=${renderer.isUnpainted}, " +
                    "accelerated=${renderer.isAccelerated}, " +
                    "paintReceived=$paintEventReceived, " +
                    "paintThread=$paintEventThread"
                )

                // Try to force a repaint by invalidating
                try {
                    b.clear() // calls invalidate()
                } catch (e: Exception) {
                    logger.debug("[CrowMap Diag] invalidate() failed: ${e.message}")
                }
            }

            if (b.isTextureReady && !hasLoggedTextureReady) {
                logger.info("[CrowMap] Browser texture is now READY! Size: ${b.renderer.textureWidth}x${b.renderer.textureHeight}")
                hasLoggedTextureReady = true

                // Auto-activate the world projection if it was enabled in config
                // (e.g. from a previous session) but hasn't been activated yet
                // because the browser wasn't ready at startup.
                if (CrowmapConfig.projectionEnabled && !dev.diena.crowmap.client.features.world.WorldProjectionScreen.isActive) {
                    dev.diena.crowmap.client.features.world.WorldProjectionScreen.activate()
                }
            }
        }
    }

    /**
     * Creates or returns the shared browser with the configured URL.
     */
    fun getOrCreateBrowser(width: Int = 1920, height: Int = 1080): MCEFBrowser? {
        if (!initialized) {
            logger.warn("Cannot create browser - BrowserManager not initialized")
            return null
        }
        if (!MCEFBackend.isInitialized) {
            logger.warn("Cannot create browser - MCEF backend not initialized")
            return null
        }
        if (!MCEF.INSTANCE.isInitialized) {
            logger.warn("Cannot create browser - MCEF.INSTANCE not initialized")
            return null
        }

        if (browser == null) {
            try {
                logger.info("[CrowMap] Creating browser on thread=${Thread.currentThread().name}, isRenderThread=${RenderSystem.isOnRenderThread()}")

                val settings = MCEFBrowserSettings(60, false)
                logger.info("[CrowMap] Creating browser for URL: ${CrowmapConfig.mapUrl} at size ${width}x${height}")

                val b = MCEFBackend.createBrowser(CrowmapConfig.mapUrl, settings)
                logger.info("[CrowMap] Browser object created: $b")

                // Add a paint listener to detect if CEF ever fires paint callbacks.
                // This fires AFTER MCEFBrowser.onPaint does its GL work (via super.onPaint).
                b.addOnPaintListener { event ->
                    if (!paintEventReceived) {
                        paintEventReceived = true
                        paintEventThread = Thread.currentThread().name
                        logger.info("[CrowMap] PAINT EVENT received! thread=${Thread.currentThread().name}, " +
                            "isRenderThread=${RenderSystem.isOnRenderThread()}, " +
                            "size=${event.width}x${event.height}, " +
                            "dirtyRects=${event.dirtyRects.size}")
                    }
                }

                // Resize the browser to the desired dimensions.
                // This triggers wasResized() which tells CEF to repaint at the new size.
                b.resize(width, height)
                logger.info("[CrowMap] Browser resized to ${width}x${height}")

                // Log initial renderer state
                val renderer = b.renderer
                logger.info("[CrowMap] Renderer state after creation: " +
                    "texReady=${b.isTextureReady}, " +
                    "texW=${renderer.textureWidth}, texH=${renderer.textureHeight}, " +
                    "identifier=${renderer.identifier}")

                // Check if the native browser was actually created
                val nativeRef = b.getNativeRef("CefBrowser")
                val url = b.getURL() ?: "<null>"
                val loading = b.isLoading
                logger.info("[CrowMap] Native browser check: nativeRef=$nativeRef, url=$url, loading=$loading, id=${b.identifier}")

                if (nativeRef == 0L) {
                    logger.error("[CrowMap] CRITICAL: Native browser handle is 0 — browser was NOT created by CEF!")
                    // Try to force reload
                    logger.info("[CrowMap] Attempting explicit loadURL...")
                    b.loadURL(CrowmapConfig.mapUrl)
                }

                browser = b
                hasLoggedTextureReady = false
                paintEventReceived = false
                paintEventThread = null

            } catch (e: Exception) {
                logger.error("[CrowMap] Failed to create browser", e)
                return null
            }
        }

        return browser
    }

    /**
     * Computes a browser resolution that matches the Minecraft window's aspect ratio
     * while staying within [MAX_BROWSER_WIDTH]×[MAX_BROWSER_HEIGHT].
     * Shared by BrowserScreen and WorldProjectionScreen.
     */
    fun computeBrowserSize(): Pair<Int, Int> {
        val mc = CrowmapClient.mc
        val winW = mc.window.width.coerceAtLeast(1)
        val winH = mc.window.height.coerceAtLeast(1)
        val aspect = winW.toDouble() / winH

        val w: Int
        val h: Int
        if (aspect >= MAX_BROWSER_WIDTH.toDouble() / MAX_BROWSER_HEIGHT) {
            w = MAX_BROWSER_WIDTH
            h = (MAX_BROWSER_WIDTH / aspect).toInt().coerceAtLeast(1)
        } else {
            h = MAX_BROWSER_HEIGHT
            w = (MAX_BROWSER_HEIGHT * aspect).toInt().coerceAtLeast(1)
        }
        return Pair(w, h)
    }

    /**
     * Resizes the shared browser.
     */
    fun resize(width: Int, height: Int) {
        browser?.resize(width, height)
    }

    /**
     * Navigates the shared browser to a new URL.
     */
    fun navigate(url: String) {
        browser?.loadURL(url)
    }

    /**
     * Closes and cleans up the shared browser.
     */
    fun closeBrowser() {
        browser?.close()
        browser = null
        hasLoggedTextureReady = false
    }

    /**
     * Shuts down the MCEF backend entirely.
     */
    fun shutdown() {
        WebDataReader.shutdown()
        closeBrowser()
        customCssMap.clear()
        if (initialized) {
            MCEFBackend.stop()
            initialized = false
        }
    }

    // ── Custom CSS injection API ─────────────────────────────────────────

    /**
     * Injects a CSS snippet into the currently loaded page. The snippet is
     * identified by [id] so it can be updated or removed later. If a snippet
     * with the same [id] already exists it is replaced.
     *
     * The CSS is automatically re-injected whenever the page reloads or
     * navigates to a new URL.
     *
     * @param id  A unique identifier for this CSS snippet (e.g. "hide-sidebar").
     * @param css The raw CSS text to inject.
     */
    fun injectCss(id: String, css: String) {
        customCssMap[id] = css
        // Inject immediately into the current page (if a browser exists)
        applyOneCss(id, css)
        logger.info("[CrowMap] CSS injected (id=$id, length=${css.length})")
    }

    /**
     * Removes a previously injected CSS snippet and strips it from the current page.
     *
     * @param id The identifier passed to [injectCss].
     */
    fun removeCss(id: String) {
        customCssMap.remove(id) ?: return
        // Remove the <style> element from the live page
        val removeJs = """
            (function(){
                var el = document.getElementById('crowmap-css-$id');
                if(el) el.remove();
            })();
        """.trimIndent()
        browser?.executeJavaScript(removeJs, browser?.url ?: "", 0)
        logger.info("[CrowMap] CSS removed (id=$id)")
    }

    /**
     * Removes all previously injected CSS snippets.
     */
    fun clearCss() {
        val ids = customCssMap.keys.toList()
        customCssMap.clear()
        val b = browser ?: return
        val removeJs = ids.joinToString("\n") { id ->
            "(function(){ var el = document.getElementById('crowmap-css-$id'); if(el) el.remove(); })();"
        }
        if (removeJs.isNotEmpty()) {
            b.executeJavaScript(removeJs, b.url ?: "", 0)
        }
        logger.info("[CrowMap] All custom CSS cleared")
    }

    /**
     * Returns an unmodifiable snapshot of the currently registered CSS snippets.
     */
    fun getInjectedCss(): Map<String, String> = customCssMap.toMap()

    // ── Internal CSS helpers ─────────────────────────────────────────────

    /**
     * Installs a [CefLoadHandler] on the MCEF client that re-injects all
     * registered CSS snippets after every page load.
     */
    private fun installCssLoadHandler() {
        if (cssLoadHandlerInstalled) return
        try {
            val mcefClient = MCEF.INSTANCE.client
            mcefClient.addLoadHandler(object : CefLoadHandler {
                override fun onLoadingStateChange(
                    browser: CefBrowser?, isLoading: Boolean,
                    canGoBack: Boolean, canGoForward: Boolean
                ) { /* no-op */ }

                override fun onLoadStart(
                    browser: CefBrowser?, frame: CefFrame?,
                    transitionType: CefRequest.TransitionType?
                ) { /* no-op */ }

                override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                    // Only inject into the main frame
                    if (frame == null || !frame.isMain) return
                    if (customCssMap.isEmpty()) return
                    logger.info("[CrowMap] Page load finished — re-injecting ${customCssMap.size} CSS snippet(s)")
                    applyAllCss()
                }

                override fun onLoadError(
                    browser: CefBrowser?, frame: CefFrame?,
                    errorCode: CefLoadHandler.ErrorCode?, errorText: String?, failedUrl: String?
                ) { /* no-op */ }
            })
            cssLoadHandlerInstalled = true
            logger.info("[CrowMap] CSS re-injection load handler installed")
        } catch (e: Exception) {
            logger.error("[CrowMap] Failed to install CSS load handler", e)
        }
    }

    /**
     * Applies a single CSS snippet to the current page by injecting a `<style>` element.
     */
    private fun applyOneCss(id: String, css: String) {
        val b = browser ?: return
        val escaped = css
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        val js = """
            (function(){
                var existing = document.getElementById('crowmap-css-$id');
                if(existing) existing.remove();
                var style = document.createElement('style');
                style.id = 'crowmap-css-$id';
                style.textContent = '$escaped';
                (document.head || document.documentElement).appendChild(style);
            })();
        """.trimIndent()
        b.executeJavaScript(js, b.url ?: "", 0)
    }

    /**
     * Re-applies all registered CSS snippets to the current page.
     */
    private fun applyAllCss() {
        customCssMap.forEach { (id, css) -> applyOneCss(id, css) }
    }
}






