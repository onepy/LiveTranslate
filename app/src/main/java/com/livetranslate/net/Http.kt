package com.livetranslate.net

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.toList
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

class HttpException(val code: Int, val body: String) : IOException("HTTP $code")

object Http {
    val JSON = "application/json; charset=utf-8".toMediaType()

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(DoubaoCookieJar)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** Streams response body lines. Cancelling the collector cancels the underlying HTTP call. */
    fun lines(request: Request): Flow<String> = callbackFlow {
        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                close(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        close(HttpException(resp.code, resp.body?.string().orEmpty().take(500)))
                        return
                    }
                    val source = resp.body?.source()
                    if (source == null) {
                        close()
                        return
                    }
                    try {
                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            if (trySendBlocking(line).isFailure) break
                        }
                        close()
                    } catch (e: IOException) {
                        close(e)
                    }
                }
            }
        })
        awaitClose { call.cancel() }
    }

    suspend fun text(request: Request): String = lines(request).toList().joinToString("\n")
}
