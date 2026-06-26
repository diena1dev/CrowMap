package dev.diena.crowmap.client.screen

import dev.diena.crowmap.client.CrowmapClient
import dev.diena.crowmap.client.features.browser.WebDataReader
import io.wispforest.owo.ui.component.DropdownComponent
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.core.ParentUIComponent
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * Context-menu popup shown when the player right-clicks the browser (screen or projection).
 *
 * Uses [DropdownComponent.openContextMenu] on a given owo-ui screen, populated with:
 *   • **Close** – closes the screen
 *   • **Jump X Z** – sends the player to the map coordinates under the cursor
 *
 * Coordinates are scraped from the Dynmap page at the browser-pixel click position
 * using [WebDataReader.queryJs].
 */
object ContextPopup {

    private val logger = CrowmapClient.logger
    private val mc = CrowmapClient.mc

    /**
     * Opens the context popup on an existing owo-ui [screen] at GUI position ([guiX], [guiY]).
     *
     * @param screen        The active owo-ui screen.
     * @param root          The root [FlowLayout] of the screen.
     * @param browserPixelX The click X in browser-pixel space (for the JS coordinate query).
     * @param browserPixelY The click Y in browser-pixel space.
     * @param guiX          GUI-scaled mouse X where the popup should appear.
     * @param guiY          GUI-scaled mouse Y where the popup should appear.
     * @param onClose       Optional callback invoked when the popup is dismissed.
     */
    fun open(
        screen: Screen,
        root: FlowLayout,
        browserPixelX: Int,
        browserPixelY: Int,
        guiX: Double,
        guiY: Double,
        onClose: (() -> Unit)? = null
    ) {
        // Show the popup immediately so there's no delay
        showDropdown(screen, root, null, null, guiX, guiY, onClose)

        // Query coordinates in the background and recreate the popup when resolved
        queryMapCoordinates(browserPixelX, browserPixelY).thenAccept { (x, z) ->
            if (x == null && z == null) return@thenAccept  // no change from "no coords"
            mc.execute {
                if (mc.screen !== screen) return@execute
                // Remove the old dropdown (its the last child added to root)
                val children = root.children().toList()
                children.filterIsInstance<DropdownComponent>().forEach { it.remove() }
                // Show updated popup with coordinates
                showDropdown(screen, root, x, z, guiX, guiY, onClose)
            }
        }
    }

    private fun showDropdown(
        screen: Screen,
        root: FlowLayout,
        coordX: Int?,
        coordZ: Int?,
        guiX: Double,
        guiY: Double,
        onClose: (() -> Unit)?
    ) {
        val jumpLabel = if (coordX != null && coordZ != null)
            "Jump $coordX $coordZ"
        else
            "Jump (no coords)"

        val fleetJumpLabel = if (coordX != null && coordZ != null)
            "Fleet Jump $coordX $coordZ"
        else
            "Fleet Jump (no coords)"

        DropdownComponent.openContextMenu(
            screen,
            root,
            { parent, dd -> parent.child(dd) },
            guiX,
            guiY
        ) { dropdown ->
            dropdown
                .button(Component.literal("Close")) { dd ->
                    dd.remove()
                    onClose?.invoke()
                }
                .button(Component.literal(jumpLabel)) { dd ->
                    dd.remove()
                    onClose?.invoke()
                    if (coordX != null && coordZ != null) {
                        executeJump(coordX, coordZ)
                    }
                }
                .button(Component.literal(fleetJumpLabel)) { dd ->
                    dd.remove()
                    onClose?.invoke()
                    if (coordX != null && coordZ != null) {
                        executeFleetJump(coordX, coordZ)
                    }
                }
        }
    }

    /**
     * Opens a context popup as a new lightweight screen (used by the in-world projection
     * which doesn't have an existing owo-ui screen to attach to).
     *
     * @param browserPixelX The click X in browser-pixel space.
     * @param browserPixelY The click Y in browser-pixel space.
     */
    fun openAsScreen(browserPixelX: Int, browserPixelY: Int) {
        mc.execute {
            mc.setScreen(ContextPopupScreen(browserPixelX, browserPixelY))
        }
    }

