package dev.diena.crowmap.client.screen

import com.mojang.blaze3d.platform.cursor.CursorType
import com.mojang.blaze3d.platform.cursor.CursorTypes
import dev.diena.crowmap.client.CrowmapClient
import dev.diena.crowmap.client.config.CrowmapConfig
import dev.diena.crowmap.client.features.browser.BrowserManager
import io.github.trethore.graphene.api.browser.BrowserCursor
import io.github.trethore.graphene.fabric.api.surface.BrowserSurface
import io.wispforest.owo.ui.base.BaseOwoScreen
import io.wispforest.owo.ui.component.ButtonComponent
import io.wispforest.owo.ui.component.DropdownComponent
import io.wispforest.owo.ui.component.TextBoxComponent
import io.wispforest.owo.ui.component.UIComponents
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.*
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

/**
 * Full-screen browser screen using owo-ui for layout, with Graphene browser rendering
 * and full mouse/keyboard forwarding. Has a toolbar (back/forward/reload/URL bar) and a
 * right-click jump context menu (owo-ui's [DropdownComponent]).
 */
class BrowserScreen : BaseOwoScreen<FlowLayout>(Component.literal("CrowMap Browser")) {

    private val mc = CrowmapClient.mc
    private val logger = CrowmapClient.logger
    private var surface: BrowserSurface? = null

    /** The browser resolution currently in use (capped at 1080p, aspect-correct). */
    private var browserWidth = BrowserManager.MAX_BROWSER_WIDTH
    private var browserHeight = BrowserManager.MAX_BROWSER_HEIGHT

    /** True when a mouse press was swallowed by the toolbar or an open dropdown; paired release is also suppressed. */
    private var suppressBrowserMouseButton = false

    private lateinit var backButton: ButtonComponent
    private lateinit var forwardButton: ButtonComponent
    private lateinit var urlBox: TextBoxComponent

    override fun createAdapter(): OwoUIAdapter<FlowLayout> {
        return OwoUIAdapter.create(this, UIContainers::verticalFlow)
    }

    override fun build(rootComponent: FlowLayout) {
        // Use BLANK surface so owo-ui doesn't paint over the browser texture
        rootComponent
            .surface(Surface.BLANK)
            .horizontalAlignment(HorizontalAlignment.CENTER)
            .verticalAlignment(VerticalAlignment.TOP)

        // Same panel surface DropdownComponent uses for the jump-menu popup, so the toolbar
        // reads as the same UI system rather than a bolted-on vanilla control strip.
        // With the URL bar disabled, the toolbar is just 3 small buttons — shrink it to fit them
        // instead of reserving a full-width strip, and let the map extend underneath (see
        // viewportTop()/isPointInToolbar()).
        val toolbarWidthSizing = if (CrowmapConfig.showUrlBar) Sizing.fill(100) else Sizing.content()
        val toolbar = UIContainers.horizontalFlow(toolbarWidthSizing, Sizing.fixed(TOOLBAR_HEIGHT))
        toolbar.surface(Surface.flat(0xC7000000.toInt()).and(Surface.blur(3f, 5f)).and(Surface.outline(0xFF121212.toInt())))
        toolbar.verticalAlignment(VerticalAlignment.CENTER)
        toolbar.padding(Insets.of(2))
        toolbar.gap(2)
        toolbar.positioning(Positioning.absolute(0, 0))

        backButton = UIComponents.button(Component.literal("<")) { BrowserManager.goBack() }
        backButton.sizing(Sizing.fixed(20), Sizing.fixed(20))
        backButton.renderer(TOOLBAR_BUTTON_RENDERER)
        forwardButton = UIComponents.button(Component.literal(">")) { BrowserManager.goForward() }
        forwardButton.sizing(Sizing.fixed(20), Sizing.fixed(20))
        forwardButton.renderer(TOOLBAR_BUTTON_RENDERER)
        val reloadButton = UIComponents.button(Component.literal("R")) {
            if (isShiftDown()) {
                // Shift+reload: the page itself isn't necessarily the problem — restart the
                // whole CEF session, for when the browser itself gets stuck rather than the page.
                BrowserManager.restart()
                surface = BrowserManager.surface
                BrowserManager.inputAdapter?.setFocused(true)
            } else {
                BrowserManager.reload()
            }
        }
        reloadButton.sizing(Sizing.fixed(20), Sizing.fixed(20))
        reloadButton.renderer(TOOLBAR_BUTTON_RENDERER)
        reloadButton.setTooltip(Tooltip.create(Component.literal("Reload (Shift: restart browser)")))
        urlBox = UIComponents.textBox(Sizing.fill(100))
        urlBox.text(BrowserManager.currentUrl())
        urlBox.setBordered(false)

        toolbar.child(backButton)
        toolbar.child(forwardButton)
        toolbar.child(reloadButton)
        if (CrowmapConfig.showUrlBar) {
            toolbar.child(urlBox)
        }
        rootComponent.child(toolbar)

        // Anchored separately, bottom-left of the screen — not part of the toolbar. Same panel
        // surface as the toolbar/jump-menu so it still reads as part of the same UI system.
        val myWorldContainer = UIContainers.horizontalFlow(Sizing.content(), Sizing.content())
        myWorldContainer.surface(Surface.flat(0xC7000000.toInt()).and(Surface.blur(3f, 5f)).and(Surface.outline(0xFF121212.toInt())))
        myWorldContainer.padding(Insets.of(2))
        myWorldContainer.positioning(Positioning.absolute(MY_WORLD_MARGIN, height - MY_WORLD_MARGIN - TOOLBAR_HEIGHT))

        val myWorldButton = UIComponents.button(Component.literal("Me")) { jumpToPlayerWorld() }
        myWorldButton.sizing(Sizing.fixed(24), Sizing.fixed(20))
        myWorldButton.renderer(TOOLBAR_BUTTON_RENDERER)

        myWorldContainer.child(myWorldButton)
        rootComponent.child(myWorldContainer)
    }

