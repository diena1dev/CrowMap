package dev.diena.crowmap.client.features.browser

import dev.diena.crowmap.client.CrowmapClient
import net.ccbluex.liquidbounce.mcef.MCEF
import net.ccbluex.liquidbounce.mcef.cef.MCEFBrowser
import net.ccbluex.liquidbounce.mcef.cef.MCEFBrowserSettings
import org.cef.CefApp
import java.io.File

object MCEFBackend {
    private val mc = CrowmapClient.mc
    private val logger = CrowmapClient.logger

    private val mcefFolder = File(
        mc.gameDirectory, "CrowMap"
    ).apply {
        // Check if there is already a config folder and if not create new folder
        // (mkdirs not needed - .minecraft should always exist)
        if (!exists()) {
            mkdir()
        }
    }.resolve("mcef")
    private val librariesFolder = mcefFolder.resolve("libraries")
    private val cacheFolder = mcefFolder.resolve("cache")

    val isInitialized: Boolean
        get() = MCEF.INSTANCE.isInitialized
    var browsers = mutableListOf<MCEFBrowser>()

    fun init() {
        // Clean up old cache directories
        cleanup()

        CrowmapClient.debug("[MCEFBackend] Starting init. isInitialized=${MCEF.INSTANCE.isInitialized}")

        if (!MCEF.INSTANCE.isInitialized) {
            MCEF.INSTANCE.settings.apply {
                // Uses a natural user agent to prevent websites from blocking the browser
                userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                cacheDirectory = cacheFolder.resolve(System.currentTimeMillis().toString(16)).apply {
                    deleteOnExit()
                }
                librariesDirectory = librariesFolder
            }

            CrowmapClient.debug("[MCEFBackend] Settings configured. librariesDir=$librariesFolder")

            val resourceManager = MCEF.INSTANCE.newResourceManager()
            CrowmapClient.debug("[MCEFBackend] ResourceManager created. compatible=${resourceManager.isSystemCompatible}, requiresDownload=${resourceManager.requiresDownload()}")

            // Check if system is compatible with MCEF (JCEF)
            if (!resourceManager.isSystemCompatible) {
                throw Exception("This system does not support MCEF!")
            }

            //HashValidator.validateFolder(resourceManager.commitDirectory)
            // TODO: verify download hash

            if (resourceManager.requiresDownload()) {
                logger.info("[MCEFBackend] Downloading JCEF...")
                resourceManager.downloadJcef()
                logger.info("[MCEFBackend] JCEF download complete")
            }

            CrowmapClient.debug("[MCEFBackend] Calling MCEF.INSTANCE.initialize()...")
            val initResult = MCEF.INSTANCE.initialize()
            CrowmapClient.debug("[MCEFBackend] MCEF.initialize() returned: $initResult")
            CrowmapClient.debug("[MCEFBackend] Post-init state: isInitialized=${MCEF.INSTANCE.isInitialized}")

            // Validate initialization actually succeeded
            if (!MCEF.INSTANCE.isInitialized) {
                throw Exception("MCEF.initialize() returned $initResult but isInitialized is still false!")
            }

            // Log CefApp state
            try {
                val cefAppState = CefApp.getState()
                CrowmapClient.debug("[MCEFBackend] CefApp.getState()=$cefAppState")
            } catch (e: Exception) {
                logger.warn("[MCEFBackend] Could not get CefApp state: ${e.message}")
            }

            logger.info("[MCEFBackend] MCEF initialized successfully")
        } else {
            CrowmapClient.debug("[MCEFBackend] MCEF was already initialized, skipping init block")
        }
    }

    /**
     * Cleans up old cache directories.
     *
     * TODO: Check if we have an active PID using the cache directory, if so, check if the LiquidBounce
     *   process attached to the JCEF PID is still running or not. If not, we could kill the JCEF process
     *   and clean up the cache directory.
     */
    private fun cleanup() {
        if (cacheFolder.exists()) {
            runCatching {
                cacheFolder.listFiles()
                    ?.filter { file ->
                        file.isDirectory && System.currentTimeMillis() - file.lastModified() > 100
                    }
                    ?.sumOf { file ->
                        try {
                            val fileSize = file.walkTopDown().sumOf { uFile -> uFile.length() }
                            file.deleteRecursively()
                            fileSize
                        } catch (e: Exception) {
                            logger.error("Failed to clean up old cache directory", e)
                            0
                        }
                    } ?: 0
            }.onFailure {
                // Not a big deal, not fatal.
                logger.error("Failed to clean up old JCEF cache directories", it)
            }.onSuccess { size ->
                if (size > 0) {
                    CrowmapClient.debug("Cleaned up ${size} JCEF cache directories")
                }
            }
        }
    }

