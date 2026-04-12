package dev.diena.crowmap.client.config

import dev.diena.crowmap.client.CrowmapClient.Companion.mc
import net.minecraft.world.phys.Vec3

/**
 * Configuration for the CrowMap mod.
 */
object CrowmapConfig {

    /** The URL to load in the browser. */
    var mapUrl: String = "https://survival.horizonsend.net/"

    /** Whether the HUD minimap overlay is enabled. */
    var hudEnabled: Boolean = true

    /** Which corner to display the HUD minimap in. */
    var hudCorner: HudCorner = HudCorner.TOP_RIGHT

    /** The size (in pixels) of the HUD minimap square. */
    var hudSize: Int = 128

    /** Margin from the screen edge for the HUD minimap. */
    var hudMargin: Int = 8

    /** Whether the in-world projection is enabled. */
    var projectionEnabled: Boolean = false

    /** The world position of the projection's bottom-left corner. */
    var projectionPos: Vec3 = Vec3(0.0, 70.0, 0.0)

    /** The direction the projection faces (yaw in degrees). */
    var projectionYaw: Float = 0f

    /** Width of the in-world projection in blocks. */
    val projectionWidth: Float get() = mc.window.width.toFloat()/100

    /** Height of the in-world projection in blocks. */
    val projectionHeight: Float get() = mc.window.height.toFloat()/100

    /** Resolution of the braid display surface for in-world projection. */
    var projectionResWidth: Int = 640
    var projectionResHeight: Int = 480

    enum class HudCorner {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }
}

