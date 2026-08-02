package dev.diena.crowmap.client.features.browser

import dev.diena.crowmap.client.CrowmapClient
import dev.diena.crowmap.client.config.CrowmapConfig
import dev.diena.crowmap.client.features.liveatlas.LocalLiveAtlasServer
import io.github.trethore.graphene.api.browser.BrowserLoadCompleted
import io.github.trethore.graphene.api.browser.BrowserLoadFailed
import io.github.trethore.graphene.api.browser.BrowserLoadListener
import io.github.trethore.graphene.api.browser.BrowserOptions
import io.github.trethore.graphene.api.browser.BrowserSession
import io.github.trethore.graphene.api.browser.bridge.BrowserBridgePolicy
import io.github.trethore.graphene.fabric.api.surface.BrowserSurface
import io.github.trethore.graphene.fabric.api.surface.BrowserSurfaceInputAdapter
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages the shared Graphene browser surface used by the screen, HUD, and world projection.
 */
object BrowserManager {
    private val logger = CrowmapClient.logger

    /** The shared browser surface (owns one [BrowserSession]). */
    var surface: BrowserSurface? = null
        private set

    /** The underlying browser session, when a surface exists. */
    val session: BrowserSession? get() = surface?.browser()

    /** The shared input adapter for the surface, used by [dev.diena.crowmap.client.screen.BrowserScreen]. */
    var inputAdapter: BrowserSurfaceInputAdapter? = null
        private set

    /** Whether the backend has been initialized. */
    var initialized: Boolean = false
        private set

    /** Maximum browser resolution (keeps web content usable on HiDPI). */
    const val MAX_BROWSER_WIDTH = 1920
    const val MAX_BROWSER_HEIGHT = 1080

    /**
     * Resolution used whenever nothing needs full quality (i.e. only the HUD minimap is
     * showing, which just crops a small square out of whatever's rendered). The HUD is on by
     * default and renders every frame, so without this the shared CEF session was paying to
     * render a near-1080p frame constantly during ordinary gameplay just to feed a corner icon —
     * that ambient cost dominated actual rendering-path choices (owo-ui vs. a manual widget)
     * enough to make them look "negligible."
     */
    private const val COMPACT_BROWSER_WIDTH = 480
    private const val COMPACT_BROWSER_HEIGHT = 270

    /** Last window size used for browser resolution — lets us detect changes. */
    private var lastWindowWidth = 0
    private var lastWindowHeight = 0

    /** Number of consumers (BrowserScreen open, world projection active) currently wanting full quality. */
    private var highResRequesters = 0

    // ── Custom CSS injection ─────────────────────────────────────────────

    /** Registered CSS snippets keyed by a caller-chosen id. Re-injected on every page load. */
    private val customCssMap = ConcurrentHashMap<String, String>()

    /**
     * Initializes the browser backend and registers the window-resize watcher.
     * Should be called early in the client lifecycle.
     */
    fun init() {
        initialized = true
        logger.info("Graphene browser backend initialized")

        ClientTickEvents.END_CLIENT_TICK.register { _ ->
            if (!initialized) return@register
            surface ?: return@register

            val mc = CrowmapClient.mc
            val curW = mc.window.width
            val curH = mc.window.height
            if (curW != lastWindowWidth || curH != lastWindowHeight) {
                lastWindowWidth = curW
                lastWindowHeight = curH
                applyResolution()
                dev.diena.crowmap.client.features.world.WorldProjectionScreen.onWindowResize()
            }
        }
    }

    /**
     * Returns the URL the browser should load.
     */
    fun getTargetUrl(): String =
        if (CrowmapConfig.useLocalLiveAtlas && LocalLiveAtlasServer.state == LocalLiveAtlasServer.State.READY) {
            LocalLiveAtlasServer.localUrl
        } else {
            CrowmapConfig.mapUrl
        }

