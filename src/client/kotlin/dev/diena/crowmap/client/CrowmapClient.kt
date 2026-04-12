package dev.diena.crowmap.client

import dev.diena.crowmap.client.features.browser.BrowserManager
import dev.diena.crowmap.client.features.hud.MapHudOverlay
import net.fabricmc.api.ClientModInitializer
import dev.diena.crowmap.client.config.Keybindings
import net.minecraft.client.Minecraft
import org.apache.logging.log4j.LogManager

class CrowmapClient : ClientModInitializer {
    companion object {
        val mc: Minecraft = Minecraft.getInstance()
        val logger = LogManager.getLogger("Crowmap")
        val namespace = "crowmap"
    }

    override fun onInitializeClient() {
        // Initialize keybindings
        Keybindings().init()

        // Initialize MCEF browser backend
        BrowserManager.init()

        // Register the HUD minimap overlay
        MapHudOverlay.register()

        logger.info("CrowMap client initialized")
    }
}
