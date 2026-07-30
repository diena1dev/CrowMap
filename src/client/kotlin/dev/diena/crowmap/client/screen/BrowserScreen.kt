package dev.diena.crowmap.client.screen

import com.mojang.blaze3d.platform.cursor.CursorType
import com.mojang.blaze3d.platform.cursor.CursorTypes
import dev.diena.crowmap.client.CrowmapClient
import dev.diena.crowmap.client.features.browser.BrowserManager
import io.github.trethore.graphene.api.browser.BrowserCursor
import io.github.trethore.graphene.fabric.api.surface.BrowserSurface
import io.wispforest.owo.ui.base.BaseOwoScreen
import io.wispforest.owo.ui.component.DropdownComponent
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.*
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

/**
 * Full-screen browser screen using owo-ui for layout, with Graphene browser rendering
 * and full mouse/keyboard forwarding.
 */
class BrowserScreen : BaseOwoScreen<FlowLayout>(Component.literal("CrowMap Browser")) {

    private val mc = CrowmapClient.mc
    private val logger = CrowmapClient.logger
    private var surface: BrowserSurface? = null

    /** The browser resolution currently in use (capped at 1080p, aspect-correct). */
    private var browserWidth = BrowserManager.MAX_BROWSER_WIDTH
    private var browserHeight = BrowserManager.MAX_BROWSER_HEIGHT

    /** True when a mouse press was swallowed due to an open dropdown; paired release is also suppressed. */
    private var suppressBrowserMouseButton = false

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

        surface = BrowserManager.getOrCreateBrowser(browserWidth, browserHeight)
        BrowserManager.inputAdapter?.setFocused(true)

        CrowmapClient.debug("BrowserScreen init: browserRes=${browserWidth}x${browserHeight}, guiSize=${width}x${height}, window=${mc.window.width}x${mc.window.height}, surface=${surface != null}")
    }

    override fun resize(width: Int, height: Int) {
        super.resize(width, height)

        val (bw, bh) = BrowserManager.computeBrowserSize()
        browserWidth = bw
        browserHeight = bh
        BrowserManager.resize(browserWidth, browserHeight)
    }

    override fun render(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        val s = surface
        val hasFrame = s?.browser()?.latestFrame()?.isPresent == true
        if (s != null && hasFrame) {
            s.render(context, 0, 0, width, height)
            context.requestCursor(cursor(s))
        } else {
            renderLoadingScreen(context, if (s == null) "Initializing browser..." else "Loading page...")
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
    }

    private fun cursor(s: BrowserSurface): CursorType = when (s.browser().requestedCursor()) {
        BrowserCursor.CROSSHAIR -> CursorTypes.CROSSHAIR
        BrowserCursor.TEXT -> CursorTypes.IBEAM
        BrowserCursor.HAND -> CursorTypes.POINTING_HAND
        BrowserCursor.NOT_ALLOWED -> CursorTypes.NOT_ALLOWED
        BrowserCursor.RESIZE_HORIZONTAL -> CursorTypes.RESIZE_EW
        BrowserCursor.RESIZE_VERTICAL -> CursorTypes.RESIZE_NS
        BrowserCursor.RESIZE_ALL -> CursorTypes.RESIZE_ALL
        BrowserCursor.ARROW -> CursorTypes.ARROW
    }

    // --- Mouse input forwarding ---
    // The input adapter maps GUI-scaled coordinates (relative to the full-screen surface,
    // origin at 0,0) into the browser's fixed pixel resolution internally.

    private fun hasOpenDropdown(): Boolean =
        uiAdapter.rootComponent.children().any { it is DropdownComponent }

    override fun mouseClicked(event: MouseButtonEvent, isDoubleClick: Boolean): Boolean {
        // Right-click (button 1) → open the CrowMap context popup
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            ContextPopup.open(
                screen = this,
                root = uiAdapter.rootComponent,
                browserPixelX = BrowserManager.surface?.toBrowserX(event.x(), width) ?: 0,
                browserPixelY = BrowserManager.surface?.toBrowserY(event.y(), height) ?: 0,
                guiX = event.x(),
                guiY = event.y()
            )
            return true
        }
        // If a dropdown is open, let owo-ui consume the click (dismiss/interact with it)
        // and suppress forwarding to the browser — the user is interacting with UI, not the map.
        if (hasOpenDropdown()) {
            suppressBrowserMouseButton = true
            super.mouseClicked(event, isDoubleClick)
            return true
        }
        suppressBrowserMouseButton = false
        BrowserManager.inputAdapter?.mouseButton(
            event.x(), event.y(), 0, 0, width, height,
            event.button(), true, if (isDoubleClick) 2 else 1, event.modifiers()
        )
        super.mouseClicked(event, isDoubleClick)
        return true
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (suppressBrowserMouseButton) {
            suppressBrowserMouseButton = false
            super.mouseReleased(event)
            return true
        }
        BrowserManager.inputAdapter?.mouseButton(
            event.x(), event.y(), 0, 0, width, height,
            event.button(), false, 0, event.modifiers()
        )
        super.mouseReleased(event)
        return true
    }

    override fun mouseMoved(mouseX: Double, mouseY: Double) {
        BrowserManager.inputAdapter?.mouseMoved(mouseX, mouseY, 0, 0, width, height, 0)
        super.mouseMoved(mouseX, mouseY)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        if (super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) return true
        BrowserManager.inputAdapter?.mouseScrolled(
            mouseX, mouseY, 0, 0, width, height, horizontalAmount, verticalAmount, 0
        )
        return true
    }

    override fun mouseDragged(click: MouseButtonEvent, deltaX: Double, deltaY: Double): Boolean {
        BrowserManager.inputAdapter?.mouseDragged(click.x(), click.y(), 0, 0, width, height, click.modifiers())
        return true
    }

    // --- Keyboard input forwarding ---

    override fun keyPressed(input: KeyEvent): Boolean {
        if (input.key() == GLFW.GLFW_KEY_ESCAPE) {
            onClose()
            return true
        }
        BrowserManager.inputAdapter?.key(input.key(), input.scancode(), true, input.modifiers())
        return true
    }

    override fun keyReleased(event: KeyEvent): Boolean {
        BrowserManager.inputAdapter?.key(event.key(), event.scancode(), false, event.modifiers())
        return true
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        BrowserManager.inputAdapter?.text(event.codepointAsString(), event.modifiers())
        return true
    }

    override fun isPauseScreen(): Boolean = false

    override fun onClose() {
        // Don't close the browser - it's shared across screen, HUD, projection
        super.onClose()
    }
}