    override fun init() {
        super.init()

        val (bw, bh) = BrowserManager.computeBrowserSize()
        browserWidth = bw
        browserHeight = bh

        BrowserManager.requestHighResolution()
        BrowserManager.showFullScreenChrome()
        surface = BrowserManager.getOrCreateBrowser(browserWidth, browserHeight)
        BrowserManager.inputAdapter?.setFocused(true)

        CrowmapClient.debug("BrowserScreen init: browserRes=${browserWidth}x${browserHeight}, guiSize=${width}x${height}, window=${mc.window.width}x${mc.window.height}, surface=${surface != null}")
    }

    private fun isShiftDown(): Boolean {
        val handle = mc.window.handle()
        return GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS ||
            GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS
    }

    /** Maps the player's current dimension to the keyword [BrowserManager.jumpToWorld] matches on. */
    private fun jumpToPlayerWorld() {
        val player = mc.player ?: return
        val dimensionPath = player.level().dimension().identifier().path
        val keyword = when (dimensionPath) {
            "overworld" -> "overworld"
            "the_nether" -> "nether"
            "the_end" -> "end"
            else -> dimensionPath
        }
        BrowserManager.jumpToWorld(keyword)
    }

    private fun navigateToUrlBox() {
        var text = urlBox.value.trim()
        if (text.isEmpty()) return
        if (!text.contains("://")) text = "https://$text"
        BrowserManager.navigate(text)
        urlBox.setFocused(false)
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
            s.render(context, 0, viewportTop(), width, viewportHeight())
            context.requestCursor(cursor(s))
        } else {
            renderLoadingScreen(context, if (s == null) "Initializing browser..." else "Loading page...")
        }

        if (!urlBox.isFocused) {
            val current = BrowserManager.currentUrl()
            if (urlBox.value != current) {
                urlBox.text(current)
            }
        }
        backButton.active(BrowserManager.canGoBack())
        forwardButton.active(BrowserManager.canGoForward())

