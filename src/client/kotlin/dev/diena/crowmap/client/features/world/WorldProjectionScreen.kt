package dev.diena.crowmap.client.features.world

import dev.diena.crowmap.client.CrowmapClient
import dev.diena.crowmap.client.config.CrowmapConfig
import dev.diena.crowmap.client.features.browser.BrowserManager
import io.wispforest.owo.braid.core.BraidGraphics
import io.wispforest.owo.braid.core.Constraints
import io.wispforest.owo.braid.core.KeyModifiers
import io.wispforest.owo.braid.display.BraidDisplay
import io.wispforest.owo.braid.display.BraidDisplayBinding
import io.wispforest.owo.braid.display.DisplayQuad
import io.wispforest.owo.braid.framework.instance.LeafWidgetInstance
import io.wispforest.owo.braid.framework.instance.MouseListener
import io.wispforest.owo.braid.framework.widget.LeafInstanceWidget
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin

/**
 * Manages an in-world projection "screen" that displays the browser texture
 * using owo-lib's braid-ui BraidDisplay system.
 *
 * BraidDisplay handles:
 * - Rendering a braid widget tree to a texture
 * - Rendering that texture as a quad in the world
 * - Player interaction via raycasting (clicks and text input)
 */
object WorldProjectionScreen {

    private val logger = CrowmapClient.logger
    private val mc = CrowmapClient.mc

    /** The active BraidDisplay, if any. */
    var display: BraidDisplay? = null
        private set

    /** Whether the projection is currently active. */
    val isActive: Boolean get() = display != null

    /**
     * Creates and activates the in-world projection display.
     * Uses braid-ui's BraidDisplay which provides automatic:
     * - Texture rendering from the widget tree
     * - In-world quad rendering
     * - Player interaction (mouse/keyboard forwarding via raycasting)
     */
    fun activate() {
        if (display != null) {
            deactivate()
        }

        if (!CrowmapConfig.projectionEnabled) return

        val quad = buildQuadFromConfig()

        // Create the braid widget that will render the browser content
        val widget = BrowserProjectionWidget()

        display = BraidDisplay(
            quad,
            mc.window.width,//CrowmapConfig.projectionResWidth,
            mc.window.height,//CrowmapConfig.projectionResHeight,
            widget
        ).renderAutomatically()

        // Register with BraidDisplayBinding so it receives interaction events
        // and renders automatically
        BraidDisplayBinding.activate(display!!)

        logger.info("World projection activated at ${CrowmapConfig.projectionPos}")
    }

    /**
     * Deactivates and cleans up the in-world projection.
     */
    fun deactivate() {
        display?.let {
            BraidDisplayBinding.deactivate(it)
        }
        display = null
    }

    /**
     * Updates the quad position/rotation based on current config.
     */
    fun updateQuad() {
        display?.quad = buildQuadFromConfig()
    }

    /**
     * Sets the projection position to the player's current look target.
     */
    fun setPositionFromPlayer() {
        val player = mc.player ?: return
        val lookPos = player.position().add(0.0, player.eyeHeight.toDouble(), 0.0)
            .add(player.lookAngle.scale(3.0))

        CrowmapConfig.projectionPos = lookPos
        CrowmapConfig.projectionYaw = player.yRot

        if (isActive) {
            updateQuad()
        }
    }

    /**
     * Toggles the projection on/off.
     */
    fun toggle() {
        CrowmapConfig.projectionEnabled = !CrowmapConfig.projectionEnabled
        if (CrowmapConfig.projectionEnabled) {
            activate()
        } else {
            deactivate()
        }
    }

    /**
     * Builds a DisplayQuad from the current config values.
     *
     * The DisplayQuad defines a quad in 3D space with:
     * - pos: the origin point (bottom-left)
     * - top: vector from origin to the top edge
     * - left: vector from origin to the right edge
     *
     * BraidDisplay's hit testing and rendering uses these vectors.
     */
    private fun buildQuadFromConfig(): DisplayQuad {
        val pos = CrowmapConfig.projectionPos
        val yawRad = Math.toRadians(CrowmapConfig.projectionYaw.toDouble())
        val w = CrowmapConfig.projectionWidth.toDouble()
        val h = CrowmapConfig.projectionHeight.toDouble()

        // The "left" vector points horizontally perpendicular to the facing direction
        // (this defines the width of the quad along the surface plane)
        val leftDir = Vec3(-cos(yawRad) * w, 0.0, -sin(yawRad) * w)

        // The "top" vector points upward (defines the height of the quad)
        val topDir = Vec3(0.0, h, 0.0)

        // Rotate the UV mapping 90° CW to fix the texture being displayed 90° CCW.
        // Shift origin from bottom-left to top-left, swap and negate the direction
        // vectors so the physical quad corners stay the same but the texture axes rotate.
        val adjustedPos = pos.add(topDir)
        val adjustedTop = leftDir
        val adjustedLeft = topDir.scale(-1.0)

        return DisplayQuad(adjustedPos, adjustedTop, adjustedLeft)
    }
}

