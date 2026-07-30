package dev.diena.crowmap.client.features.browser

import dev.diena.crowmap.client.CrowmapClient
import io.github.trethore.graphene.api.GrapheneSubscription
import io.github.trethore.graphene.api.browser.BrowserSession
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Reads data from the embedded Graphene browser by executing JavaScript on the page
 * and receiving results back through Graphene's Java/JavaScript bridge.
 *
 * ## Usage
 *
 * **One-shot query** – evaluate a JS expression and get the result as a string:
 * ```kotlin
 * val future = WebDataReader.queryJs("document.querySelector('#score').innerText")
 * future.thenAccept { value -> println("Score: $value") }
 * ```
 *
 * **Periodic polling** – continuously read a value at a fixed interval:
 * ```kotlin
 * val handle = WebDataReader.poll(
 *     jsExpression = "document.getElementById('timer').textContent",
 *     intervalMs = 1000
 * ) { value -> println("Timer: $value") }
 *
 * // Later, stop polling:
 * handle.cancel()
 * ```
 *
 * **Fire-and-forget JS execution** (no return value):
 * ```kotlin
 * WebDataReader.executeJs("document.body.style.background = 'red'")
 * ```
 */
object WebDataReader {

    private val logger = CrowmapClient.logger

    /** Bridge channel used for JS -> Java query replies. Must not start with "graphene:". */
    private const val QUERY_CHANNEL = "crowmap:query"

    /** The active bridge subscription for [QUERY_CHANNEL], if installed. */
    private var querySubscription: GrapheneSubscription? = null

    /** Pending one-shot query futures keyed by a monotonically increasing query id. */
    private val pendingQueries = ConcurrentHashMap<Long, CompletableFuture<String>>()

    /** Counter for generating unique query ids. */
    private val queryIdCounter = AtomicLong(0)

    // ── Polling ──────────────────────────────────────────────────────────

