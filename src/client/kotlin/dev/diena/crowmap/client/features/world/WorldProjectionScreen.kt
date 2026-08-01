package dev.diena.crowmap.client.features.world

import dev.diena.crowmap.client.CrowmapClient
import dev.diena.crowmap.client.config.CrowmapConfig
import dev.diena.crowmap.client.features.browser.BrowserManager
import io.github.trethore.graphene.api.browser.input.BrowserPointerAction
import io.github.trethore.graphene.api.browser.input.BrowserPointerButton
import io.github.trethore.graphene.api.browser.input.BrowserPointerInput
import io.github.trethore.graphene.api.browser.input.BrowserScrollInput
import io.wispforest.owo.braid.core.BraidGraphics
import io.wispforest.owo.braid.core.Constraints
import io.wispforest.owo.braid.core.KeyModifiers
import io.wispforest.owo.braid.display.BraidDisplay
import io.wispforest.owo.braid.display.BraidDisplayBinding
import io.wispforest.owo.braid.display.DisplayQuad
import io.wispforest.owo.braid.framework.instance.LeafWidgetInstance
import io.wispforest.owo.braid.framework.instance.MouseListener
import io.wispforest.owo.braid.framework.widget.LeafInstanceWidget
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
 */
object WorldProjectionScreen {

    private val logger = CrowmapClient.logger
    private val mc = CrowmapClient.mc

    var display: BraidDisplay? = null
        private set

    val isActive: Boolean get() = display != null

    fun activate() {
        if (display != null) deactivate()
        if (!CrowmapConfig.projectionEnabled) return

        val quad = buildQuadFromConfig()
        val widget = BrowserProjectionWidget()
        val (bw, bh) = BrowserManager.computeBrowserSize()

        BrowserManager.requestHighResolution()
        display = BraidDisplay(quad, bw, bh, widget).renderAutomatically()
        BraidDisplayBinding.activate(display!!)

        logger.info("World projection activated at ${CrowmapConfig.projectionPos} (display ${bw}x${bh})")
    }

    fun deactivate() {
        if (display == null) return
        BraidDisplayBinding.deactivate(display!!)
        display = null
        BrowserManager.releaseHighResolution()
    }

    private var lastResizeNano = 0L
    private const val RESIZE_DEBOUNCE_NS = 500_000_000L

    fun onWindowResize() {
        if (!isActive) return
        val now = System.nanoTime()
        if (now - lastResizeNano < RESIZE_DEBOUNCE_NS) return
        lastResizeNano = now
        deactivate()
        CrowmapConfig.projectionEnabled = true
        activate()
    }

    fun updateQuad() {
        display?.quad = buildQuadFromConfig()
    }

    internal const val PLACE_REACH = 6.0

    internal fun normalizeYaw(yaw: Float): Float {
        var y = yaw % 360f
        if (y >= 180f) y -= 360f
        if (y < -180f) y += 360f
        return y
    }

    fun setPositionFromPlayer() {
        val player = mc.player ?: return
        val level = mc.level ?: return

        val eyePos = player.getEyePosition(1f)
        val lookVec = player.lookAngle
        val endPos = eyePos.add(lookVec.scale(PLACE_REACH))

        val hit = level.clip(
            ClipContext(eyePos, endPos, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player)
        )

        val placePos = if (hit.type == HitResult.Type.BLOCK) hit.location else endPos

        CrowmapConfig.projectionPos = placePos
        CrowmapConfig.projectionYaw = normalizeYaw(player.yRot)

        if (isActive) updateQuad()
    }

    fun toggle() {
        CrowmapConfig.projectionEnabled = !CrowmapConfig.projectionEnabled
        if (CrowmapConfig.projectionEnabled) activate() else deactivate()
    }