    /**
     * Creates or returns the shared browser surface with the configured URL, at the given
     * fixed browser resolution.
     */
    fun getOrCreateBrowser(width: Int = MAX_BROWSER_WIDTH, height: Int = MAX_BROWSER_HEIGHT): BrowserSurface? {
        if (!initialized) {
            logger.warn("Cannot create browser - BrowserManager not initialized")
            return null
        }

        if (surface == null) {
            try {
                val targetUrl = getTargetUrl()
                CrowmapClient.debug("[CrowMap] Creating browser surface for URL: $targetUrl at size ${width}x${height}")

                val options = BrowserOptions.builder()
                    // Dynmap is a fixed, user-configured server (not arbitrary remote content),
                    // so allow the bridge for the origin the browser was created with.
                    .bridgePolicy(BrowserBridgePolicy.initialOrigin())
                    .build()

                val newSurface = BrowserSurface.builder(CrowmapClient.graphene())
                    .url(targetUrl)
                    .size(width, height)
                    .resolution(width, height)
                    .options(options)
                    .build()

                newSurface.browser().onLoad(object : BrowserLoadListener {
                    override fun onLoadCompleted(event: BrowserLoadCompleted) {
                        if (event.mainFrame()) {
                            CrowmapClient.debug("[CrowMap] Page load finished — re-injecting ${customCssMap.size} CSS snippet(s), ${customJsMap.size} JS hook(s)")
                            applyAllCss()
                            applyAllPersistentJs()
                        }
                    }

                    override fun onLoadFailed(event: BrowserLoadFailed) {
                        logger.warn("[CrowMap] Page load failed: ${event.url()} (${event.message()})")
                    }
                })

                surface = newSurface
                inputAdapter = BrowserSurfaceInputAdapter(newSurface)
                WebDataReader.install(newSurface.browser())

                logger.info("[CrowMap] Browser surface created")
            } catch (e: Exception) {
                logger.error("[CrowMap] Failed to create browser surface", e)
                return null
            }
        }

        applyResolution()
        return surface
    }

    /**
     * Computes a browser resolution that matches the Minecraft window's aspect ratio
     * while staying within [MAX_BROWSER_WIDTH]×[MAX_BROWSER_HEIGHT].
     * Shared by BrowserScreen and WorldProjectionScreen.
     */
    fun computeBrowserSize(): Pair<Int, Int> = fitAspect(MAX_BROWSER_WIDTH, MAX_BROWSER_HEIGHT)

    /** Low-cost resolution used when nothing needs full quality — see [COMPACT_BROWSER_WIDTH]. */
    private fun compactBrowserSize(): Pair<Int, Int> = fitAspect(COMPACT_BROWSER_WIDTH, COMPACT_BROWSER_HEIGHT)

    private fun fitAspect(maxWidth: Int, maxHeight: Int): Pair<Int, Int> {
        val mc = CrowmapClient.mc
        val winW = mc.window.width.coerceAtLeast(1)
        val winH = mc.window.height.coerceAtLeast(1)
        val aspect = winW.toDouble() / winH

        val w: Int
        val h: Int
        if (aspect >= maxWidth.toDouble() / maxHeight) {
            w = maxWidth
            h = (maxWidth / aspect).toInt().coerceAtLeast(1)
        } else {
            h = maxHeight
            w = (maxHeight * aspect).toInt().coerceAtLeast(1)
        }
        return Pair(w, h)
    }

    /**
     * Requests full-quality rendering (e.g. the full browser screen is open, or the world
     * projection is active). Calls must be paired with [releaseHighResolution].
     */
    fun requestHighResolution() {
        highResRequesters++
        applyResolution()
    }

    /** Releases a previous [requestHighResolution] call. */
    fun releaseHighResolution() {
        highResRequesters = (highResRequesters - 1).coerceAtLeast(0)
        applyResolution()
    }

    private fun applyResolution() {
        val (w, h) = if (highResRequesters > 0) computeBrowserSize() else compactBrowserSize()
        surface?.setResolution(w, h)
    }

    /**
     * Sets the browser's fixed pixel resolution directly (used by the world-projection texture path).
     */
    fun resize(width: Int, height: Int) {
        surface?.setResolution(width, height)
    }

    /**
     * Navigates the shared browser to a new URL.
     */
    fun navigate(url: String) {
        session?.navigate(url)
    }

    /** The currently loaded URL, or the configured target URL if no browser session exists yet. */
    fun currentUrl(): String = session?.currentUrl() ?: getTargetUrl()

    fun canGoBack(): Boolean = session?.canGoBack() ?: false

    fun canGoForward(): Boolean = session?.canGoForward() ?: false

    fun goBack() {
        session?.let { if (it.canGoBack()) it.goBack() }
    }

    fun goForward() {
        session?.let { if (it.canGoForward()) it.goForward() }
    }

    fun reload() {
        session?.reload()
    }

    /**
     * Fully tears down and recreates the shared CEF browser session (as opposed to [reload],
     * which just reloads the page within the existing session) — for when the browser itself gets
     * stuck rather than just the page. Callers holding their own [BrowserSurface]/input-adapter
     * references (e.g. [dev.diena.crowmap.client.screen.BrowserScreen]) must refresh them from
     * [surface]/[inputAdapter] afterward, same as after any other [getOrCreateBrowser] call.
     */
    fun restart() {
        logger.info("[CrowMap] Restarting browser session")
        closeBrowser()
        val (bw, bh) = computeBrowserSize()
        getOrCreateBrowser(bw, bh)
    }

    /**
     * Closes and cleans up the shared browser surface.
     */
    fun closeBrowser() {
        inputAdapter?.close()
        inputAdapter = null
        surface?.close()
        surface = null
    }