    /** Single-thread scheduler used for periodic polls. */
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "CrowMap-WebDataReader").apply { isDaemon = true }
    }

    /** Active polling handles so they can be cancelled in bulk on shutdown. */
    private val activePolls = ConcurrentHashMap<Long, ScheduledFuture<*>>()
    private val pollIdCounter = AtomicLong(0)

    // ── Initialisation ───────────────────────────────────────────────────

    /**
     * Installs the bridge listener that receives query replies from the page.
     * Must be called once per [BrowserSession] (i.e. during [BrowserManager.getOrCreateBrowser]).
     */
    fun install(session: BrowserSession) {
        querySubscription?.unsubscribe()
        querySubscription = session.bridge().onEvent(QUERY_CHANNEL) { _, payloadJson ->
            handleIncomingQuery(payloadJson)
        }
        logger.info("[WebDataReader] Bridge query listener installed (channel=$QUERY_CHANNEL)")
    }

    /**
     * Removes the bridge listener and cancels all pending work.
     */
    fun shutdown() {
        querySubscription?.unsubscribe()
        querySubscription = null

        // Complete all pending futures exceptionally
        pendingQueries.forEach { (_, future) ->
            future.completeExceptionally(IllegalStateException("WebDataReader shut down"))
        }
        pendingQueries.clear()

        // Cancel active polls
        activePolls.values.forEach { it.cancel(false) }
        activePolls.clear()

        logger.info("[WebDataReader] Shut down")
    }

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Executes arbitrary JavaScript on the current page (fire-and-forget, no return value).
     */
    fun executeJs(code: String) {
        val session = BrowserManager.session
        if (session == null) {
            logger.warn("[WebDataReader] executeJs called but no browser is available")
            return
        }
        session.executeScript(code)
    }

    /**
     * Evaluates a JavaScript **expression** and returns the result as a [CompletableFuture<String>].
     *
     * Under the hood this injects a small JS snippet that evaluates [jsExpression],
     * converts the result to a string, and sends it back through the Graphene bridge.
     *
     * @param jsExpression A JS expression whose result will be converted to a string
     *                     (e.g. `"document.querySelector('#hp').innerText"`).
     * @param timeoutMs    Maximum time to wait for the result before the future completes
     *                     exceptionally. Default 5 000 ms.
     * @return A [CompletableFuture] that completes with the string result.
     */
    fun queryJs(jsExpression: String, timeoutMs: Long = 5_000): CompletableFuture<String> {
        val future = CompletableFuture<String>()
        val id = queryIdCounter.incrementAndGet()
        pendingQueries[id] = future

        // Schedule a timeout
        scheduler.schedule({
            if (pendingQueries.remove(id) != null) {
                future.completeExceptionally(
                    java.util.concurrent.TimeoutException("queryJs timed out after ${timeoutMs}ms (id=$id)")
                )
            }
        }, timeoutMs, TimeUnit.MILLISECONDS)

        // Build the JS snippet.
        // The expression is embedded directly (not via eval) so multi-line
        // expressions with comments and newlines work correctly.
        // The wrapper converts the result to a string and posts it through
        // the Graphene bridge (globalThis.grapheneBridge), if exposed to this document.
        val js = """
            (function() {
                function reply(payload) {
                    if (globalThis.grapheneBridge) {
                        globalThis.grapheneBridge.ready().then(function() {
                            globalThis.grapheneBridge.emit('$QUERY_CHANNEL', payload);
                        });
                    }
                }
                try {
                    var __res = (${jsExpression});
                    var __val = (__res === null || __res === undefined) ? '' : String(__res);
                    reply({ id: $id, result: __val });
                } catch(e) {
                    reply({ id: $id, error: String(e) });
                }
            })();
        """.trimIndent()

        executeJs(js)
        return future
    }

    /**
     * Evaluates multiple JavaScript expressions in a single call and returns all results.
     *
     * @param expressions Map of user-defined key → JS expression.
     * @return A [CompletableFuture] that completes with a map of key → result string.
     */
    fun queryJsMultiple(
        expressions: Map<String, String>,
        timeoutMs: Long = 5_000
    ): CompletableFuture<Map<String, String>> {
        val future = CompletableFuture<Map<String, String>>()
        val id = queryIdCounter.incrementAndGet()
        pendingQueries[id] = CompletableFuture<String>().also { raw ->
            raw.thenAccept { json ->
                try {
                    // Minimal JSON parsing (the payload is {id:…, results:{key:val,…}})
                    future.complete(parseResultsMap(json))
                } catch (e: Exception) {
                    future.completeExceptionally(e)
                }
            }.exceptionally { ex ->
                future.completeExceptionally(ex)
                null
            }
        }

        // Timeout
        scheduler.schedule({
            if (pendingQueries.remove(id) != null) {
                future.completeExceptionally(
                    java.util.concurrent.TimeoutException("queryJsMultiple timed out after ${timeoutMs}ms")
                )
            }
        }, timeoutMs, TimeUnit.MILLISECONDS)

        // Build a JS object from the expressions map
        val entries = expressions.entries.joinToString(",\n") { (key, expr) ->
            val safeKey = key.replace("\\", "\\\\").replace("\"", "\\\"")
            val safeExpr = expr.replace("\\", "\\\\").replace("'", "\\'")
                .replace("\n", "\\n").replace("\r", "\\r")
            "\"$safeKey\": (function(){ try { var v = eval('$safeExpr'); return (v===null||v===undefined)?'':String(v); } catch(e){ return 'ERROR:'+e; } })()"
        }

        val js = """
            (function() {
                var __results = { $entries };
                if (globalThis.grapheneBridge) {
                    globalThis.grapheneBridge.ready().then(function() {
                        globalThis.grapheneBridge.emit('$QUERY_CHANNEL', { id: $id, results: __results });
                    });
                }
            })();
        """.trimIndent()

        executeJs(js)
        return future
    }

    /**
     * Starts periodically evaluating a JS expression and invoking [callback] with each result.
     *
     * @param jsExpression The JS expression to evaluate each interval.
     * @param intervalMs   Polling interval in milliseconds.
     * @param callback     Invoked on the scheduler thread with each result string.
     * @return A [PollHandle] that can be used to cancel the poll.
     */
    fun poll(
        jsExpression: String,
        intervalMs: Long,
        callback: (String) -> Unit
    ): PollHandle {
        val pollId = pollIdCounter.incrementAndGet()

        val scheduledFuture = scheduler.scheduleAtFixedRate({
            try {
                queryJs(jsExpression, timeoutMs = intervalMs.coerceAtLeast(2_000))
                    .thenAccept { result ->
                        try {
                            callback(result)
                        } catch (e: Exception) {
                            logger.warn("[WebDataReader] Poll callback error (pollId=$pollId): ${e.message}")
                        }
                    }
            } catch (e: Exception) {
                logger.warn("[WebDataReader] Poll query error (pollId=$pollId): ${e.message}")
            }
        }, 0, intervalMs, TimeUnit.MILLISECONDS)

        activePolls[pollId] = scheduledFuture
        logger.info("[WebDataReader] Started poll $pollId every ${intervalMs}ms")

        return PollHandle(pollId)
    }

    /**
     * Starts periodically evaluating multiple JS expressions and invoking [callback] with each set of results.
     */
    fun pollMultiple(
        expressions: Map<String, String>,
        intervalMs: Long,
        callback: (Map<String, String>) -> Unit
    ): PollHandle {
        val pollId = pollIdCounter.incrementAndGet()

        val scheduledFuture = scheduler.scheduleAtFixedRate({
            try {
                queryJsMultiple(expressions, timeoutMs = intervalMs.coerceAtLeast(2_000))
                    .thenAccept { results ->
                        try {
                            callback(results)
                        } catch (e: Exception) {
                            logger.warn("[WebDataReader] PollMultiple callback error (pollId=$pollId): ${e.message}")
                        }
                    }
            } catch (e: Exception) {
                logger.warn("[WebDataReader] PollMultiple query error (pollId=$pollId): ${e.message}")
            }
        }, 0, intervalMs, TimeUnit.MILLISECONDS)

        activePolls[pollId] = scheduledFuture
        logger.info("[WebDataReader] Started multi-poll $pollId every ${intervalMs}ms")

        return PollHandle(pollId)
    }

    // ── Internal ─────────────────────────────────────────────────────────

    /**
     * Handles an incoming message from the bridge. The payload is a JSON string
     * with the shape `{ "id": <number>, "result": "<string>" }` or
     * `{ "id": <number>, "error": "<string>" }` or
     * `{ "id": <number>, "results": { … } }`.
     */
    private fun handleIncomingQuery(payloadJson: String) {
        try {
            val id = extractJsonLong(payloadJson, "id")
            if (id == null) {
                logger.warn("[WebDataReader] Received query without id: $payloadJson")
                return
            }

            val future = pendingQueries.remove(id) ?: return // Likely timed out already

            val error = extractJsonString(payloadJson, "error")
            if (error != null) {
                future.completeExceptionally(RuntimeException("JS error: $error"))
                return
            }

            // Check for multi-result payload
            val resultsStart = payloadJson.indexOf("\"results\"")
            if (resultsStart >= 0) {
                // For multi queries, pass the raw JSON so the caller can parse the results map
                future.complete(payloadJson)
            } else {
                val result = extractJsonString(payloadJson, "result") ?: ""
                future.complete(result)
            }
        } catch (e: Exception) {
            logger.error("[WebDataReader] Error handling query: ${e.message}", e)
        }
    }

    // ── Minimal JSON helpers (avoids adding a JSON library dependency) ───

    private fun extractJsonString(json: String, key: String): String? {
        val keyPattern = "\"$key\""
        val keyIndex = json.indexOf(keyPattern)
        if (keyIndex < 0) return null

        val colonIndex = json.indexOf(':', keyIndex + keyPattern.length)
        if (colonIndex < 0) return null

        val afterColon = json.substring(colonIndex + 1).trimStart()
        if (afterColon.isEmpty() || afterColon[0] != '"') return null

        val sb = StringBuilder()
        var i = 1 // skip opening quote
        while (i < afterColon.length) {
            val c = afterColon[i]
            if (c == '\\' && i + 1 < afterColon.length) {
                sb.append(afterColon[i + 1])
                i += 2
            } else if (c == '"') {
                break
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun extractJsonLong(json: String, key: String): Long? {
        val keyPattern = "\"$key\""
        val keyIndex = json.indexOf(keyPattern)
        if (keyIndex < 0) return null

        val colonIndex = json.indexOf(':', keyIndex + keyPattern.length)
        if (colonIndex < 0) return null

        val afterColon = json.substring(colonIndex + 1).trimStart()
        val numStr = afterColon.takeWhile { it.isDigit() || it == '-' }
        return numStr.toLongOrNull()
    }

    /**
     * Parses the results map from a multi-query JSON payload.
     * Expected shape: `{ "id": …, "results": { "key1": "val1", "key2": "val2" } }`
     */
    private fun parseResultsMap(json: String): Map<String, String> {
        val resultsKey = "\"results\""
        val idx = json.indexOf(resultsKey)
        if (idx < 0) return emptyMap()

        // Find the opening { of the results object
        val braceStart = json.indexOf('{', idx + resultsKey.length)
        if (braceStart < 0) return emptyMap()

        // Find the matching closing }
        var depth = 0
        var braceEnd = braceStart
        for (i in braceStart until json.length) {
            when (json[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        braceEnd = i
                        break
                    }
                }
            }
        }

        val resultsJson = json.substring(braceStart + 1, braceEnd)
        val map = mutableMapOf<String, String>()

        // Simple key-value extraction for flat string objects
        val keyRegex = Regex(""""([^"\\]*(?:\\.[^"\\]*)*)"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        for (match in keyRegex.findAll(resultsJson)) {
            val k = match.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
            val v = match.groupValues[2].replace("\\\"", "\"").replace("\\\\", "\\")
            map[k] = v
        }

        return map
    }

    // ── Poll handle ──────────────────────────────────────────────────────

    /**
     * Handle for a running poll. Call [cancel] to stop it.
     */
    class PollHandle internal constructor(private val pollId: Long) {
        /** Whether this poll has been cancelled. */
        var isCancelled: Boolean = false
            private set

        /** Stops the periodic poll. */
        fun cancel() {
            if (isCancelled) return
            isCancelled = true
            activePolls.remove(pollId)?.cancel(false)
            logger.info("[WebDataReader] Cancelled poll $pollId")
        }
    }
}
