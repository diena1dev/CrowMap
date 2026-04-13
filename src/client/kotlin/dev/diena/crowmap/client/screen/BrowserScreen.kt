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

    /** The browser resolution currently in use (capped at 1080p, aspect-correct). */
    private var browserWidth = BrowserManager.MAX_BROWSER_WIDTH
    private var browserHeight = BrowserManager.MAX_BROWSER_HEIGHT

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

        val (bw, bh) = BrowserManager.computeBrowserSize()
        browserWidth = bw
        browserHeight = bh

        browser = BrowserManager.getOrCreateBrowser(browserWidth, browserHeight)
        browser?.resize(browserWidth, browserHeight)

        logger.info("BrowserScreen init: browserRes=${browserWidth}x${browserHeight}, guiSize=${width}x${height}, window=${mc.window.width}x${mc.window.height}, browser=${browser != null}")
    }

    override fun resize(width: Int, height: Int) {
        super.resize(width, height)

        val (bw, bh) = BrowserManager.computeBrowserSize()
        browserWidth = bw
        browserHeight = bh
        browser?.resize(browserWidth, browserHeight)
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
                        // blit(pipeline, atlas, x, y, u, v, width, height, uWidth, vHeight, texWidth, texHeight)
                        //   x,y            = dest position on screen (GUI-scaled)
                        //   u,v            = source offset in texture pixels
                        //   width, height  = dest size (GUI-scaled screen)
                        //   uWidth,vHeight = source region (full browser texture)
                        //   texWidth,texHeight = total texture dimensions
                        context.blit(
                            RenderPipelines.GUI_TEXTURED,
                            textureId,
                            0, 0,                       // screen x, y
                            0f, 0f,                     // texture u, v offset
                            width, height,              // destination width, height
                            texW, texH,                 // source region (full texture)
                            texW, texH                  // total texture dimensions
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
    // MCEF expects coordinates in the browser's resolution space, so scale from
    // GUI-scaled coords to the capped browser resolution.

    private fun scaleX(guiX: Double): Int = (guiX * browserWidth / width).toInt()
    private fun scaleY(guiY: Double): Int = (guiY * browserHeight / height).toInt()

    override fun mouseClicked(event: MouseButtonEvent, isDoubleClick: Boolean): Boolean {
        // Right-click (button 1) → open the CrowMap context popup
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            ContextPopup.open(
                screen = this,
                root = uiAdapter.rootComponent,
                browserPixelX = scaleX(event.x()),
                browserPixelY = scaleY(event.y()),
                guiX = event.x(),
                guiY = event.y()
            )
            return true
        }
        // Let owo-ui handle the click first (e.g. an open dropdown button).
        // Only forward to the browser if nothing in the UI tree consumed it.
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





