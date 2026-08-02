package dev.diena.crowmap.client.features.browser

import dev.diena.crowmap.client.CrowmapClient
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents

/**
 * Draws a small square marker on the Dynmap/LiveAtlas page at the player's current in-world
 * position, kept in sync over the Graphene bridge.
 *
 * The marker is placed using whatever world/map the page currently has *active* — read live from
 * the page's own JS state (LiveAtlas's Vuex-like store, or legacy Dynmap's `window.dynmap`), never
 * from the URL. The URL's world segment doesn't reliably track the active map: both Dynmap and
 * LiveAtlas are single-page apps that can switch the displayed world without a navigation (so the
 * page's `onLoad`-based re-injection never re-fires, and the URL can simply be stale).
 */
object PlayerMapMarker {

    private const val CSS_ID = "player-marker"
    private const val JS_ID = "player-marker"
    private const val CHANNEL = "crowmap:player-pos"

    /** Push a position update this often (in ticks). 5 ticks @ 20 TPS = 4 updates/sec. */
    private const val UPDATE_INTERVAL_TICKS = 5

    private var tickCounter = 0

    fun install() {
        BrowserManager.injectCss(CSS_ID, MARKER_CSS)
        BrowserManager.injectPersistentJs(JS_ID, MARKER_JS)

        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            val player = mc.player ?: return@register

            tickCounter++
            if (tickCounter % UPDATE_INTERVAL_TICKS != 0) return@register

            val bridge = BrowserManager.session?.bridge() ?: return@register
            if (!bridge.isReady()) return@register

            // Keep it dependency-free (no JSON lib on this path) — three numeric fields.
            val payload = "{\"x\":${player.x},\"y\":${player.y},\"z\":${player.z}}"
            bridge.emit(CHANNEL, payload)
        }

        CrowmapClient.debug("[CrowMap] Player map marker hook installed")
    }

    private val MARKER_CSS = """
        .crowmap-player-marker {
            width: 14px;
            height: 14px;
            background: #ff3b3b;
            border: 2px solid #ffffff;
            border-radius: 2px;
            box-shadow: 0 0 4px rgba(0,0,0,0.7);
            box-sizing: border-box;
            pointer-events: none;
        }
    """.trimIndent()

    // language=JavaScript
    private val MARKER_JS = """
        (function() {
            var marker = null;

            // Resolves the currently *active* map/world straight from page state — never the URL,
            // since both Dynmap and LiveAtlas can switch worlds as a client-side SPA transition
            // without a navigation (which is the only thing that would make the URL update).
            function getActiveMapContext() {
                // LiveAtlas (Vue 3 app) — same store/map access path used by the jump-menu coordinate lookup.
                try {
                    var appEl = document.querySelector('#app');
                    if (appEl && appEl.__vue_app__) {
                        var store = appEl.__vue_app__.config.globalProperties.${'$'}store;
                        var currentMap = store && store.state && store.state.currentMap;
                        var leafletContainer = document.querySelector('.leaflet-container');
                        var leafletMap = leafletContainer && leafletContainer._leaflet_map;
                        if (currentMap && leafletMap && typeof currentMap.locationToLatLng === 'function') {
                            var seaLevel = store.state.currentWorld ? store.state.currentWorld.seaLevel + 1 : 64;
                            return {
                                map: leafletMap,
                                toLatLng: function(x, y, z) {
                                    return currentMap.locationToLatLng({ x: x, y: (y == null ? seaLevel : y), z: z });
                                }
                            };
                        }
                    }
                } catch (_) {}

                // Legacy Dynmap
                try {
                    if (window.dynmap && window.dynmap.map) {
                        var proj = window.dynmap.getProjection
                            ? window.dynmap.getProjection()
                            : (window.dynmap.maptype && window.dynmap.maptype.getProjection
                                ? window.dynmap.maptype.getProjection()
                                : null);
                        if (proj && typeof proj.fromLocationToLatLng === 'function') {
                            return {
                                map: window.dynmap.map,
                                toLatLng: function(x, y, z) {
                                    return proj.fromLocationToLatLng({ x: x, y: (y == null ? 64 : y), z: z });
                                }
                            };
                        }
                    }
                } catch (_) {}

                return null;
            }

            function updateMarker(x, y, z) {
                var ctx = getActiveMapContext();
                if (!ctx) return;

                var latlng = ctx.toLatLng(x, y, z);
                if (!latlng) return;

                if (!marker || !marker._map) {
                    marker = L.marker(latlng, {
                        icon: L.divIcon({
                            className: 'crowmap-player-marker',
                            iconSize: [14, 14],
                            iconAnchor: [7, 7]
                        }),
                        interactive: false,
                        keyboard: false,
                        zIndexOffset: 1000
                    });
                    marker.addTo(ctx.map);
                } else {
                    marker.setLatLng(latlng);
                }
            }

            function handlePayload(payload) {
                if (!payload) return;
                updateMarker(payload.x, payload.y, payload.z);
            }

            function wire() {
                if (!globalThis.grapheneBridge) return false;
                globalThis.grapheneBridge.ready().then(function() {
                    globalThis.grapheneBridge.on('$CHANNEL', handlePayload);
                });
                return true;
            }

            if (!wire()) {
                var attempts = 0;
                var iv = setInterval(function() {
                    if (wire() || ++attempts > 40) clearInterval(iv);
                }, 250);
            }
        })();
    """.trimIndent()
}
