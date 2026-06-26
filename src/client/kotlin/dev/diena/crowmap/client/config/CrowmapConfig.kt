package dev.diena.crowmap.client.config

import net.minecraft.world.phys.Vec3

/**
 * Convenience façade for the owo-lib managed config.
 *
 * [CrowmapConfigWrapper] is generated at compile-time from [CrowmapConfigModel]
 * by owo-lib's annotation processor.  This object re-exports the values with the
 * same property-style API the rest of the codebase already uses, so callers don't
 * need to change.
 */
object CrowmapConfig {

    /** The generated wrapper – created once on first access, loads from disk. */
    val wrapper: CrowmapConfigWrapper by lazy { CrowmapConfigWrapper.createAndLoad() }

    /** Shorthand. */
    private val W get() = wrapper

    /** Persists all current values to disk. */
    fun save() = W.save()

    // ── Browser ──────────────────────────────────────────────────────────

    var mapUrl: String
        get() = W.mapUrl()
        set(value) = W.mapUrl(value)

    var useLocalLiveAtlas: Boolean
        get() = W.useLocalLiveAtlas()
        set(value) = W.useLocalLiveAtlas(value)

    var debugLogging: Boolean
        get() = W.debugLogging()
        set(value) = W.debugLogging(value)

    // ── LiveAtlas UI ──────────────────────────────────────────────────────

    var liveAtlasPlayersAboveMarkers: Boolean
        get() = W.liveAtlasPlayersAboveMarkers()
        set(value) = W.liveAtlasPlayersAboveMarkers(value)

    var liveAtlasPlayersSearch: Boolean
        get() = W.liveAtlasPlayersSearch()
        set(value) = W.liveAtlasPlayersSearch(value)

    var liveAtlasCompactPlayerMarkers: Boolean
        get() = W.liveAtlasCompactPlayerMarkers()
        set(value) = W.liveAtlasCompactPlayerMarkers(value)

    // ── HUD ──────────────────────────────────────────────────────────────

    var hudEnabled: Boolean
        get() = W.hudEnabled()
        set(value) = W.hudEnabled(value)

    var hudCorner: CrowmapConfigModel.HudCorner
        get() = W.hudCorner()
        set(value) = W.hudCorner(value)

    var hudSize: Int
        get() = W.hudSize()
        set(value) = W.hudSize(value)

    var hudMargin: Int
        get() = W.hudMargin()
        set(value) = W.hudMargin(value)

    // ── Projection ───────────────────────────────────────────────────────

    var projectionEnabled: Boolean
        get() = W.projectionEnabled()
        set(value) = W.projectionEnabled(value)

    /** Absolute scale of the projection in blocks (width spans this many blocks). */
    var projectionScale: Double
        get() = W.projectionScale()
        set(value) = W.projectionScale(value)

    /** Projection position as a Vec3 (backed by three stored doubles). */
    var projectionPos: Vec3
        get() = Vec3(W.projectionX(), W.projectionY(), W.projectionZ())
        set(value) {
            W.projectionX(value.x)
            W.projectionY(value.y)
            W.projectionZ(value.z)
        }

    var projectionYaw: Float
        get() = W.projectionYaw()
        set(value) = W.projectionYaw(value)

    var projectionPitch: Float
        get() = W.projectionPitch()
        set(value) = W.projectionPitch(value)

    /** Width of the in-world projection in blocks (matches [projectionScale]). */
    val projectionWidth: Double get() = projectionScale

    /** Height of the in-world projection in blocks (aspect-correct from browser resolution). */
    val projectionHeight: Double get() {
        val (bw, bh) = dev.diena.crowmap.client.features.browser.BrowserManager.computeBrowserSize()
        return projectionScale * bh.toDouble() / bw.toDouble()
    }

    // ── Anchor ───────────────────────────────────────────────────────────

    var anchorEnabled: Boolean
        get() = W.anchorEnabled()
        set(value) = W.anchorEnabled(value)

    /** The text that identifies the anchor sign (matched against any line, front or back). */
    var anchorText: String
        get() = W.anchorText()
        set(value) = W.anchorText(value)

    /** Offset from the anchor sign's position to the projection origin. */
    var anchorOffset: Vec3
        get() = Vec3(W.anchorOffsetX(), W.anchorOffsetY(), W.anchorOffsetZ())
        set(value) {
            W.anchorOffsetX(value.x)
            W.anchorOffsetY(value.y)
            W.anchorOffsetZ(value.z)
        }

    var anchorOffsetYaw: Float
        get() = W.anchorOffsetYaw()
        set(value) = W.anchorOffsetYaw(value)

    var anchorOffsetPitch: Float
        get() = W.anchorOffsetPitch()
        set(value) = W.anchorOffsetPitch(value)

    /** The Y-rotation (degrees) of the sign face when the offset was stored.
     *  Used to rotate the offset when the anchor sign faces a different direction. */
    var anchorReferenceAngle: Float
        get() = W.anchorReferenceAngle()
        set(value) = W.anchorReferenceAngle(value)

    // ── Overlay ──────────────────────────────────────────────────────────

    var overlayMode: CrowmapConfigModel.OverlayMode
        get() = W.overlayMode()
        set(value) = W.overlayMode(value)

    var overlayScale: Double
        get() = W.overlayScale()
        set(value) = W.overlayScale(value)

    // Re-export the enum so existing `CrowmapConfig.HudCorner` references compile.
    typealias HudCorner = CrowmapConfigModel.HudCorner
}

