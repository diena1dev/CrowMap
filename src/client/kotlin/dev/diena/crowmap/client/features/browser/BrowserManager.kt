package dev.diena.crowmap.client.features.browser

import com.mojang.blaze3d.systems.RenderSystem
import dev.diena.crowmap.client.CrowmapClient
import dev.diena.crowmap.client.config.CrowmapConfig
import net.ccbluex.liquidbounce.mcef.MCEF
import net.ccbluex.liquidbounce.mcef.cef.MCEFBrowser
import net.ccbluex.liquidbounce.mcef.cef.MCEFBrowserSettings
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import org.cef.CefApp

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

    /** Tick counter for periodic diagnostic logging. */
    private var tickCount = 0
    private var hasLoggedTextureReady = false

    /** Track if we've seen a paint event from CEF at all. */
    @Volatile
    private var paintEventReceived = false
    private var paintEventThread: String? = null

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
                    "texId=${renderer.textureId}, " +
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
        closeBrowser()
        if (initialized) {
            MCEFBackend.stop()
            initialized = false
        }
    }
}






