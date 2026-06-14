package me.nettrash.geo.util

import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * OkHttp interceptor that adds two pieces of politeness when talking to
 * the public third-party APIs (Overpass, Open-Elevation, Open-Meteo):
 *
 *   1. **Retry-once with backoff** — when the server answers `429` or a
 *      `5xx`, the (unread) response body is closed and the request is
 *      retried exactly once after a wait. If the server sent a
 *      `Retry-After` header (in seconds) that delay is honoured;
 *      otherwise a short jittered backoff (~0.5–2 s) is used. Whatever
 *      the retry yields is returned — including another error, so the
 *      caller's existing graceful-failure fallback still kicks in.
 *   2. **Minimum inter-request interval** — tracks the timestamp of the
 *      last request and sleeps the difference if a new one arrives
 *      sooner than [minIntervalMs], so we never burst the public
 *      endpoints. The interval is per-interceptor-instance (i.e. per
 *      client / per API).
 *
 * Both waits use [Thread.sleep], which is safe here: OkHttp runs
 * interceptors on its own background dispatcher threads, never the main
 * thread, and the callers already invoke the network off
 * `Dispatchers.IO`. Behaviour on a successful first response is
 * unchanged apart from the inter-request spacing.
 */
class RetryInterceptor(
    private val minIntervalMs: Long
) : Interceptor {

    /** Wall-clock time (ms) the previous request was let through. */
    private val lastRequestAtMs = AtomicLong(0L)

    override fun intercept(chain: Interceptor.Chain): Response {
        throttle()

        val request = chain.request()
        val response = chain.proceed(request)
        if (!shouldRetry(response.code)) return response

        // Compute the wait before discarding the response so a
        // `Retry-After` header can be honoured, then close the unread
        // body to free the connection ahead of the retry.
        val waitMs = retryAfterMs(response) ?: jitteredBackoffMs()
        response.close()

        sleepQuietly(waitMs)
        AppLog.app.warn("HTTP ${response.code} — retrying once after ${waitMs} ms")

        throttle()
        return chain.proceed(request)
    }

    private fun shouldRetry(code: Int): Boolean = code == 429 || code in 500..599

    /** Sleep just long enough to keep at least [minIntervalMs] between
     *  consecutive requests issued through this interceptor. */
    private fun throttle() {
        if (minIntervalMs <= 0L) return
        val now = System.currentTimeMillis()
        // Atomically reserve this request's slot so concurrent OkHttp calls are
        // actually spaced out — a plain get/sleep/set lets two callers read the
        // same timestamp and both skip the wait. `lastRequestAtMs` holds the
        // next-allowed time, advanced monotonically by minIntervalMs.
        val slot = lastRequestAtMs.updateAndGet { prev ->
            if (prev == 0L) now else maxOf(now, prev + minIntervalMs)
        }
        sleepQuietly(slot - now)
    }

    /** Parse a `Retry-After` header given as an integer number of
     *  seconds. Returns `null` when absent or not a plain integer
     *  (the HTTP-date form is uncommon for these APIs and ignored in
     *  favour of the jittered backoff). */
    private fun retryAfterMs(response: Response): Long? {
        val header = response.header("Retry-After")?.trim() ?: return null
        val seconds = header.toLongOrNull() ?: return null
        if (seconds < 0L) return null
        return seconds * 1000L
    }

    /** Short jittered backoff in the ~0.5–2 s range. */
    private fun jitteredBackoffMs(): Long = Random.nextLong(500L, 2001L)

    private fun sleepQuietly(ms: Long) {
        if (ms <= 0L) return
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