        // Render owo-ui overlay on top
        super.render(context, mouseX, mouseY, delta)
    }

    private fun renderLoadingScreen(context: GuiGraphics, message: String) {
        context.fill(0, viewportTop(), width, height, 0xCC000000.toInt())
        context.drawCenteredString(
            mc.font,
            message,
            width / 2,
            viewportTop() + viewportHeight() / 2 - 10,
            0xFFFFFF
        )
    }

    /**
     * Where the browser viewport starts — below the toolbar when the URL bar is shown (it spans
     * the full width, so the map has to make room for it), or 0 when it's hidden (the toolbar is
     * just 3 small buttons then, so the map extends underneath them instead — see [isPointInToolbar]).
     */
    private fun viewportTop(): Int = if (CrowmapConfig.showUrlBar) TOOLBAR_HEIGHT else 0

    /** The browser viewport's on-screen height. */
    private fun viewportHeight(): Int = (height - viewportTop()).coerceAtLeast(1)

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
    // The input adapter maps GUI-scaled coordinates into the browser's fixed pixel resolution
    // internally; the viewport starts at (0, viewportTop()) — below the toolbar when the URL bar
    // is shown, or the very top of the screen (extending under the compact toolbar) when it's not.

    private fun hasOpenDropdown(): Boolean =
        uiAdapter.rootComponent.children().any { it is DropdownComponent }

    /**
     * The toolbar's actual on-screen bounds — full width when the URL bar is shown, or just
     * enough for the 3 buttons ([TOOLBAR_COMPACT_WIDTH]) when it's hidden and the map extends
     * underneath. Kept in sync with the sizing/padding/gap set up in [build].
     */
    private fun isPointInToolbar(x: Double, y: Double): Boolean {
        val toolbarWidth = if (CrowmapConfig.showUrlBar) width else TOOLBAR_COMPACT_WIDTH
        return x >= 0 && x <= toolbarWidth && y >= 0 && y <= TOOLBAR_HEIGHT
    }

    /** Bottom-left "Me" button's screen bounds — kept in sync with its [build] positioning/sizing. */
    private fun isPointInMyWorldButton(x: Double, y: Double): Boolean {
        val x0 = MY_WORLD_MARGIN.toDouble()
        val y0 = (height - MY_WORLD_MARGIN - TOOLBAR_HEIGHT).toDouble()
        return x >= x0 && x <= x0 + MY_WORLD_BUTTON_WIDTH && y >= y0 && y <= y0 + TOOLBAR_HEIGHT
    }

    override fun mouseClicked(event: MouseButtonEvent, isDoubleClick: Boolean): Boolean {
        // Right-click (button 1) over the map area → open the CrowMap context popup
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT &&
            !isPointInToolbar(event.x(), event.y()) &&
            !isPointInMyWorldButton(event.x(), event.y())
        ) {
            ContextPopup.open(
                screen = this,
                root = uiAdapter.rootComponent,
                browserPixelX = BrowserManager.surface?.toBrowserX(event.x(), width) ?: 0,
                browserPixelY = BrowserManager.surface?.toBrowserY(event.y() - viewportTop(), viewportHeight()) ?: 0,
                guiX = event.x(),
                guiY = event.y()
            )
            return true
        }
        // If a dropdown is open, or the click is on the toolbar or the "Me" button, let owo-ui
        // consume the click and suppress forwarding to the browser — the user is interacting
        // with UI, not the map.
        if (hasOpenDropdown() || isPointInToolbar(event.x(), event.y()) || isPointInMyWorldButton(event.x(), event.y())) {
            suppressBrowserMouseButton = true
            super.mouseClicked(event, isDoubleClick)
            return true
        }
        suppressBrowserMouseButton = false
        BrowserManager.inputAdapter?.mouseButton(
            event.x(), event.y(), 0, viewportTop(), width, viewportHeight(),
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
            event.x(), event.y(), 0, viewportTop(), width, viewportHeight(),
            event.button(), false, 0, event.modifiers()
        )
        super.mouseReleased(event)
        return true
    }

    override fun mouseMoved(mouseX: Double, mouseY: Double) {
        BrowserManager.inputAdapter?.mouseMoved(mouseX, mouseY, 0, viewportTop(), width, viewportHeight(), 0)
        super.mouseMoved(mouseX, mouseY)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        if (super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) return true
        BrowserManager.inputAdapter?.mouseScrolled(
            mouseX, mouseY, 0, viewportTop(), width, viewportHeight(), horizontalAmount, verticalAmount, 0
        )
        return true
    }

    override fun mouseDragged(click: MouseButtonEvent, deltaX: Double, deltaY: Double): Boolean {
        if (suppressBrowserMouseButton) {
            return super.mouseDragged(click, deltaX, deltaY)
        }
        BrowserManager.inputAdapter?.mouseDragged(click.x(), click.y(), 0, viewportTop(), width, viewportHeight(), click.modifiers())
        return true
    }

    // --- Keyboard input forwarding ---

    override fun keyPressed(input: KeyEvent): Boolean {
        if (input.key() == GLFW.GLFW_KEY_ESCAPE) {
            onClose()
            return true
        }
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
        BrowserManager.releaseHighResolution()
        BrowserManager.hideFullScreenChrome()
        super.onClose()
    }

    companion object {
        private const val TOOLBAR_HEIGHT = 24
        private const val MY_WORLD_MARGIN = 0

        // 3 buttons (20 each) + container padding (2 each side) + 2 gaps (2 each) between them —
        // must match the toolbar's sizing/padding/gap set up in build() when showUrlBar is off.
        private const val TOOLBAR_COMPACT_WIDTH = 3 * 20 + 2 * 2 + 2 * 2

        // Button (24) + container padding (2 on each side) — must match the myWorldContainer/
        // myWorldButton sizing/padding set up in build().
        private const val MY_WORLD_BUTTON_WIDTH = 24

        // Matches DropdownComponent.Button's own hover fill (0x44FFFFFF), transparent otherwise —
        // same look as the jump-menu popup entries instead of vanilla's 3D-bevel button sprite.
        private val TOOLBAR_BUTTON_RENDERER: ButtonComponent.Renderer =
            ButtonComponent.Renderer.flat(0x00000000, 0x44FFFFFF.toInt(), 0x00000000)
    }
}
