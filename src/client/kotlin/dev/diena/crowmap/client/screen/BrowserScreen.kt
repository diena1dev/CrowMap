package dev.diena.crowmap.client.screen

import dev.diena.crowmap.client.CrowmapClient
import dev.diena.crowmap.client.features.browser.BrowserManager
import io.wispforest.owo.ui.base.BaseOwoScreen
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.*
import net.ccbluex.liquidbounce.mcef.cef.MCEFBrowser
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

/**
 * Full-screen browser screen using owo-ui for layout, with MCEF browser rendering
 * and full mouse/keyboard forwarding.
 */
class BrowserScreen : BaseOwoScreen<FlowLayout>(Component.literal("CrowMap Browser")) {

    private val mc = CrowmapClient.mc
    private val logger = CrowmapClient.logger
    private var browser: MCEFBrowser? = null

    override fun createAdapter(): OwoUIAdapter<FlowLayout> {
        return OwoUIAdapter.create(this, UIContainers::verticalFlow)
    }

    override fun build(rootComponent: FlowLayout) {
        // Use BLANK surface so owo-ui doesn't paint over the browser texture
        rootComponent
            .surface(Surface.BLANK)
            .horizontalAlignment(HorizontalAlignment.CENTER)
            .verticalAlignment(VerticalAlignment.TOP)
    }

    override fun init() {
        super.init()

        // MCEF needs actual framebuffer pixel dimensions, not GUI-scaled dimensions
        val pixelWidth = mc.window.width
        val pixelHeight = mc.window.height

        browser = BrowserManager.getOrCreateBrowser(pixelWidth, pixelHeight)
        browser?.resize(pixelWidth, pixelHeight)

        logger.info("BrowserScreen init: pixelSize=${pixelWidth}x${pixelHeight}, guiSize=${width}x${height}, browser=${browser != null}, mcefInit=${BrowserManager.initialized}")
    }

    override fun resize(width: Int, height: Int) {
        super.resize(width, height)
        // Resize the browser to the actual pixel dimensions
        val pixelWidth = mc.window.width
        val pixelHeight = mc.window.height
        browser?.resize(pixelWidth, pixelHeight)
    }

    override fun render(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        val b = browser
        if (b != null && b.isTextureReady) {
            val textureId = b.textureLocation
            if (textureId != null) {
                val renderer = b.renderer
                val texW = renderer.textureWidth
                val texH = renderer.textureHeight

                if (texW > 0 && texH > 0) {
                    try {
                        // blit(RenderPipeline, Identifier, x, y, u, v, width, height, textureWidth, textureHeight)
                        context.blit(
                            RenderPipelines.GUI_TEXTURED,
                            textureId,
                            0, 0,                       // screen x, y
                            0f, 0f,                     // texture u, v offset
                            width, height,              // destination width, height
                            width, height                  // total texture size
                        )
                    } catch (_: IllegalStateException) {
                        // Texture view may not be fully initialized yet — show loading state
                        renderLoadingScreen(context, "Loading page...")
                    }
                } else {
                    renderLoadingScreen(context, "Loading page...")
                }
            } else {
                renderLoadingScreen(context, "Loading page...")
            }
        } else {
            renderLoadingScreen(context, if (b == null) "Initializing browser..." else "Loading page...")
        }

        // Render owo-ui overlay on top
        super.render(context, mouseX, mouseY, delta)
    }

    private fun renderLoadingScreen(context: GuiGraphics, message: String) {
        context.fill(0, 0, width, height, 0xCC000000.toInt())
        context.drawCenteredString(
            mc.font,
            message,
            width / 2,
            height / 2 - 10,
            0xFFFFFF
        )

        // Show diagnostic info
        val b = browser
        if (b != null) {
            val renderer = b.renderer
            val diagInfo = "tex=${b.isTextureReady} size=${renderer.textureWidth}x${renderer.textureHeight} accel=${renderer.isAccelerated}"
            context.drawCenteredString(
                mc.font,
                diagInfo,
                width / 2,
                height / 2 + 5,
                0x888888
            )
        }
    }

    // --- Mouse input forwarding ---
    // MCEF expects pixel coordinates, so scale from GUI coords to pixel coords

    private fun scaleX(guiX: Double): Int = (guiX * mc.window.width / width).toInt()
    private fun scaleY(guiY: Double): Int = (guiY * mc.window.height / height).toInt()

    override fun mouseClicked(event: MouseButtonEvent, isDoubleClick: Boolean): Boolean {
        browser?.sendMousePress(scaleX(event.x()), scaleY(event.y()), event.button())
        if (super.mouseClicked(event, isDoubleClick)) return true
        return true
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        browser?.sendMouseRelease(scaleX(event.x()), scaleY(event.y()), event.button())
        if (super.mouseReleased(event)) return true
        return true
    }

    override fun mouseMoved(mouseX: Double, mouseY: Double) {
        browser?.sendMouseMove(scaleX(mouseX), scaleY(mouseY))
        super.mouseMoved(mouseX, mouseY)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        if (super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) return true
        browser?.sendMouseWheel(scaleX(mouseX), scaleY(mouseY), verticalAmount)
        return true
    }

    override fun mouseDragged(click: MouseButtonEvent, deltaX: Double, deltaY: Double): Boolean {
        browser?.sendMouseMove(scaleX(click.x()), scaleY(click.y()))
        return true
    }

    // --- Keyboard input forwarding ---

    override fun keyPressed(input: KeyEvent): Boolean {
        if (input.key() == GLFW.GLFW_KEY_ESCAPE) {
            onClose()
            return true
        }
        browser?.sendKeyPress(input.key(), input.scancode().toLong(), input.modifiers())
        return true
    }

    override fun keyReleased(event: KeyEvent): Boolean {
        browser?.sendKeyRelease(event.key(), event.scancode().toLong(), event.modifiers())
        return true
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        browser?.sendKeyTyped(event.codepoint().toChar(), event.modifiers())
        return true
    }

    override fun isPauseScreen(): Boolean = false

    override fun onClose() {
        // Don't close the browser - it's shared across screen, HUD, projection
        super.onClose()
    }
}





