package com.livetranslate

import com.livetranslate.net.Http
import com.livetranslate.net.PrivacyBlockedException
import com.livetranslate.net.PrivacyGuard
import com.livetranslate.privacy.PrivacyEntry
import com.livetranslate.privacy.PrivacyRules
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSink
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class HttpFailureTest {
    private val request = Request.Builder().url("https://example.invalid/translation").build()

    private fun response(request: Request, code: Int, body: ResponseBody) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(body).build()

    private fun failingBody(error: Exception, failOnClose: Boolean = false): ResponseBody = object : ResponseBody() {
        private val stream = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (!failOnClose) throw error
                return -1
            }
            override fun timeout() = Timeout.NONE
            override fun close() {
                if (failOnClose) throw error
            }
        }.buffer()
        override fun contentType(): MediaType? = null
        override fun contentLength() = -1L
        override fun source() = stream
    }

    private fun failure(client: OkHttpClient, input: Request = request): Throwable? = try {
        runBlocking {
            withTimeout(5000) { runCatching { Http.lines(input, client).toList() }.exceptionOrNull() }
        }
    } finally {
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()
    }

    private fun assertFailure(expected: Exception, actual: Throwable?) {
        assertEquals(expected.javaClass, actual?.javaClass)
        assertEquals(expected.message, actual?.message)
    }

    @Test fun streamingRuntimeFailureReachesCollector() {
        val error = IllegalStateException("synthetic stream failure")
        val client = OkHttpClient.Builder().addInterceptor { response(it.request(), 200, failingBody(error)) }.build()
        assertFailure(error, failure(client))
    }

    @Test fun errorResponseReadFailureReachesCollector() {
        val error = IOException("synthetic error body failure")
        val client = OkHttpClient.Builder().addInterceptor { response(it.request(), 500, failingBody(error)) }.build()
        assertFailure(error, failure(client))
    }

    @Test fun responseCloseFailureReachesCollector() {
        val error = IllegalStateException("synthetic close failure")
        val client = OkHttpClient.Builder().addInterceptor { response(it.request(), 200, failingBody(error, true)) }.build()
        assertFailure(error, failure(client))
    }

    @Test fun privacySettingsFailureBlocksTransportAndBecomesIOException() {
        var reachedTransport = false
        val client = OkHttpClient.Builder()
            .addInterceptor(PrivacyGuard { throw IllegalStateException("synthetic settings failure") })
            .addInterceptor { reachedTransport = true; response(it.request(), 200, "".toResponseBody()) }.build()
        assertTrue(failure(client) is IOException)
        assertFalse(reachedTransport)
    }

    @Test fun privacyBodyInspectionFailureBlocksTransportAndBecomesIOException() {
        var reachedTransport = false
        val body = object : RequestBody() {
            override fun contentType(): MediaType? = null
            override fun writeTo(sink: BufferedSink) { throw IllegalArgumentException("synthetic body failure") }
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(PrivacyGuard { PrivacyRules() })
            .addInterceptor { reachedTransport = true; response(it.request(), 200, "".toResponseBody()) }.build()
        assertTrue(failure(client, request.newBuilder().post(body).build()) is IOException)
        assertFalse(reachedTransport)
    }

    @Test fun privacyEntryIsBlockedBeforeTransport() {
        var reachedTransport = false
        val client = OkHttpClient.Builder()
            .addInterceptor(PrivacyGuard { PrivacyRules(entries = listOf(PrivacyEntry("Example Secret"))) })
            .addInterceptor { reachedTransport = true; response(it.request(), 200, "".toResponseBody()) }.build()
        val input = request.newBuilder().url("https://example.invalid/?text=Example%20Secret").build()
        assertEquals(PrivacyBlockedException::class.java, failure(client, input)?.javaClass)
        assertFalse(reachedTransport)
    }
}
