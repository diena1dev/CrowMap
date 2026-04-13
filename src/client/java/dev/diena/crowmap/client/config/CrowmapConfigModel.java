package dev.diena.crowmap.client.config;

import io.wispforest.owo.config.annotation.Config;
import io.wispforest.owo.config.annotation.Modmenu;
import io.wispforest.owo.config.annotation.RangeConstraint;
import io.wispforest.owo.config.annotation.SectionHeader;

@Modmenu(modId = "crowmap")
@Config(name = "crowmap-config", wrapperName = "CrowmapConfigWrapper")
public class CrowmapConfigModel {

    // ── Browser ──────────────────────────────────────────────────────────

    @SectionHeader("Browser")
    public String mapUrl = "https://survival.horizonsend.net/";

    // ── HUD ──────────────────────────────────────────────────────────────

    @SectionHeader("HUD")
    public boolean hudEnabled = true;

    public HudCorner hudCorner = HudCorner.TOP_RIGHT;

    @RangeConstraint(min = 32, max = 512)
    public int hudSize = 128;

    @RangeConstraint(min = 0, max = 64)
    public int hudMargin = 8;

    // ── Projection ───────────────────────────────────────────────────────

    @SectionHeader("Projection")
    public boolean projectionEnabled = false;

    @RangeConstraint(min = 0.5, max = 32.0, decimalPlaces = 1)
    public double projectionScale = 3.0;

    public double projectionX = 0.0;
    public double projectionY = 70.0;
    public double projectionZ = 0.0;

    public float projectionYaw = 0f;
    public float projectionPitch = 0f;

    // ── Anchor ───────────────────────────────────────────────────────────

    @SectionHeader("Anchor")
    public boolean anchorEnabled = true;

    public String anchorText = "[CrowMap]";

    public double anchorOffsetX = 0.0;
    public double anchorOffsetY = 0.0;
    public double anchorOffsetZ = 0.0;
    public float anchorOffsetYaw = 0f;
    public float anchorOffsetPitch = 0f;

    /** The Y-rotation (in degrees) of the sign face when the offset was stored.
     *  Used to rotate the offset when the anchor sign faces a different direction. */
    public float anchorReferenceAngle = 0f;

    // ── Enums ────────────────────────────────────────────────────────────

    public enum HudCorner {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }
}

