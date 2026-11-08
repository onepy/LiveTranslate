package com.livetranslate

import com.livetranslate.translate.Lang
import com.livetranslate.translate.PipelineConfig
import com.livetranslate.translate.TranslateException
import com.livetranslate.translate.TranslationPipeline
import com.livetranslate.translate.Translator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationPipelineTest {

    /** Streams "<text>" as three chunks, 500ms apart (longer than the debounce). */
    private class FakeTranslator : Translator {
        val calls = mutableListOf<String>()
        var completed = mutableListOf<String>()
        var fail = false

        override fun translate(text: String, from: Lang, to: Lang): Flow<String> = flow {
            calls += text
            if (fail) throw TranslateException("boom")
            emit("[$text")
            delay(500)
            emit("[$text.")
            delay(500)
            emit("[$text]")
            completed += text
        }
    }

    private val config = MutableStateFlow(PipelineConfig("p1", "Fake", Lang.AUTO, Lang.EN, debounceMs = 300))

    /** advanceUntilIdle() ignores backgroundScope work, so advance virtual time explicitly. */
    private fun TestScope.settle() {
        advanceTimeBy(5_000)
        runCurrent()
    }

    @Test
    fun contextChangesInvalidateCacheAndReachTranslator() = runTest {
        val fake = FakeTranslator()
        val received = mutableListOf<List<String>>()
        val pipeline = TranslationPipeline(backgroundScope, config, { cfg -> received += cfg.context; fake })
        runCurrent()
        pipeline.submit("hi", context = listOf("one"))
        settle()
        pipeline.submit("hi", context = listOf("two"))
        settle()
        pipeline.submit("hi", context = listOf("one"))
        settle()
        assertEquals(listOf(listOf("one"), listOf("two")), received)
        assertEquals(2, fake.calls.size)
    }

    @Test
    fun submitImmediatelyInvalidatesOldResult() = runTest {
        val fake = FakeTranslator()
        val pipeline = TranslationPipeline(backgroundScope, config, { fake })
        runCurrent()
        pipeline.submit("old")
        settle()
        pipeline.submit("new")
        assertEquals("", pipeline.state.value.result)
        assertEquals("new", pipeline.state.value.source)
        pipeline.submit("")
        assertEquals("", pipeline.state.value.result)
        assertEquals("", pipeline.state.value.source)
        settle()
        assertEquals("", pipeline.state.value.result)
    }

    @Test
    fun rapidTyping_onlyLastTextIsTranslated() = runTest {
        val fake = FakeTranslator()
        val pipeline = TranslationPipeline(backgroundScope, config, { fake })
        runCurrent()
        pipeline.submit("今")
        advanceTimeBy(100)
        pipeline.submit("今天")
        advanceTimeBy(100)
        pipeline.submit("今天好")
        settle()
        assertEquals(listOf("今天好"), fake.calls)
        assertEquals("[今天好]", pipeline.state.value.result)
        assertFalse(pipeline.state.value.loading)
    }

    @Test
    fun newInput_cancelsInFlightStream() = runTest {
        val fake = FakeTranslator()
        val pipeline = TranslationPipeline(backgroundScope, config, { fake })
        runCurrent()
        pipeline.submit("a")
        advanceTimeBy(350) // debounce passed, first chunk emitted, stream in progress
        assertEquals("[a", pipeline.state.value.result)
        pipeline.submit("ab")
        settle()
        assertEquals(listOf("a", "ab"), fake.calls)
        assertEquals(listOf("ab"), fake.completed)
        assertEquals("[ab]", pipeline.state.value.result)
    }

    @Test
    fun deletingBack_hitsCache() = runTest {
        val fake = FakeTranslator()
        val pipeline = TranslationPipeline(backgroundScope, config, { fake })
        runCurrent()
        pipeline.submit("hi")
        settle()
        pipeline.submit("hi there")
        settle()
        pipeline.submit("hi")
        settle()
        assertEquals(listOf("hi", "hi there"), fake.calls)
        assertEquals("[hi]", pipeline.state.value.result)
    }

    @Test
    fun blankInput_clearsImmediately() = runTest {
        val fake = FakeTranslator()
        val pipeline = TranslationPipeline(backgroundScope, config, { fake })
        runCurrent()
        pipeline.submit("hi")
        settle()
        pipeline.submit("   ")
        runCurrent()
        assertEquals("", pipeline.state.value.source)
        assertEquals("", pipeline.state.value.result)
    }

    @Test
    fun providerChange_retranslatesSameText() = runTest {
        val fake = FakeTranslator()
        val pipeline = TranslationPipeline(backgroundScope, config, { fake })
        runCurrent()
        pipeline.submit("hi")
        settle()
        config.value = config.value.copy(providerKey = "p2", providerName = "Other")
        settle()
        assertEquals(listOf("hi", "hi"), fake.calls)
        assertEquals("Other", pipeline.state.value.providerName)
    }

    @Test
    fun error_isReported_andRetryWorks() = runTest {
        val fake = FakeTranslator().apply { fail = true }
        val pipeline = TranslationPipeline(backgroundScope, config, { fake })
        runCurrent()
        pipeline.submit("hi")
        settle()
        assertEquals("boom", pipeline.state.value.error)
        fake.fail = false
        pipeline.retry()
        settle()
        assertEquals(null, pipeline.state.value.error)
        assertEquals("[hi]", pipeline.state.value.result)
    }
}