    /**
     * Shuts down the browser backend entirely.
     */
    fun shutdown() {
        WebDataReader.shutdown()
        closeBrowser()
        customCssMap.clear()
        customJsMap.clear()
        initialized = false
    }

    // ── Custom CSS injection API ─────────────────────────────────────────

    /**
     * Injects a CSS snippet into the currently loaded page. The snippet is
     * identified by [id] so it can be updated or removed later. If a snippet
     * with the same [id] already exists it is replaced.
     *
     * The CSS is automatically re-injected whenever the page reloads or
     * navigates to a new URL.
     *
     * @param id  A unique identifier for this CSS snippet (e.g. "hide-sidebar").
     * @param css The raw CSS text to inject.
     */
    fun injectCss(id: String, css: String) {
        customCssMap[id] = css
        // Inject immediately into the current page (if a browser exists)
        applyOneCss(id, css)
        CrowmapClient.debug("[CrowMap] CSS injected (id=$id, length=${css.length})")
    }

    /**
     * Removes a previously injected CSS snippet and strips it from the current page.
     *
     * @param id The identifier passed to [injectCss].
     */
    fun removeCss(id: String) {
        customCssMap.remove(id) ?: return
        // Remove the <style> element from the live page
        val removeJs = """
            (function(){
                var el = document.getElementById('crowmap-css-$id');
                if(el) el.remove();
            })();
        """.trimIndent()
        session?.executeScript(removeJs)
        CrowmapClient.debug("[CrowMap] CSS removed (id=$id)")
    }

    /**
     * Removes all previously injected CSS snippets.
     */
    fun clearCss() {
        val ids = customCssMap.keys.toList()
        customCssMap.clear()
        val activeSession = session ?: return
        val removeJs = ids.joinToString("\n") { id ->
            "(function(){ var el = document.getElementById('crowmap-css-$id'); if(el) el.remove(); })();"
        }
        if (removeJs.isNotEmpty()) {
            activeSession.executeScript(removeJs)
        }
        CrowmapClient.debug("[CrowMap] All custom CSS cleared")
    }

    /**
     * Returns an unmodifiable snapshot of the currently registered CSS snippets.
     */
    fun getInjectedCss(): Map<String, String> = customCssMap.toMap()

    // ── Full-screen-only chrome (sidebar, coordinate display) ────────────

    private const val HUD_CHROME_CSS_ID = "hide-map-chrome-for-hud"

    /**
     * Sidebar (world/player list) and coordinate display only make sense — and only fit — when
     * the map is the full-screen [dev.diena.crowmap.client.screen.BrowserScreen]. The rest of the
     * time (HUD minimap corner, in-world projection) they just cover the map image, so they're
     * hidden by default and only shown while the full-screen view is open.
     *
     * `.sidebar` is LiveAtlas's sidebar root (world/marker/player panel); `.dynmap .panel` is
     * legacy Dynmap's equivalent. `.leaflet-control-coordinates` is the hover-coordinate readout —
     * same class name in both, LiveAtlas intentionally kept it Dynmap-compatible.
     */
    private const val HUD_CHROME_HIDE_CSS = """
        .sidebar { display: none !important; }
        .dynmap .panel { display: none !important; }
        .leaflet-control-coordinates { display: none !important; }
    """

    /** Hides the sidebar/coordinate display — the default state (HUD/projection views). */
    fun hideFullScreenChrome() = injectCss(HUD_CHROME_CSS_ID, HUD_CHROME_HIDE_CSS)

    /** Shows the sidebar/coordinate display again — call while the full-screen view is open. */
    fun showFullScreenChrome() = removeCss(HUD_CHROME_CSS_ID)

    // ── Jump to the player's current world ────────────────────────────────

