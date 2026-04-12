package dev.diena.crowmap.client.features.hud

import dev.diena.crowmap.client.CrowmapClient
import dev.diena.crowmap.client.config.CrowmapConfig
import dev.diena.crowmap.client.config.CrowmapConfig.HudCorner
import dev.diena.crowmap.client.features.browser.BrowserManager
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.DeltaTracker
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier

/**
 * HUD overlay that renders a center-cropped square portion of the MCEF browser texture
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

        val browser = BrowserManager.browser ?: return
        if (!browser.isTextureReady) return

        val textureId = browser.textureLocation ?: return

        val renderer = browser.renderer
        val texWidth = renderer.textureWidth
        val texHeight = renderer.textureHeight
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

        // Calculate center crop: take a square from the center of the browser texture
        val cropSize = minOf(texWidth, texHeight)
        val cropX = (texWidth - cropSize) / 2
        val cropY = (texHeight - cropSize) / 2

        // Draw a border/background
        context.fill(
            screenX - 1, screenY - 1,
            screenX + size + 1, screenY + size + 1,
            0xFF222222.toInt()
        )

        // Render the center-cropped portion of the browser texture
        try {
            // blit(RenderPipeline, Identifier, x, y, u, v, width, height, texWidth, texHeight)
            // u,v are the source offset in pixels; width,height is both dest size and source crop size
            // texWidth,texHeight are total texture dimensions for UV normalization
            context.blit(
                RenderPipelines.GUI_TEXTURED,
                textureId,
                screenX, screenY,                      // destination x, y
                cropX.toFloat(), cropY.toFloat(),      // source u, v offset
                size, size,                            // destination width, height (also source crop)
                texWidth, texHeight                    // total texture dimensions
            )
        } catch (_: IllegalStateException) {
            // Texture view may not be fully initialized yet — ignore this frame
        }
    }
}

