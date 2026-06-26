package dev.diena.crowmap.client.features.hud

import dev.diena.crowmap.client.CrowmapClient
import dev.diena.crowmap.client.config.CrowmapConfig
import dev.diena.crowmap.client.config.CrowmapConfigModel
import dev.diena.crowmap.client.config.Keybindings
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.DeltaTracker
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import javax.imageio.ImageIO

object OverlayHud : HudElement {

    private val mc = CrowmapClient.mc

    private val TEXTURE = Identifier.fromNamespaceAndPath(CrowmapClient.namespace, "textures/system_map.png")

    private var texW = 0
    private var texH = 0

    var visible = false

    fun register() {
        HudElementRegistry.addLast(
            Identifier.fromNamespaceAndPath(CrowmapClient.namespace, "overlay"),
            this
        )
    }

    private fun ensureTextureDimensions() {
        if (texW > 0) return
        try {
            mc.resourceManager.open(TEXTURE).use { stream ->
                // ImageIO supports PNG, JPEG, GIF, BMP — NativeImage only supports PNG
                val img = ImageIO.read(stream)
                    ?: throw IllegalStateException("ImageIO returned null (unsupported format?)")
                texW = img.width
                texH = img.height
            }
        } catch (e: Exception) {
            CrowmapClient.logger.warn("[OverlayHud] Could not read texture size: ${e.message}")
            texW = 256
            texH = 256
        }
    }

    override fun render(context: GuiGraphics, tickCounter: DeltaTracker) {
        val shouldShow = when (CrowmapConfig.overlayMode) {
            CrowmapConfigModel.OverlayMode.HOLD -> Keybindings.showOverlay?.isDown == true
            CrowmapConfigModel.OverlayMode.TOGGLE -> visible
        }
        if (!shouldShow) return

        ensureTextureDimensions()

        val screenW = mc.window.guiScaledWidth
        val screenH = mc.window.guiScaledHeight

        val fitScale = minOf(screenW.toDouble() / texW, screenH.toDouble() / texH)
        val effectiveScale = fitScale * CrowmapConfig.overlayScale

        val displayW = (texW * effectiveScale).toInt()
        val displayH = (texH * effectiveScale).toInt()
        val x = (screenW - displayW) / 2
        val y = (screenH - displayH) / 2

        // 12-arg blit: separates destination size (displayW/H) from source UV region (texW/H).
        // The 10-arg overload conflates them, sampling only a displayW×displayH subregion
        // from the top-left of the texture instead of the full image.
        context.blit(
            RenderPipelines.GUI_TEXTURED,
            TEXTURE,
            x, y,
            0f, 0f,
            displayW, displayH,
            texW, texH,
            texW, texH
        )
    }
}