/**
 * A braid-ui widget that renders the MCEF browser texture into the braid display surface
 * and forwards input events to the browser.
 *
 * Extends LeafInstanceWidget so we can create a custom instance that implements MouseListener
 * to receive mouse events from the braid display interaction system (raycasting + click/scroll).
 */
class BrowserProjectionWidget : LeafInstanceWidget() {
    override fun instantiate(): LeafWidgetInstance<*> {
        return BrowserProjectionInstance(this)
    }
}

/**
 * Widget instance that handles both rendering and input for the browser projection.
 *
 * Implements MouseListener to receive mouse events dispatched by the braid display system's
 * built-in interaction handling (owo-lib mixins handle raycasting, click interception, and scroll):
 * - MC right-click (use) → braid button 0 (primary) → browser left-click
 * - MC left-click (attack) → braid button 1 (secondary) → browser right-click
 * - Scroll wheel → forwarded directly
 * - Cursor position → updated each frame via raycasting
 */
class BrowserProjectionInstance(widget: BrowserProjectionWidget) :
    LeafWidgetInstance<BrowserProjectionWidget>(widget), MouseListener {

    override fun doLayout(constraints: Constraints) {
        // Fill all available space (the entire braid surface)
        transform.setSize(constraints.maxFiniteOrMinSize())
    }

    override fun draw(graphics: BraidGraphics) {
        val browser = BrowserManager.browser ?: return
        if (!browser.isTextureReady) return

        val textureId = browser.textureLocation ?: return
        val renderer = browser.renderer
        val texW = renderer.textureWidth
        val texH = renderer.textureHeight
        if (texW <= 0 || texH <= 0) return

        val width = transform.width().toInt()
        val height = transform.height().toInt()

        try {
            graphics.blit(
                net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                textureId,
                0, 0,           // destination x, y
                0f, 0f,         // source u, v offset (pixel space)
                width, height,  // destination width, height
                texW, texH      // total texture dimensions
            )
        } catch (_: IllegalStateException) {
            // Texture view may not be fully initialized yet
        }
    }

    // --- Mouse event forwarding to MCEF browser ---

    // Braid display button mapping (set by owo-lib's MinecraftMixin):
    //   MC right-click (use key)  → braid button 0 (primary)
    //   MC left-click (attack key) → braid button 1 (secondary)
    // Browser button convention:
    //   0 = left click, 1 = middle click, 2 = right click
    // We map: braid 0 → browser 0 (left), braid 1 → browser 2 (right)
    private fun mapButton(braidButton: Int): Int = when (braidButton) {
        0 -> 0  // MC use (right-click) → browser left-click
        1 -> 2  // MC attack (left-click) → browser right-click
        else -> braidButton
    }

    override fun onMouseDown(x: Double, y: Double, button: Int, modifiers: KeyModifiers): Boolean {
        val browser = BrowserManager.browser ?: return false
        browser.sendMousePress(x.toInt(), y.toInt(), mapButton(button))
        return true
    }

    override fun onMouseUp(x: Double, y: Double, button: Int, modifiers: KeyModifiers): Boolean {
        val browser = BrowserManager.browser ?: return false
        browser.sendMouseRelease(x.toInt(), y.toInt(), mapButton(button))
        return true
    }

    override fun onMouseMove(x: Double, y: Double) {
        val browser = BrowserManager.browser ?: return
        browser.sendMouseMove(x.toInt(), y.toInt())
    }

    override fun onMouseScroll(mouseX: Double, mouseY: Double, xOffset: Double, yOffset: Double): Boolean {
        val browser = BrowserManager.browser ?: return false
        browser.sendMouseWheel(mouseX.toInt(), mouseY.toInt(), yOffset)
        return true
    }

    override fun onMouseDrag(x: Double, y: Double, deltaX: Double, deltaY: Double) {
        val browser = BrowserManager.browser ?: return
        browser.sendMouseMove(x.toInt(), y.toInt())
    }
}


