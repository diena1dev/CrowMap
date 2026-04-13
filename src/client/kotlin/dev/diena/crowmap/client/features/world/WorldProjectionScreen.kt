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
import dev.diena.crowmap.client.screen.ContextPopup
import net.minecraft.network.chat.Component
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.OptionalDouble
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

        // Use the capped browser resolution so the braid surface matches the
        // actual browser texture (keeps 1:1 pixel mapping, avoids UV overflow).
        val (bw, bh) = BrowserManager.computeBrowserSize()

        display = BraidDisplay(
            quad,
            bw,
            bh,
            widget
        ).renderAutomatically()

        // Register with BraidDisplayBinding so it receives interaction events
        // and renders automatically
        BraidDisplayBinding.activate(display!!)

        logger.info("World projection activated at ${CrowmapConfig.projectionPos} (display ${bw}x${bh})")
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

    /** Timestamp (System.nanoTime) of the last resize — used to debounce. */
    private var lastResizeNano = 0L
    private const val RESIZE_DEBOUNCE_NS = 500_000_000L // 500 ms

    /**
     * Called by [BrowserManager] when the Minecraft window is resized.
     * Recreates the display so the braid surface matches the new browser
     * resolution and the quad scales with [CrowmapConfig.projectionWidth]/[CrowmapConfig.projectionHeight].
     */
    fun onWindowResize() {
        if (!isActive) return
        val now = System.nanoTime()
        if (now - lastResizeNano < RESIZE_DEBOUNCE_NS) return
        lastResizeNano = now
        // Recreate: deactivate then activate picks up the new resolution + quad size
        deactivate()
        CrowmapConfig.projectionEnabled = true
        activate()
    }

    /**
     * Updates the quad position/rotation based on current config.
     */
    fun updateQuad() {
        display?.quad = buildQuadFromConfig()
    }

    /** Max distance (blocks) the placement raycast will travel. */
    internal const val PLACE_REACH = 6.0

    /**
     * Normalizes a yaw angle to the range [-180, 180).
     */
    internal fun normalizeYaw(yaw: Float): Float {
        var y = yaw % 360f
        if (y >= 180f) y -= 360f
        if (y < -180f) y += 360f
        return y
    }

    /**
     * Sets the projection position to the block the player is looking at.
     * Performs a block raycast so the projection collides with solid geometry
     * instead of floating 3 blocks forward through the air.
     */
    fun setPositionFromPlayer() {
        val player = mc.player ?: return
        val level = mc.level ?: return

        val eyePos = player.getEyePosition(1f)
        val lookVec = player.lookAngle
        val endPos = eyePos.add(lookVec.scale(PLACE_REACH))

        val hit = level.clip(
            ClipContext(
                eyePos,
                endPos,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                player
            )
        )

        val placePos = if (hit.type == HitResult.Type.BLOCK) {
            hit.location
        } else {
            // Nothing hit — fall back to max reach
            endPos
        }

        CrowmapConfig.projectionPos = placePos
        CrowmapConfig.projectionYaw = normalizeYaw(player.yRot)

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
        val w = CrowmapConfig.projectionWidth
        val h = CrowmapConfig.projectionHeight

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
 * - MC right-click (use) → braid button 0 → browser left-click (primary browsing)
 * - MC left-click (attack) → braid button 1 → open context popup
 * - Scroll wheel → forwarded directly
 * - Cursor position → updated each frame via raycasting
 *
 * Also renders a context popup directly on the braid surface (no separate screen).
 */
class BrowserProjectionInstance(widget: BrowserProjectionWidget) :
    LeafWidgetInstance<BrowserProjectionWidget>(widget), MouseListener {

    // ── Inline popup state ───────────────────────────────────────────────

    private val logger = CrowmapClient.logger

    /** Whether the inline popup is currently visible. */
    private var popupVisible = false

    /** Top-left X of the popup in surface-pixel space. */
    private var popupX = 0
    /** Top-left Y of the popup in surface-pixel space. */
    private var popupY = 0

    /** Resolved coordinates (null = still loading or not found). */
    @Volatile private var jumpCoordX: Int? = null
    @Volatile private var jumpCoordZ: Int? = null
    /** True while the coordinate query is in-flight. */
    @Volatile private var coordsLoading = false

    // ── Popup layout (surface-pixel space) ───────────────────────────────

    companion object {
        private const val SCALE = 3
        private const val BTN_W = 120 * SCALE
        private const val BTN_H = 12 * SCALE
        private const val PAD   = 4  * SCALE
        private const val POPUP_W = BTN_W + PAD * 2
        private const val POPUP_H = PAD + BTN_H + PAD + BTN_H + PAD

        private const val BG_COLOR   = 0xDD000000.toInt()
        private const val BTN_COLOR  = 0xFF333333.toInt()
        private const val TEXT_COLOR = 0xFFFFFF
    }

    private val closeBtnTop get() = popupY + PAD
    private val closeBtnBot get() = closeBtnTop + BTN_H
    private val jumpBtnTop  get() = closeBtnBot + PAD
    private val jumpBtnBot  get() = jumpBtnTop + BTN_H
    private val btnLeft     get() = popupX + PAD
    private val btnRight    get() = popupX + PAD + BTN_W

    // ── Layout ───────────────────────────────────────────────────────────

    override fun doLayout(constraints: Constraints) {
        transform.setSize(constraints.maxFiniteOrMinSize())
    }

    override fun measureIntrinsicWidth(p0: Double): Double = 1.0
    override fun measureIntrinsicHeight(p0: Double): Double = 1.0
    override fun measureBaselineOffset(): OptionalDouble? = OptionalDouble.of(1.0)

    // ── Drawing ──────────────────────────────────────────────────────────

    override fun draw(graphics: BraidGraphics) {
        drawBrowserTexture(graphics)
        if (popupVisible) drawPopup(graphics)
    }

    private fun drawBrowserTexture(graphics: BraidGraphics) {
        val browser = BrowserManager.browser ?: return
        if (!browser.isTextureReady) return
        val textureId = browser.textureLocation ?: return
        val renderer = browser.renderer
        val texW = renderer.textureWidth
        val texH = renderer.textureHeight
        if (texW <= 0 || texH <= 0) return
        val width = transform.width().toInt()
        val height = transform.height().toInt()
        if (width <= 0 || height <= 0) return
        try {
            graphics.blit(
                net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                textureId,
                0, 0, 0f, 0f,
                width, height, texW, texH, texW, texH
            )
        } catch (_: Exception) { }
    }

    private fun drawPopup(graphics: BraidGraphics) {
        graphics.fill(popupX, popupY, popupX + POPUP_W, popupY + POPUP_H, BG_COLOR)

        val textScale = SCALE.toFloat()

        // Close button
        graphics.fill(btnLeft, closeBtnTop, btnRight, closeBtnBot, BTN_COLOR)
        val textY1 = closeBtnTop + (BTN_H - 9 * SCALE) / 2
        graphics.drawText(
            Component.literal("Close"),
            (btnLeft + PAD).toFloat(), textY1.toFloat(),
            textScale, TEXT_COLOR
        )

        // Jump button
        graphics.fill(btnLeft, jumpBtnTop, btnRight, jumpBtnBot, BTN_COLOR)
        val jumpLabel = when {
            coordsLoading -> "Jump (scanning...)"
            jumpCoordX != null && jumpCoordZ != null -> "Jump $jumpCoordX $jumpCoordZ"
            else -> "Jump (no coords)"
        }
        val textY2 = jumpBtnTop + (BTN_H - 9 * SCALE) / 2
        graphics.drawText(
            Component.literal(jumpLabel),
            (btnLeft + PAD).toFloat(), textY2.toFloat(),
            textScale, TEXT_COLOR
        )
    }

    // ── Popup lifecycle ──────────────────────────────────────────────────

    private fun openPopup(clickX: Double, clickY: Double) {
        val surfW = transform.width().toInt()
        val surfH = transform.height().toInt()
        popupX = (clickX - POPUP_W / 2.0).toInt().coerceIn(0, (surfW - POPUP_W).coerceAtLeast(0))
        popupY = (clickY - POPUP_H / 2.0).toInt().coerceIn(0, (surfH - POPUP_H).coerceAtLeast(0))
        popupVisible = true
        jumpCoordX = null
        jumpCoordZ = null
        coordsLoading = true
        ContextPopup.queryMapCoordinates(clickX.toInt(), clickY.toInt()).thenAccept { (x, z) ->
            jumpCoordX = x
            jumpCoordZ = z
            coordsLoading = false
        }
    }

    private fun closePopup() {
        popupVisible = false
        coordsLoading = false
    }

    private fun inButton(x: Double, y: Double, top: Int, bot: Int): Boolean {
        return x >= btnLeft && x < btnRight && y >= top && y < bot
    }

    // --- Mouse event forwarding to MCEF browser ---
    //
    // Braid display button mapping (from owo-lib's MinecraftMixin):
    //   MC left-click (attack key)  → braid button 0
    //   MC right-click (use key)    → braid button 1
    //
    //   braid 0 (MC attack) → browser left-click (0) — primary browsing
    //   braid 1 (MC use)    → open CrowMap context popup

    override fun onMouseDown(x: Double, y: Double, button: Int, modifiers: KeyModifiers): Boolean {
        val browser = BrowserManager.browser ?: return false

        if (popupVisible) {
            if (inButton(x, y, closeBtnTop, closeBtnBot)) { closePopup(); return true }
            if (inButton(x, y, jumpBtnTop, jumpBtnBot)) {
                val cx = jumpCoordX; val cz = jumpCoordZ
                closePopup()
                if (cx != null && cz != null) ContextPopup.executeJump(cx, cz)
                return true
            }
            closePopup(); return true
        }

        // Braid button 1 (MC use / right-click) → open context popup
        if (button == 0) { openPopup(x, y); return true }

        // Braid button 0 (MC attack / left-click) → browser left-click
        browser.sendMousePress(x.toInt(), y.toInt(), 2)
        return true
    }

    override fun onMouseUp(x: Double, y: Double, button: Int, modifiers: KeyModifiers): Boolean {
        if (popupVisible) return true
        //if (button == 1) return true
        val browser = BrowserManager.browser ?: return false
        browser.sendMouseRelease(x.toInt(), y.toInt(), button)
        return true
    }

    override fun onMouseMove(x: Double, y: Double) {
        if (popupVisible) return
        val browser = BrowserManager.browser ?: return
        browser.sendMouseMove(x.toInt(), y.toInt())
    }

    override fun onMouseScroll(mouseX: Double, mouseY: Double, xOffset: Double, yOffset: Double): Boolean {
        if (popupVisible) return true
        val browser = BrowserManager.browser ?: return false
        browser.sendMouseWheel(mouseX.toInt(), mouseY.toInt(), yOffset)
        return true
    }

    override fun onMouseDrag(x: Double, y: Double, deltaX: Double, deltaY: Double) {
        if (popupVisible) return
        val browser = BrowserManager.browser ?: return
        browser.sendMouseMove(x.toInt(), y.toInt())
    }
}


