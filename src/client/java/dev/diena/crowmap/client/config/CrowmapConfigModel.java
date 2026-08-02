package dev.diena.crowmap.client.config;

import io.wispforest.owo.config.annotation.Config;
import io.wispforest.owo.config.annotation.ExcludeFromScreen;
import io.wispforest.owo.config.annotation.Modmenu;
import io.wispforest.owo.config.annotation.RangeConstraint;
import io.wispforest.owo.config.annotation.SectionHeader;

@Modmenu(modId = "crowmap")
@Config(name = "crowmap-config", wrapperName = "CrowmapConfigWrapper")
public class CrowmapConfigModel {

    // ── Browser ──────────────────────────────────────────────────────────

    @SectionHeader("Browser")
    public String mapUrl = "https://survival.horizonsend.net/";

    public boolean showUrlBar = false;

    public boolean useLocalLiveAtlas = false;

    public boolean debugLogging = false;

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

    /** Set by the "Place World Map" keybind — not user-editable. */
    @ExcludeFromScreen public double projectionX = 0.0;
    @ExcludeFromScreen public double projectionY = 70.0;
    @ExcludeFromScreen public double projectionZ = 0.0;
    @ExcludeFromScreen public float projectionYaw = 0f;
    @ExcludeFromScreen public float projectionPitch = 0f;

    // ── Anchor ───────────────────────────────────────────────────────────

    @SectionHeader("Anchor")
    public boolean anchorEnabled = true;

    public String anchorText = "[CrowMap]";

    /** Set by the "Place World Map" keybind near an anchor sign — not user-editable. */
    @ExcludeFromScreen public double anchorOffsetX = 0.0;
    @ExcludeFromScreen public double anchorOffsetY = 0.0;
    @ExcludeFromScreen public double anchorOffsetZ = 0.0;
    @ExcludeFromScreen public float anchorOffsetYaw = 0f;
    @ExcludeFromScreen public float anchorOffsetPitch = 0f;
    @ExcludeFromScreen public float anchorReferenceAngle = 0f;

    // ── LiveAtlas ─────────────────────────────────────────────────────────

    @SectionHeader("LiveAtlas")
    public boolean liveAtlasPlayersAboveMarkers = true;

    public boolean liveAtlasPlayersSearch = true;

    public boolean liveAtlasCompactPlayerMarkers = false;

    // public boolean liveAtlasDisableContextMenu = false;

    // public boolean liveAtlasDisableMarkerUI = false;

    // ── Overlay ──────────────────────────────────────────────────────────

    @SectionHeader("Overlay")
    public OverlayMode overlayMode = OverlayMode.HOLD;

    @RangeConstraint(min = 0.1, max = 4.0, decimalPlaces = 1)
    public double overlayScale = 1.0;

    // ── Enums ────────────────────────────────────────────────────────────

    public enum HudCorner {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }

    public enum OverlayMode {
        HOLD,
        TOGGLE
    }
}

