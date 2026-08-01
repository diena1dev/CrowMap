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

        val addRouteHereLabel  = if (coordX != null && coordZ != null)
            "Add Route $coordX $coordZ"
        else
            "Add Route (no coords)"

        val copyCoordinateLabel = if (coordX != null && coordZ != null)
            "Copy $coordX $coordZ"
        else
            "Copy (no coords)"

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
                .button(Component.literal(addRouteHereLabel)) { dd ->
                    dd.remove()
                    onClose?.invoke()
                    if (coordX != null && coordZ != null) {
                        queryActiveWorld().thenAccept { world ->
                            if (world == null) {
                                logger.warn("[ContextPopup] Could not determine the active Dynmap world; route not added")
                                return@thenAccept
                            }
                            mc.execute { addRouteFromCoordinates(world, coordX, coordZ) }
                        }
                    }
                }
                .button(Component.literal(copyCoordinateLabel)) { dd ->
                    dd.remove()
                    onClose?.invoke()
                    if (coordX != null && coordZ != null) {
                        copyCoordinateToClipboard(coordX, coordZ)
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
                var pixelX = $pixelX, pixelY = $pixelY;

                // Strategy 1: LiveAtlas — access Vuex store via Vue 3 app instance.
                // Uses the same currentMap.latLngToLocation() path the coordinate control uses.
                try {
                    var appEl = document.querySelector('#app');
                    if (appEl && appEl.__vue_app__) {
                        var store = appEl.__vue_app__.config.globalProperties.${'$'}store;
                        var currentMap = store && store.state && store.state.currentMap;
                        var leafletContainer = document.querySelector('.leaflet-container');
                        var leafletMap = leafletContainer && leafletContainer._leaflet_map;
                        if (currentMap && leafletMap) {
                            var rect = leafletMap.getContainer().getBoundingClientRect();
                            var latlng = leafletMap.containerPointToLatLng(
                                L.point(pixelX - rect.left, pixelY - rect.top)
                            );
                            var seaLevel = store.state.currentWorld ? store.state.currentWorld.seaLevel + 1 : 64;
                            var loc = currentMap.latLngToLocation(latlng, seaLevel);
                            if (loc && typeof loc.x === 'number') {
                                return Math.round(loc.x) + ',' + Math.round(loc.z);
                            }
                        }
                    }
                } catch (_) {}

                // Strategy 2: Dynmap — window.dynmap exposes the Leaflet map and projection.
                if (window.dynmap && window.dynmap.map) {
                    var map = window.dynmap.map;
                    var rect = map.getContainer().getBoundingClientRect();
                    var latlng = map.containerPointToLatLng(L.point(pixelX - rect.left, pixelY - rect.top));
                    var proj = window.dynmap.getProjection
                             ? window.dynmap.getProjection()
                             : (window.dynmap.maptype && window.dynmap.maptype.getProjection
                                ? window.dynmap.maptype.getProjection()
                                : null);
                    if (proj && proj.fromLatLngToLocation) {
                        var loc = proj.fromLatLngToLocation(latlng, 64);
                        return Math.round(loc.x) + ',' + Math.round(loc.z);
                    }
                    return Math.round(latlng.lng) + ',' + Math.round(-latlng.lat);
                }

                // Strategy 3: Coordinate display element (LiveAtlas renders .leaflet-control-coordinates
                // bottom-left; shows last hovered position which is close enough for a right-click).
                var coordEl = document.querySelector('.leaflet-control-coordinates');
                if (coordEl) {
                    var txt = coordEl.textContent || '';
                    // LiveAtlas "X: 123, Z: 456" or plain "123, 456"
                    var xz = txt.match(/X:\s*(-?\d+).*?Z:\s*(-?\d+)/);
                    if (xz) return xz[1] + ',' + xz[2];
                    var plain = txt.match(/(-?\d+)[^-\d]+(-?\d+)/);
                    if (plain) return plain[1] + ',' + plain[2];
                }

                // Strategy 4: URL hash (Dynmap format #world;flat;x,y,z,zoom)
                var m = window.location.hash.match(/(-?\d+),\s*-?\d+,\s*(-?\d+)/);
                if (m) return m[1] + ',' + m[2];

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

    /**
     * Queries the Dynmap page for the name of the world it currently has active.
     *
     * Deliberately doesn't read the URL — Dynmap and LiveAtlas are both single-page apps that can
     * switch the displayed world without a navigation, so the URL's world segment can be stale.
     * Reads the page's live JS state instead (same approach as [dev.diena.crowmap.client.features.browser.PlayerMapMarker]):
     *
     * 1. LiveAtlas: `store.state.currentWorld.name` (Vuex-like store via the Vue 3 app instance)
     * 2. Legacy Dynmap: `window.dynmap.world.name`
     */
    internal fun queryActiveWorld(): java.util.concurrent.CompletableFuture<String?> {
        val js = """
            (function() {
                // Strategy 1: LiveAtlas
                try {
                    var appEl = document.querySelector('#app');
                    if (appEl && appEl.__vue_app__) {
                        var store = appEl.__vue_app__.config.globalProperties.${'$'}store;
                        var world = store && store.state && store.state.currentWorld;
                        if (world && world.name) return world.name;
                    }
                } catch (_) {}

                // Strategy 2: Legacy Dynmap
                try {
                    if (window.dynmap && window.dynmap.world && window.dynmap.world.name) {
                        return window.dynmap.world.name;
                    }
                } catch (_) {}

                return '';
            })()
        """.trimIndent()

        return WebDataReader.queryJs(js, timeoutMs = 2000).thenApply { result ->
            result.trim().ifBlank { null }
        }.exceptionally {
            logger.debug("[ContextPopup] Active world query failed: ${it.message}")
            null
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

    internal fun copyCoordinateToClipboard(x: Int, z: Int) {

        mc.setScreen(null)
	    CrowmapClient.mc.keyboardHandler.clipboard = "$x $z"

        logger.info("[ContextPopup] Copied Coordinates $x $z")

    }

    internal fun addRouteFromCoordinates(world: String, x: Int, z: Int) {

        val player = mc.player ?: return

        mc.setScreen(null)
        player.connection.sendCommand("route add $world $x $z")

        logger.info("[ContextPopup] Added Route $world $x $z")

    }

}