    // ── Coordinate extraction ────────────────────────────────────────────

    /**
     * Queries the Dynmap page for the Minecraft world coordinates at the given
     * browser-pixel position.
     *
     * Tries (in order):
     * 1. Dynmap's Leaflet map: convert pixel → LatLng → world coords (with container offset)
     * 2. Parse the URL hash for a coordinate pattern
     * 3. Look for a coordinate display element on the page
     */
    internal fun queryMapCoordinates(
        pixelX: Int,
        pixelY: Int
    ): java.util.concurrent.CompletableFuture<Pair<Int?, Int?>> {
        val js = """
            (function() {
                // Strategy 1: Dynmap Leaflet — convert the pixel position to world coords
                if (window.dynmap && window.dynmap.map) {
                    var map = window.dynmap.map;
                    // Account for the map container offset in the page
                    var container = map.getContainer();
                    var rect = container.getBoundingClientRect();
                    var point = L.point($pixelX - rect.left, $pixelY - rect.top);
                    var latlng = map.containerPointToLatLng(point);
                    // Dynmap's projection converts lat/lng back to world X/Z
                    var proj = window.dynmap.getProjection
                             ? window.dynmap.getProjection()
                             : (window.dynmap.maptype && window.dynmap.maptype.getProjection
                                ? window.dynmap.maptype.getProjection()
                                : null);
                    if (proj && proj.fromLatLngToLocation) {
                        var loc = proj.fromLatLngToLocation(latlng, 64);
                        return Math.round(loc.x) + ',' + Math.round(loc.z);
                    }
                    // Fallback: use raw latlng (Dynmap flat maps use lng=X, lat=-Z)
                    return Math.round(latlng.lng) + ',' + Math.round(-latlng.lat);
                }
                // Strategy 2: Parse URL hash   e.g. #world;flat;123,64,-456,4
                var hash = window.location.hash;
                var m = hash.match(/(-?\d+),\s*-?\d+,\s*(-?\d+)/);
                if (m) return m[1] + ',' + m[2];
                // Strategy 3: Look for a coordinate display element
                var el = document.querySelector('.coord-control, .leaflet-control-coordinates, [class*="coord"]');
                if (el) {
                    var txt = el.innerText || '';
                    var cm = txt.match(/(-?\d+)[,\s]+(-?\d+)/);
                    if (cm) return cm[1] + ',' + cm[2];
                }
                return '';
            })()
        """.trimIndent()

        return WebDataReader.queryJs(js, timeoutMs = 2000).thenApply { result ->
            if (result.isBlank()) return@thenApply Pair(null, null)
            val parts = result.split(",")
            if (parts.size >= 2) {
                Pair(parts[0].trim().toIntOrNull(), parts[1].trim().toIntOrNull())
            } else {
                Pair(null, null)
            }
        }.exceptionally {
            logger.debug("[ContextPopup] Coordinate query failed: ${it.message}")
            Pair(null, null)
        }
    }

    // ── Jump execution ───────────────────────────────────────────────────

    /**
     * "Jumps" the player to the given X, Z coordinates by sending a chat command.
     */
    internal fun executeJump(x: Int, z: Int) {
        val player = mc.player ?: return
        // Close any open screen first
        mc.setScreen(null)
        // Send a teleport command
        player.connection.sendCommand("jump $x $z")
        logger.info("[ContextPopup] Jump to $x $z")
    }

    internal fun executeFleetJump(x: Int, z: Int) {
        val player = mc.player ?: return
        // Close any open screen first
        mc.setScreen(null)
        // Send a teleport command
        player.connection.sendCommand("fleet jump $x $z")
        logger.info("[ContextPopup] Fleet Jump to $x $z")
    }
}



