package dev.diena.crowmap.client.features.hud

import dev.diena.crowmap.client.CrowmapClient
import dev.diena.crowmap.client.config.CrowmapConfig
import dev.diena.crowmap.client.config.CrowmapConfig.HudCorner
import dev.diena.crowmap.client.features.browser.BrowserManager
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.DeltaTracker
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.resources.Identifier

/**
 * HUD overlay that renders a center-cropped square portion of the Graphene browser surface
 * in a configurable corner of the screen.
 */
object MapHudOverlay : HudElement {

    private val mc = CrowmapClient.mc

    /**
     * Registers this HUD element with Fabric's HUD element registry.
     */
    fun register() {
        HudElementRegistry.addLast(
            Identifier.fromNamespaceAndPath(CrowmapClient.namespace, "map_hud"),
            this
        )
    }

    override fun render(context: GuiGraphics, tickCounter: DeltaTracker) {
        if (!CrowmapConfig.hudEnabled) return

        val surface = BrowserManager.surface ?: return
        if (surface.browser().latestFrame().isEmpty) return

        val texWidth = surface.resolutionWidth()
        val texHeight = surface.resolutionHeight()
        if (texWidth <= 0 || texHeight <= 0) return

        val screenWidth = mc.window.guiScaledWidth
        val screenHeight = mc.window.guiScaledHeight

        val size = CrowmapConfig.hudSize
        val margin = CrowmapConfig.hudMargin

        // Calculate the screen position based on the configured corner
        val (screenX, screenY) = when (CrowmapConfig.hudCorner) {
            HudCorner.TOP_LEFT -> Pair(margin, margin)
            HudCorner.TOP_RIGHT -> Pair(screenWidth - size - margin, margin)
            HudCorner.BOTTOM_LEFT -> Pair(margin, screenHeight - size - margin)
            HudCorner.BOTTOM_RIGHT -> Pair(screenWidth - size - margin, screenHeight - size - margin)
        }

        // Calculate center crop: take a square from the center of the browser content
        val cropSize = minOf(texWidth, texHeight)
        val cropX = (texWidth - cropSize) / 2
        val cropY = (texHeight - cropSize) / 2

        // Draw a border/background
        context.fill(
            screenX - 1, screenY - 1,
            screenX + size + 1, screenY + size + 1,
            0xFF222222.toInt()
        )

        // Render the full surface scaled so the desired crop lands exactly in the HUD square,
        // then scissor to that square so only the cropped region is visible.
        val scale = size.toDouble() / cropSize
        val renderedWidth = (texWidth * scale).toInt().coerceAtLeast(1)
        val renderedHeight = (texHeight * scale).toInt().coerceAtLeast(1)
        val originX = (screenX - cropX * scale).toInt()
        val originY = (screenY - cropY * scale).toInt()

        context.enableScissor(screenX, screenY, screenX + size, screenY + size)
        surface.render(context, originX, originY, renderedWidth, renderedHeight)
        context.disableScissor()
    }
}
