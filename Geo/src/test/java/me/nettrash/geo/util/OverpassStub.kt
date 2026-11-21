package me.nettrash.geo.util

import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A stand-in for the public Overpass mirrors, so a peak search can be driven
 * with no network at all: an OkHttp interceptor that never calls `proceed` and
 * answers each request from [answer], by host — the JSON body to return with
 * HTTP 200, or `null` for "unreachable" (an [IOException], which is what a
 * phone with no signal sees). [answer] may block, to hold a search in flight.
 *
 * Every stubbed peak carries an `ele` tag, so no answer ever sends the search
 * on to the Open-Meteo DEM lookup.
 */
internal class OverpassStub(
    @Volatile var answer: (host: String) -> String?
) : Interceptor {

    /** Every host asked, in order. */
    val hosts: MutableList<String> = CopyOnWriteArrayList()

    /** A client whose only "network" is this stub. Deliberately without the
     *  production `RetryInterceptor`, so there is no 1 s spacing either. */
    fun client(): OkHttpClient = OkHttpClient.Builder().addInterceptor(this).build()

    override fun intercept(chain: Interceptor.Chain): Response {
        val host = chain.request().url.host
        hosts += host
        val body = answer(host) ?: throw IOException("$host unreachable (stub)")
        return Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }

    /** One `natural=peak` node, as Overpass returns it. */
    data class Peak(val name: String, val lat: Double, val lon: Double, val ele: Double)

    companion object {
        /** An Overpass JSON answer: these nodes, plus an optional `remark` —
         *  where Overpass reports a runtime error while still answering 200. */
        fun body(peaks: List<Peak>, remark: String? = null): String {
            val elements = peaks.mapIndexed { i, p ->
                """{"type":"node","id":${i + 1},"lat":${p.lat},"lon":${p.lon},""" +
                    """"tags":{"natural":"peak","name":${JsonPrimitive(p.name)},"ele":"${p.ele}"}}"""
            }.joinToString(",")
            val remarkField = remark?.let { ""","remark":${JsonPrimitive(it)}""" } ?: ""
            return """{"version":0.6,"generator":"Overpass API (stub)","elements":[$elements]$remarkField}"""
        }
    }
}