    fun start() {
        if (!MCEF.INSTANCE.isInitialized) {
            MCEF.INSTANCE.initialize()
        }
    }

    fun stop() {
        MCEF.INSTANCE.shutdown()
        MCEF.INSTANCE.settings.cacheDirectory?.deleteRecursively()
    }

    fun createBrowser(
        url: String,
        settings: MCEFBrowserSettings,
    ): MCEFBrowser {
        CrowmapClient.debug("[MCEFBackend] createBrowser called. url=$url, isInitialized=${MCEF.INSTANCE.isInitialized}")

        // Capture stderr to detect UnsatisfiedLinkError from N_CreateBrowser
        val oldStderr = System.err
        val stderrCapture = java.io.ByteArrayOutputStream()
        val teeStream = java.io.PrintStream(object : java.io.OutputStream() {
            override fun write(b: Int) {
                oldStderr.write(b)
                stderrCapture.write(b)
            }
            override fun write(b: ByteArray, off: Int, len: Int) {
                oldStderr.write(b, off, len)
                stderrCapture.write(b, off, len)
            }
        })

        try {
            System.setErr(teeStream)

            val browser = MCEF.INSTANCE.createBrowser(url, true, settings)
            CrowmapClient.debug("[MCEFBackend] MCEF.createBrowser returned: $browser")

            // Check native handle — on macOS, N_CreateBrowser is asynchronous.
            // Pump the native message loop to process the pending creation.
            var nativeRef = browser.getNativeRef("CefBrowser")
            CrowmapClient.debug("[MCEFBackend] After createBrowser: nativeRef=$nativeRef (before pump)")

            if (nativeRef == 0L) {
                // Pump native CEF message loop to process async browser creation
                try {
                    val cefApp = CefApp.getInstance()
                    repeat(10) {
                        cefApp.N_DoMessageLoopWork()
                        Thread.sleep(50)
                    }
                } catch (e: Exception) {
                    logger.warn("[MCEFBackend] N_DoMessageLoopWork pump failed: ${e.message}")
                }

                nativeRef = browser.getNativeRef("CefBrowser")
                CrowmapClient.debug("[MCEFBackend] After pumping message loop: nativeRef=$nativeRef")
            }

            if (nativeRef == 0L) {
                logger.error("[MCEFBackend] Native browser handle is 0! N_CreateBrowser likely failed.")

                // Check captured stderr for clues
                val stderrOutput = stderrCapture.toString().trim()
                if (stderrOutput.isNotEmpty()) {
                    logger.error("[MCEFBackend] STDERR captured during browser creation:\n$stderrOutput")
                }

                // Try diagnostic: create browser through CefClient directly
                CrowmapClient.debug("[MCEFBackend] Attempting diagnostic: CefClient.createBrowser(\"about:blank\", true)...")
                try {
                    val cefClient = MCEF.INSTANCE.client.handle
                    val testBrowser = cefClient.createBrowser("about:blank", true)
                    val testRef = (testBrowser as? org.cef.callback.CefNativeAdapter)?.getNativeRef("CefBrowser") ?: -99L
                    CrowmapClient.debug("[MCEFBackend] CefClient.createBrowser diagnostic: class=${testBrowser.javaClass.name}, nativeRef=$testRef")
                    if (testRef != 0L && testRef != -99L) {
                        CrowmapClient.debug("[MCEFBackend] Standard CefClient.createBrowser WORKS! Issue is with MCEFBrowser construction.")
                    }
                    testBrowser.close(true)
                } catch (e: Exception) {
                    logger.error("[MCEFBackend] CefClient.createBrowser diagnostic also failed: ${e.javaClass.name}: ${e.message}", e)
                }

                // Try calling createImmediately again
                CrowmapClient.debug("[MCEFBackend] Attempting retry: browser.createImmediately()...")
                try {
                    browser.createImmediately()
                    val retryRef = browser.getNativeRef("CefBrowser")
                    CrowmapClient.debug("[MCEFBackend] After retry createImmediately: nativeRef=$retryRef")
                } catch (e: Exception) {
                    logger.error("[MCEFBackend] Retry createImmediately failed: ${e.javaClass.name}: ${e.message}")
                }
            }

            browsers.add(browser)
            return browser
        } finally {
            System.setErr(oldStderr)
        }
    }

    private fun addBrowser(browser: MCEFBrowser) {
        browsers.add(browser)
    }

}