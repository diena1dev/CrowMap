package dev.diena.crowmap.client

import dev.diena.crowmap.client.config.CrowmapConfig
import dev.diena.crowmap.client.features.browser.BrowserManager
import dev.diena.crowmap.client.features.browser.PlayerMapMarker
import dev.diena.crowmap.client.features.hud.MapHudOverlay
import dev.diena.crowmap.client.features.hud.OverlayHud
import dev.diena.crowmap.client.features.world.SignAnchorTracker
import io.github.trethore.graphene.api.Graphene
import io.github.trethore.graphene.api.GrapheneContext
import net.fabricmc.api.ClientModInitializer
import dev.diena.crowmap.client.config.Keybindings
import net.minecraft.client.Minecraft
import org.apache.logging.log4j.LogManager

class CrowmapClient : ClientModInitializer {
    companion object {
        val mc: Minecraft = Minecraft.getInstance()
        val logger = LogManager.getLogger("Crowmap")
        val namespace = "crowmap"

        private var grapheneContext: GrapheneContext? = null

        /** The Graphene context registered for this mod. Valid after [onInitializeClient] runs. */
        fun graphene(): GrapheneContext =
            grapheneContext ?: throw IllegalStateException("CrowMap has not initialized Graphene yet")

        fun debug(message: String) {
            if (CrowmapConfig.debugLogging) logger.info("[DEBUG] $message")
        }
    }

    override fun onInitializeClient() {
        // On macOS, JCEF's org.cef.CefApp lazily inits AWT's Toolkit via
        // SwingUtilities.invokeLater the first time it runs (later, on a
        // background thread once Graphene starts its CEF runtime). If GLFW
        // has already claimed the Cocoa main thread by then (-XstartOnFirstThread),
        // that lazy Toolkit init deadlocks forever fighting GLFW for NSApplication
        // ownership. Force it here, before GLFW/Minecraft's window exists, so AWT
        // and GLFW don't race for the same Cocoa main-thread handshake.
        if (System.getProperty("os.name").contains("Mac")) {
            java.awt.Toolkit.getDefaultToolkit()
        }

        // Load config from disk (or create defaults) before anything else reads it
        CrowmapConfig.wrapper

        // Initialize keybindings
        Keybindings().init()

        // Register with Graphene before touching any browser APIs
        grapheneContext = Graphene.register(CrowmapClient::class.java)

        // LiveAtlas support is dropped; dev.diena.crowmap.client.features.liveatlas.LocalLiveAtlasServer
        // is kept in the tree but intentionally unwired.

        // Initialize Graphene browser backend
        BrowserManager.init()
        BrowserManager.injectCss(
            "mini-dynmap-sidebar",
            ".dynmap .sublist .item {" +
                    "    display: block;" +
                    "    float: right;" +
                    "" +
                    "scale:95%;" +
                    "" +
                    "    height: 18px;" +
                    "    width: 18px;" +
                    "" +
                    "    padding: 2px;" +
                    "    margin: -20px 3px 1px 1px;" +
                    "" +
                    "    border-radius: 1px;" +
                    "    -moz-border-radius: 1px;" +
                    "" +
                    "    background: rgba(32,32,32,0.6);" +
                    "    border: 1px solid rgba(64,64,64,0.6);" +
                    "}" +
                    "" +
                    "/* Removes the bar across the list */" +
                    ".dynmap .panel .subsection {" +
                    "    display: block;" +
                    "    clear: both;" +
                    "" +
                    "    width: 100%;" +
                    "    line-height: 18px;" +
                    "    margin: 0 0 7px 0;" +
                    "" +
                    "    border-bottom: 1px solid rgba(0,0,0,0);" +
                    "" +
                    "}" +
                    "" +
                    "/* This makes the selected World easy to see */" +
                    ".dynmap .sublist .item.selected {" +
                    "    background: rgba(128,128,128,0.5);" +
                    "    border: 1px solid rgba(255,255,255,255);" +
                    "}" +
                    "" +
                    "/* This disables the Compass */" +
                    ".compass, .compass_NE, .compass_SE, .compass_NW, .compass_SW {" +
                    "    display: block;" +
                    "    position: absolute;" +
                    "    z-index: 10;" +
                    "    top: 0px;" +
                    "    right: 0px;" +
                    "    height: 0px;" +
                    "    width: 0px;" +
                    "    background-repeat: no-repeat;" +
                    "}"
        )

        // Suppress Leaflet hover tooltips on map regions — they obscure the map and
        // the same info is available by clicking the region (which opens the popup).
        BrowserManager.injectCss(
            "hide-leaflet-tooltips",
            ".leaflet-tooltip { display: none !important; }"
        )

        // Sidebar/coordinate display only fit in the full-screen browser view — hidden by
        // default (HUD/projection), shown again while BrowserScreen is open.
        BrowserManager.hideFullScreenChrome()

        // Draw the player's position on the Dynmap/LiveAtlas page itself
        PlayerMapMarker.install()

        // Register the HUD minimap overlay
        MapHudOverlay.register()
        OverlayHud.register()

        // Register the sign-anchor tracker for projection repositioning
        SignAnchorTracker.register()

        logger.info("CrowMap client initialized")
    }
}
