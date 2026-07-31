package dev.diena.crowmap.client.screen

import com.mojang.blaze3d.platform.cursor.CursorType
import com.mojang.blaze3d.platform.cursor.CursorTypes
import dev.diena.crowmap.client.CrowmapClient
import dev.diena.crowmap.client.features.browser.BrowserManager
import io.github.trethore.graphene.api.browser.BrowserCursor
import io.github.trethore.graphene.fabric.api.surface.BrowserSurface
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

/**
 * Full-screen browser screen with Graphene browser rendering, full mouse/keyboard forwarding,
 * and a vanilla toolbar (back/forward/reload/URL bar) plus a right-click jump context menu.
 */
class BrowserScreen : Screen(Component.literal("CrowMap Browser")) {

    private val mc = CrowmapClient.mc
    private val logger = CrowmapClient.logger
    private var surface: BrowserSurface? = null

    /** The browser resolution currently in use (capped at 1080p, aspect-correct). */
    private var browserWidth = BrowserManager.MAX_BROWSER_WIDTH
    private var browserHeight = BrowserManager.MAX_BROWSER_HEIGHT

    /** True when a mouse press was swallowed by the toolbar or context menu; paired release is also suppressed. */
    private var suppressBrowserMouseButton = false

    // --- Toolbar ---

    private lateinit var backButton: Button
    private lateinit var forwardButton: Button
    private lateinit var reloadButton: Button
    private lateinit var urlBox: EditBox

    // --- Context menu (right-click "jump" popup) ---

    private val contextMenuWidgets = mutableListOf<AbstractWidget>()
    private var contextMenuBounds: IntArray? = null
    private var contextMenuGeneration = 0
    private val contextMenuOpen: Boolean get() = contextMenuWidgets.isNotEmpty()

    override fun init() {
        super.init()

        val (bw, bh) = BrowserManager.computeBrowserSize()
        browserWidth = bw
        browserHeight = bh

        surface = BrowserManager.getOrCreateBrowser(browserWidth, browserHeight)
        BrowserManager.inputAdapter?.setFocused(true)

        contextMenuWidgets.clear()
        contextMenuBounds = null
        contextMenuGeneration++

        backButton = addRenderableWidget(
            Button.builder(Component.literal("<")) { BrowserManager.goBack() }
                .bounds(0, 0, BUTTON_SIZE, BUTTON_SIZE)
                .build()
        )
        forwardButton = addRenderableWidget(
            Button.builder(Component.literal(">")) { BrowserManager.goForward() }
                .bounds(0, 0, BUTTON_SIZE, BUTTON_SIZE)
                .build()
        )
        reloadButton = addRenderableWidget(
            Button.builder(Component.literal("R")) { BrowserManager.reload() }
                .bounds(0, 0, BUTTON_SIZE, BUTTON_SIZE)
                .build()
        )
        urlBox = addRenderableWidget(EditBox(mc.font, 0, 0, 100, BUTTON_SIZE, Component.literal("Browser URL")))
        urlBox.setMaxLength(1024)
        urlBox.setValue(BrowserManager.currentUrl())

        layoutToolbar()

        CrowmapClient.debug("BrowserScreen init: browserRes=${browserWidth}x${browserHeight}, guiSize=${width}x${height}, window=${mc.window.width}x${mc.window.height}, surface=${surface != null}")
    }

    private fun layoutToolbar() {
        var x = PADDING
        backButton.setX(x); backButton.setY(PADDING)
        x += BUTTON_SIZE + PADDING
        forwardButton.setX(x); forwardButton.setY(PADDING)
        x += BUTTON_SIZE + PADDING
        reloadButton.setX(x); reloadButton.setY(PADDING)
        x += BUTTON_SIZE + PADDING

        urlBox.setX(x); urlBox.setY(PADDING)
        urlBox.setWidth((width - x - PADDING).coerceAtLeast(60))
    }

    private fun updateToolbarState() {
        backButton.active = BrowserManager.canGoBack()
        forwardButton.active = BrowserManager.canGoForward()
        if (!urlBox.isFocused) {
            val current = BrowserManager.currentUrl()
            if (urlBox.value != current) {
                urlBox.setValue(current)
            }
        }
    }

    private fun navigateToUrlBox() {
        var text = urlBox.value.trim()
        if (text.isEmpty()) return
        if (!text.contains("://")) text = "https://$text"
        BrowserManager.navigate(text)
        clearFocus()
    }

