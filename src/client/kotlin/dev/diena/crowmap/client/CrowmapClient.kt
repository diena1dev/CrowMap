package dev.diena.crowmap.client

import dev.diena.crowmap.client.config.CrowmapConfig
import dev.diena.crowmap.client.features.browser.BrowserManager
import dev.diena.crowmap.client.features.hud.MapHudOverlay
import dev.diena.crowmap.client.features.hud.OverlayHud
import dev.diena.crowmap.client.features.liveatlas.LocalLiveAtlasServer
import dev.diena.crowmap.client.features.world.SignAnchorTracker
import net.fabricmc.api.ClientModInitializer
import dev.diena.crowmap.client.config.Keybindings
import net.minecraft.client.Minecraft
import org.apache.logging.log4j.LogManager

class CrowmapClient : ClientModInitializer {
    companion object {
        val mc: Minecraft = Minecraft.getInstance()
        val logger = LogManager.getLogger("Crowmap")
        val namespace = "crowmap"

        fun debug(message: String) {
            if (CrowmapConfig.debugLogging) logger.info("[DEBUG] $message")
        }
    }

    override fun onInitializeClient() {
        // Load config from disk (or create defaults) before anything else reads it
        CrowmapConfig.wrapper

        // Initialize keybindings
        Keybindings().init()

        // Start local LiveAtlas server if enabled; register watcher for runtime toggles
        if (CrowmapConfig.useLocalLiveAtlas) {
            LocalLiveAtlasServer.startAsync { BrowserManager.navigate(LocalLiveAtlasServer.localUrl) }
        }
        LocalLiveAtlasServer.registerConfigWatcher(
            onEnable  = { url -> BrowserManager.navigate(url) },
            onDisable = { BrowserManager.navigate(CrowmapConfig.mapUrl) }
        )

        // Initialize MCEF browser backend
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

        // Register the HUD minimap overlay
        MapHudOverlay.register()
        OverlayHud.register()

        // Register the sign-anchor tracker for projection repositioning
        SignAnchorTracker.register()

        logger.info("CrowMap client initialized")
    }
}