    /**
     * Switches the page's active map to whichever of its worlds corresponds to [dimensionKeyword]
     * — a no-op if no matching world is configured on this Dynmap instance (i.e. the player's
     * current dimension just isn't mapped there).
     *
     * [dimensionKeyword] should be one of `"overworld"`, `"nether"`, `"end"`, or a raw dimension
     * path for anything else (custom/modded dimensions) — see the call site in [dev.diena.crowmap.client.screen.BrowserScreen].
     *
     * World-name matching is a suffix/substring heuristic (`_nether`/`DIM-1` → nether,
     * `_end`/`DIM1` → end, else overworld), because there's no authoritative client-side mapping
     * from a Minecraft dimension to a Dynmap world name — this mirrors the convention Dynmap's
     * own client JS uses internally (see `map.js`'s world-icon selection) for the same reason.
     *
     * Legacy Dynmap exposes `window.dynmap.selectWorld(name)`/`window.dynmap.worlds` (an object
     * keyed by world name) for exactly this. LiveAtlas has no public switch function, so this
     * reaches into its Vuex-like store directly (`store.commit('setCurrentMap', ...)`, the same
     * mutation `WorldListItem.vue`'s world/map radio buttons trigger) and its `store.state.worlds`
     * (a JS `Map`).
     */
    fun jumpToWorld(dimensionKeyword: String) {
        val js = """
            (function() {
                var keyword = '$dimensionKeyword';

                function matchesKeyword(name) {
                    var n = (name || '').toLowerCase();
                    if (keyword === 'nether') return n.indexOf('nether') !== -1 || n === 'dim-1';
                    if (keyword === 'end') return n.indexOf('the_end') !== -1 || n.indexOf('_end') !== -1 || n === 'dim1';
                    if (keyword === 'overworld') {
                        return n.indexOf('nether') === -1 && n.indexOf('_end') === -1 && n !== 'dim-1' && n !== 'dim1';
                    }
                    return n.indexOf(keyword) !== -1;
                }

                function firstMapName(maps) {
                    if (!maps) return null;
                    if (typeof maps.values === 'function') {
                        var it = maps.values().next();
                        return it.done ? null : it.value.name;
                    }
                    if (Array.isArray(maps)) return maps.length ? maps[0].name : null;
                    var keys = Object.keys(maps);
                    return keys.length ? maps[keys[0]].name : null;
                }

                // Strategy 1: LiveAtlas
                try {
                    var appEl = document.querySelector('#app');
                    if (appEl && appEl.__vue_app__) {
                        var store = appEl.__vue_app__.config.globalProperties.${'$'}store;
                        var worldsMap = store && store.state && store.state.worlds;
                        if (worldsMap && typeof worldsMap.values === 'function') {
                            var candidates = Array.from(worldsMap.values());
                            for (var i = 0; i < candidates.length; i++) {
                                if (!matchesKeyword(candidates[i].name)) continue;
                                var mapName = firstMapName(candidates[i].maps);
                                if (mapName) {
                                    store.commit('setCurrentMap', { worldName: candidates[i].name, mapName: mapName });
                                    return;
                                }
                            }
                        }
                    }
                } catch (_) {}

                // Strategy 2: Legacy Dynmap
                try {
                    if (window.dynmap && window.dynmap.worlds && window.dynmap.selectWorld) {
                        var names = Object.keys(window.dynmap.worlds);
                        for (var j = 0; j < names.length; j++) {
                            if (matchesKeyword(names[j])) {
                                window.dynmap.selectWorld(names[j]);
                                return;
                            }
                        }
                    }
                } catch (_) {}
            })();
        """.trimIndent()

        WebDataReader.executeJs(js)
    }

    // ── Internal CSS helpers ─────────────────────────────────────────────

    /**
     * Applies a single CSS snippet to the current page by injecting a `<style>` element.
     */
    private fun applyOneCss(id: String, css: String) {
        val activeSession = session ?: return
        val escaped = css
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        val js = """
            (function(){
                var existing = document.getElementById('crowmap-css-$id');
                if(existing) existing.remove();
                var style = document.createElement('style');
                style.id = 'crowmap-css-$id';
                style.textContent = '$escaped';
                (document.head || document.documentElement).appendChild(style);
            })();
        """.trimIndent()
        activeSession.executeScript(js)
    }

    /**
     * Re-applies all registered CSS snippets to the current page.
     */
    private fun applyAllCss() {
        customCssMap.forEach { (id, css) -> applyOneCss(id, css) }
    }

    // ── Persistent JS hook API ───────────────────────────────────────────

    /** Registered JS hooks keyed by a caller-chosen id. Re-executed on every page load. */
    private val customJsMap = ConcurrentHashMap<String, String>()

    /**
     * Registers a JS snippet that should run on the current page and be re-run on every
     * subsequent page load/navigation (page JS state — including bridge listeners — doesn't
     * survive a navigation). If a hook with the same [id] already exists it is replaced.
     *
     * Unlike [injectCss], this doesn't clean up prior DOM/state itself — the snippet should be
     * idempotent (e.g. guard with a `window.__xyzInstalled` flag) since it may run more than
     * once per page (once here, again from the next `onLoad`).
     *
     * @param id A unique identifier for this hook (e.g. "player-marker").
     * @param js The raw JS to execute (typically an IIFE).
     */
    fun injectPersistentJs(id: String, js: String) {
        customJsMap[id] = js
        session?.executeScript(js)
        CrowmapClient.debug("[CrowMap] JS hook installed (id=$id, length=${js.length})")
    }

    /** Removes a previously registered JS hook (does not undo anything it already did to the page). */
    fun removePersistentJs(id: String) {
        customJsMap.remove(id)
        CrowmapClient.debug("[CrowMap] JS hook removed (id=$id)")
    }

    private fun applyAllPersistentJs() {
        val activeSession = session ?: return
        customJsMap.values.forEach { activeSession.executeScript(it) }
    }
}