    override fun resize(width: Int, height: Int) {
        contextMenuWidgets.clear()
        contextMenuBounds = null
        contextMenuGeneration++

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

        context.fill(0, 0, width, TOOLBAR_HEIGHT, 0xCC101010.toInt())
        updateToolbarState()

        // Render toolbar/context-menu widgets on top of the browser texture
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

    // --- Context menu (reimplementation of owo-ui's DropdownComponent.openContextMenu) ---

    private fun isPointInContextMenu(x: Double, y: Double): Boolean {
        val b = contextMenuBounds ?: return false
        return x >= b[0] && x <= b[0] + b[2] && y >= b[1] && y <= b[1] + b[3]
    }

    private fun clearContextMenuWidgets() {
        contextMenuWidgets.forEach { removeWidget(it) }
        contextMenuWidgets.clear()
        contextMenuBounds = null
    }

    private fun closeContextMenu() {
        contextMenuGeneration++
        clearContextMenuWidgets()
    }

    private fun openContextMenu(guiX: Double, guiY: Double) {
        contextMenuGeneration++
        val myGeneration = contextMenuGeneration
        val browserPixelX = BrowserManager.surface?.toBrowserX(guiX, width) ?: 0
        val browserPixelY = BrowserManager.surface?.toBrowserY(guiY, height) ?: 0

        // Show immediately with no coordinates so there's no delay, then rebuild once resolved.
        buildContextMenu(guiX, guiY, null, null)

        ContextPopup.queryMapCoordinates(browserPixelX, browserPixelY).thenAccept { (x, z) ->
            if (x == null && z == null) return@thenAccept
            mc.execute {
                if (mc.screen !== this || myGeneration != contextMenuGeneration) return@execute
                buildContextMenu(guiX, guiY, x, z)
            }
        }
    }

    private fun buildContextMenu(guiX: Double, guiY: Double, coordX: Int?, coordZ: Int?) {
        clearContextMenuWidgets()

        val jumpLabel = if (coordX != null && coordZ != null) "Jump $coordX $coordZ" else "Jump (no coords)"
        val fleetJumpLabel = if (coordX != null && coordZ != null) "Fleet Jump $coordX $coordZ" else "Fleet Jump (no coords)"

        val entries = listOf<Pair<Component, () -> Unit>>(
            Component.literal("Close") to { closeContextMenu() },
            Component.literal(jumpLabel) to {
                closeContextMenu()
                if (coordX != null && coordZ != null) ContextPopup.executeJump(coordX, coordZ)
            },
            Component.literal(fleetJumpLabel) to {
                closeContextMenu()
                if (coordX != null && coordZ != null) ContextPopup.executeFleetJump(coordX, coordZ)
            }
        )

        val x = guiX.toInt().coerceIn(0, (width - CONTEXT_MENU_WIDTH).coerceAtLeast(0))
        var y = guiY.toInt()
        entries.forEach { (label, action) ->
            val btn = Button.builder(label) { action() }
                .bounds(x, y, CONTEXT_MENU_WIDTH, CONTEXT_MENU_ENTRY_HEIGHT)
                .build()
            contextMenuWidgets += addRenderableWidget(btn)
            y += CONTEXT_MENU_ENTRY_HEIGHT
        }
        contextMenuBounds = intArrayOf(x, guiY.toInt(), CONTEXT_MENU_WIDTH, entries.size * CONTEXT_MENU_ENTRY_HEIGHT)
    }

    // --- Mouse input forwarding ---
    // The input adapter maps GUI-scaled coordinates (relative to the full-screen surface,
    // origin at 0,0) into the browser's fixed pixel resolution internally.

    override fun mouseClicked(event: MouseButtonEvent, isDoubleClick: Boolean): Boolean {
        // Right-click (button 1) over the map area → open the CrowMap context popup
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT && event.y() >= TOOLBAR_HEIGHT) {
            openContextMenu(event.x(), event.y())
            return true
        }

        // If the context menu is open, this click either lands on it (owo-ui behaved the same
        // way: the click is fully consumed by the popup) or dismisses it — never both UI and page.
        if (contextMenuOpen) {
            if (!isPointInContextMenu(event.x(), event.y())) {
                closeContextMenu()
            }
            suppressBrowserMouseButton = true
            super.mouseClicked(event, isDoubleClick)
            return true
        }

        // Toolbar clicks go to the widgets, not the browser underneath.
        if (event.y() < TOOLBAR_HEIGHT) {
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
        if (suppressBrowserMouseButton) {
            return super.mouseDragged(click, deltaX, deltaY)
        }
        BrowserManager.inputAdapter?.mouseDragged(click.x(), click.y(), 0, 0, width, height, click.modifiers())
        return true
    }

    // --- Keyboard input forwarding ---

    override fun keyPressed(input: KeyEvent): Boolean {
        if (urlBox.isFocused && (input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_KP_ENTER)) {
            navigateToUrlBox()
            return true
        }
        if (super.keyPressed(input)) return true
        BrowserManager.inputAdapter?.key(input.key(), input.scancode(), true, input.modifiers())
        return true
    }

    override fun keyReleased(event: KeyEvent): Boolean {
        BrowserManager.inputAdapter?.key(event.key(), event.scancode(), false, event.modifiers())
        return true
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        if (super.charTyped(event)) return true
        BrowserManager.inputAdapter?.text(event.codepointAsString(), event.modifiers())
        return true
    }

    override fun isPauseScreen(): Boolean = false

    override fun onClose() {
        // Don't close the browser - it's shared across screen, HUD, projection
        super.onClose()
    }

    companion object {
        private const val TOOLBAR_HEIGHT = 24
        private const val BUTTON_SIZE = 20
        private const val PADDING = 2
        private const val CONTEXT_MENU_WIDTH = 160
        private const val CONTEXT_MENU_ENTRY_HEIGHT = 20
    }
}