    private fun buildQuadFromConfig(): DisplayQuad {
        val pos = CrowmapConfig.projectionPos
        val yawRad = Math.toRadians(CrowmapConfig.projectionYaw.toDouble())
        val w = CrowmapConfig.projectionWidth
        val h = CrowmapConfig.projectionHeight

        val leftDir = Vec3(-cos(yawRad) * w, 0.0, -sin(yawRad) * w)
        val topDir  = Vec3(0.0, h, 0.0)

        val adjustedPos  = pos.add(topDir)
        val adjustedTop  = leftDir
        val adjustedLeft = topDir.scale(-1.0)

        return DisplayQuad(adjustedPos, adjustedTop, adjustedLeft)
    }
}

class BrowserProjectionWidget : LeafInstanceWidget() {
    override fun instantiate(): LeafWidgetInstance<*> = BrowserProjectionInstance(this)
}

/**
 * Renders the browser surface and forwards input to Graphene.
 *
 * Braid button mapping (owo-lib MinecraftMixin):
 *   MC right-click (use)   → braid button 1 → Graphene left-click (0)
 *   MC left-click (attack) → braid button 0 → ignored (would break blocks; braid captures it)
 *
 * Scroll and drag are forwarded directly.
 */
class BrowserProjectionInstance(widget: BrowserProjectionWidget) :
    LeafWidgetInstance<BrowserProjectionWidget>(widget), MouseListener {

    override fun doLayout(constraints: Constraints) {
        transform.setSize(constraints.maxFiniteOrMinSize())
    }

    override fun measureIntrinsicWidth(p0: Double): Double = 1.0
    override fun measureIntrinsicHeight(p0: Double): Double = 1.0
    override fun measureBaselineOffset(): OptionalDouble? = OptionalDouble.of(1.0)

    override fun draw(graphics: BraidGraphics) {
        val surface = BrowserManager.surface ?: return
        if (surface.browser().latestFrame().isEmpty) return
        val width = transform.width().toInt()
        val height = transform.height().toInt()
        if (width <= 0 || height <= 0) return
        try {
            // BraidGraphics is a GuiGraphics (via OwoUIGraphics), so the surface's normal
            // GuiGraphics render path works directly for this in-world quad too.
            surface.render(graphics, 0, 0, width, height)
        } catch (_: Exception) { }
    }

    private fun pointer(x: Double, y: Double, action: BrowserPointerAction, clickCount: Int = 0) {
        val surface = BrowserManager.surface ?: return
        val width = transform.width().toInt()
        val height = transform.height().toInt()
        if (width <= 0 || height <= 0) return
        surface.browser().sendPointerInput(
            BrowserPointerInput(
                action,
                surface.toBrowserX(x, width),
                surface.toBrowserY(y, height),
                BrowserPointerButton.LEFT,
                clickCount,
                emptySet()
            )
        )
    }

    // Braid 1 (MC use/right-click) → Graphene left-click
    // Braid 0 (MC attack/left-click) → ignored
    override fun onMouseDown(x: Double, y: Double, button: Int, modifiers: KeyModifiers): Boolean {
        if (button != 1) return true
        pointer(x, y, BrowserPointerAction.PRESS, 1)
        return true
    }

    override fun onMouseUp(x: Double, y: Double, button: Int, modifiers: KeyModifiers): Boolean {
        if (button != 1) return true
        pointer(x, y, BrowserPointerAction.RELEASE)
        return true
    }

    override fun onMouseMove(x: Double, y: Double) {
        pointer(x, y, BrowserPointerAction.MOVE)
    }

    override fun onMouseScroll(mouseX: Double, mouseY: Double, xOffset: Double, yOffset: Double): Boolean {
        val surface = BrowserManager.surface ?: return true
        val width = transform.width().toInt()
        val height = transform.height().toInt()
        if (width <= 0 || height <= 0) return true
        surface.browser().sendScrollInput(
            BrowserScrollInput(
                surface.toBrowserX(mouseX, width),
                surface.toBrowserY(mouseY, height),
                Math.round(xOffset * 120).toInt(),
                Math.round(yOffset * 120).toInt(),
                emptySet()
            )
        )
        return true
    }

    override fun onMouseDrag(x: Double, y: Double, deltaX: Double, deltaY: Double) {
        pointer(x, y, BrowserPointerAction.DRAG)
    }
}
