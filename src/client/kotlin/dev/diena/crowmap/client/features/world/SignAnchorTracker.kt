package dev.diena.crowmap.client.features.world

import dev.diena.crowmap.client.CrowmapClient
import dev.diena.crowmap.client.config.CrowmapConfig
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.SignBlock
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin

/**
 * Periodically scans for a sign whose text matches [CrowmapConfig.anchorText].
 *
 * When the anchor sign is found (or moves), the projection is repositioned to
 * `signPos + anchorOffset` at yaw `anchorOffsetYaw`, and the quad is updated.
 *
 * When the player "places" the projection via the Place Projection hotkey while
 * anchoring is enabled, the *offset* from the current anchor sign is stored
 * instead of an absolute position.
 */
object SignAnchorTracker {

    private val logger = CrowmapClient.logger
    private val mc = CrowmapClient.mc

    /** The block position of the last-seen anchor sign (null if none found yet). */
    var anchorSignPos: BlockPos? = null
        private set

    /** How often (in ticks) to scan for the anchor sign. */
    private const val SCAN_INTERVAL = 5 // ~1 second

    /** Radius (in blocks) around the player to search for anchor signs. */
    private const val SCAN_RADIUS = 32

    private var tickCounter = 0

    /**
     * Registers the tick handler.  Call once during client init.
     */
    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { _ ->
            if (!CrowmapConfig.anchorEnabled) return@register
            if (!CrowmapConfig.projectionEnabled) return@register

            tickCounter++
            if (tickCounter % SCAN_INTERVAL != 0) return@register

            val player = mc.player ?: return@register
            val level = mc.level ?: return@register

            val anchorText = CrowmapConfig.anchorText
            if (anchorText.isBlank()) return@register

            val playerPos = player.blockPosition()
            var foundPos: BlockPos? = null
            var foundFacingAngle = 0f

            // Scan loaded chunks around the player for a sign with matching text
            val minX = playerPos.x - SCAN_RADIUS
            val maxX = playerPos.x + SCAN_RADIUS
            val minZ = playerPos.z - SCAN_RADIUS
            val maxZ = playerPos.z + SCAN_RADIUS

            outer@ for (cx in (minX shr 4)..(maxX shr 4)) {
                for (cz in (minZ shr 4)..(maxZ shr 4)) {
                    val chunk = level.getChunkSource().getChunk(cx, cz, false) ?: continue
                    for ((pos, be) in chunk.blockEntities) {
                        if (be !is SignBlockEntity) continue
                        if (pos.x < minX || pos.x > maxX || pos.z < minZ || pos.z > maxZ) continue
                        if (signMatches(be, anchorText)) {
                            foundPos = pos
                            foundFacingAngle = getSignFacingAngle(level, pos)
                            break@outer
                        }
                    }
                }
            }

            if (foundPos != null && foundPos != anchorSignPos) {
                // Anchor sign appeared or moved — reposition the projection
                anchorSignPos = foundPos
                applyAnchorOffset(foundPos, foundFacingAngle)
                logger.info("[CrowMap] Anchor sign found at $foundPos (facing ${foundFacingAngle}°) — projection repositioned")
            } else if (foundPos == null && anchorSignPos != null) {
                // Anchor sign disappeared — keep projection where it is but clear tracking
                logger.info("[CrowMap] Anchor sign lost (was at $anchorSignPos)")
                anchorSignPos = null
            }
        }
    }

    /**
     * Returns true if any line on the front or back of the sign contains [text].
     */
    private fun signMatches(sign: SignBlockEntity, text: String): Boolean {
        for (isFront in listOf(true, false)) {
            val signText = sign.getText(isFront)
            for (i in 0 until 4) {
                val line = signText.getMessage(i, false).string
                if (line.contains(text, ignoreCase = true)) return true
            }
        }
        return false
    }

    /**
     * Applies the stored anchor offset to compute the absolute projection position
     * from the given sign block position, rotating the offset to account for
     * the difference between the sign's current facing and the stored reference angle.
     */
    private fun applyAnchorOffset(signPos: BlockPos, signFacingAngle: Float) {
        val storedOffset = CrowmapConfig.anchorOffset
        val referenceAngle = CrowmapConfig.anchorReferenceAngle

        // Delta = how far the new sign's facing has rotated from the reference
        val deltaAngleDeg = signFacingAngle - referenceAngle
        val deltaAngleRad = Math.toRadians(deltaAngleDeg.toDouble())

        // Rotate the X/Z components of the stored offset by the delta angle
        val (rotatedX, rotatedZ) = rotateXZ(storedOffset.x, storedOffset.z, deltaAngleRad)
        val rotatedOffset = Vec3(rotatedX, storedOffset.y, rotatedZ)

        // Sign pos is block-corner; use centre of block as origin
        val signCenter = Vec3(signPos.x + 0.5, signPos.y.toDouble(), signPos.z + 0.5)
        val newPos = signCenter.add(rotatedOffset)

        // Rotate the stored yaw offset by the same delta
        val rotatedYaw = WorldProjectionScreen.normalizeYaw(CrowmapConfig.anchorOffsetYaw + deltaAngleDeg)

        CrowmapConfig.projectionPos = newPos
        CrowmapConfig.projectionYaw = rotatedYaw
        CrowmapConfig.projectionPitch = CrowmapConfig.anchorOffsetPitch
        CrowmapConfig.save()

        if (WorldProjectionScreen.isActive) {
            WorldProjectionScreen.updateQuad()
        }

        CrowmapClient.debug("[CrowMap] Applied offset with deltaAngle=${deltaAngleDeg}° → pos=$newPos, yaw=$rotatedYaw")
    }

    /**
     * Returns the Y-rotation (in degrees) of the sign face at the given position.
     * Works for standing signs, wall signs, and hanging signs — all implement
     * [SignBlock.getYRotationDegrees].
     *
     * Returns 0 if the block is not a sign.
     */
    private fun getSignFacingAngle(level: net.minecraft.world.level.LevelAccessor, pos: BlockPos): Float {
        val state = level.getBlockState(pos)
        val block = state.block
        return if (block is SignBlock) {
            block.getYRotationDegrees(state)
        } else {
            0f
        }
    }

    /**
     * Rotates a 2D point (x, z) around the origin by [angleRad] radians.
     */
    private fun rotateXZ(x: Double, z: Double, angleRad: Double): Pair<Double, Double> {
        val cosA = cos(angleRad)
        val sinA = sin(angleRad)
        return Pair(
            x * cosA - z * sinA,
            x * sinA + z * cosA
        )
    }

    /**
     * Called when the player presses the Place Projection hotkey while anchoring
     * is enabled.  Performs a block raycast to find where the player is looking,
     * then computes and stores the relative offset from the current anchor sign
     * to that placement point.
     *
     * For example, if the sign is at (100, 100, 100) and the player places the
     * projection at (110, 100, 100), the stored offset is (10, 0, 0).  When any
     * matching sign is later found at a different position the projection is
     * repositioned to sign_pos + offset.
     */
    fun storeOffsetFromPlayer() {
        val player = mc.player ?: return
        val level  = mc.level  ?: return
        val signPos = anchorSignPos
        if (signPos == null) {
            logger.warn("[CrowMap] Cannot store anchor offset — no anchor sign found nearby")
            return
        }

        // Determine the sign's facing angle so offsets are direction-relative
        val signFacingAngle = getSignFacingAngle(level, signPos)

        // Raycast to find the block the player is looking at (same logic as setPositionFromPlayer)
        val eyePos  = player.getEyePosition(1f)
        val lookVec = player.lookAngle
        val endPos  = eyePos.add(lookVec.scale(WorldProjectionScreen.PLACE_REACH))

        val hit = level.clip(
            ClipContext(
                eyePos,
                endPos,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                player
            )
        )

        val placePos = if (hit.type == HitResult.Type.BLOCK) hit.location else endPos

        val signCenter = Vec3(signPos.x + 0.5, signPos.y.toDouble(), signPos.z + 0.5)
        val offset = placePos.subtract(signCenter)

        CrowmapConfig.anchorOffset = offset
        CrowmapConfig.anchorOffsetYaw = WorldProjectionScreen.normalizeYaw(player.yRot)
        CrowmapConfig.anchorOffsetPitch = player.xRot*-1
        CrowmapConfig.anchorReferenceAngle = signFacingAngle

        // Also update the absolute position so the projection moves immediately
        CrowmapConfig.projectionPos = placePos
        CrowmapConfig.projectionYaw = WorldProjectionScreen.normalizeYaw(player.yRot)
        CrowmapConfig.projectionPitch = player.xRot*-1

        if (WorldProjectionScreen.isActive) {
            WorldProjectionScreen.updateQuad()
        }

        CrowmapConfig.save()

        CrowmapClient.debug("[CrowMap] Stored anchor offset: $offset (yaw=${player.yRot}, signFacing=${signFacingAngle}°) from sign at $signPos")
    }
}



