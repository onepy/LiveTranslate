package com.livetranslate

import com.livetranslate.net.PrivacyGuard
import com.livetranslate.privacy.MaskingTranslator
import com.livetranslate.privacy.PrivacyEntry
import com.livetranslate.privacy.PrivacyMask
import com.livetranslate.privacy.PrivacyRules
import com.livetranslate.translate.Lang
import com.livetranslate.translate.TranslateException
import com.livetranslate.translate.Translator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** All names, numbers and addresses below are fictional. */
class PrivacyMaskTest {
    private val rules = PrivacyRules(entries = listOf(PrivacyEntry("张三", "Zhang San"), PrivacyEntry("张三丰"), PrivacyEntry("Ann Lee")))
    private val codeRe = Regex("[A-Z]{3}[0-9]")

    private fun texts(text: String, caret: Int = -1) = rules.spans(text, caret).map { text.substring(it.first, it.last + 1) }

    @Test fun foldKeepsLengthAndMatchesFullWidthAndCase() {
        val s = "联系ＡＮＮ　ＬＥＥ吧"
        assertEquals(s.length, PrivacyRules.fold(s).length)
        assertEquals(listOf("ＡＮＮ　ＬＥＥ"), texts(s))
        assertEquals(listOf("ann  lee"), texts("hi ann  lee!"))
    }

    @Test fun latinEntriesNeedWordBoundariesAndOverlapsMerge() {
        assertTrue(texts("Annual Lee meeting").isEmpty())
        assertEquals(listOf("张三丰"), texts("张三丰来了"))
        assertEquals(listOf("Zhang San"), texts("call Zhang San now"))
    }

    @Test fun builtInRules() {
        assertEquals(listOf("a.b@example.com"), texts("mail a.b@example.com ok"))
        assertEquals(listOf("11010519491231002X"), texts("证件11010519491231002X"))
        assertEquals(listOf("138 1234 5678"), texts("电话 138 1234 5678 谢谢"))
        assertTrue(texts("买了 12345 个").isEmpty())
    }

    @Test fun caretMasksUnfinishedEntriesAndNumbers() {
        assertEquals(listOf("张"), texts("明天和张", 4))
        assertEquals(listOf("Zha"), texts("call Zha", 8))
        assertEquals(listOf("138 12"), texts("号码138 12", 8))
        assertTrue(texts("明天见", 3).isEmpty())
        assertTrue(texts("xzha", 4).isEmpty())
    }

    @Test fun maskRestoreAndVerify() {
        val text = "明天和张三去北京，电话13812345678"
        val mask = PrivacyMask.create(rules, text, -1, listOf("称呼张三要礼貌"), Random(1))
        assertFalse(mask.text.contains("张三") || mask.text.contains("1381"))
        assertFalse(mask.extras[0].contains("张三"))
        val codes = codeRe.findAll(mask.text).map { it.value }.toList()
        assertEquals(2, codes.size)
        assertTrue(codes[0].indices.count { codes[0][it] != codes[1][it] } >= 3)
        val raw = "Go to Beijing with ${codes[0]} tomorrow, phone ${codes[1]}"
        assertEquals("Go to Beijing with Zhang San tomorrow, phone 13812345678", mask.restore(raw, final = true))
        mask.verify(raw)
        assertEquals("Go with ", mask.restore("Go with ${codes[0].take(2)}", final = false))
        assertThrows { mask.verify("Go to Beijing with ${codes[0]} tomorrow") }
        assertThrows { mask.verify(raw + " XQZ7".takeIf { "XQZ7" !in codes }.orEmpty() + " BCD9") }
    }

    @Test fun cjkValuesDropModelSpacesAndNaturalCodesAreAllowed() {
        val plain = PrivacyRules(entries = listOf(PrivacyEntry("李四")), emails = false, idCards = false, longDigits = false)
        val mask = PrivacyMask.create(plain, "I and 李四 like BTS4", -1, emptyList(), Random(2))
        val code = codeRe.findAll(mask.text).map { it.value }.first { it != "BTS4" }
        assertEquals("我和李四都喜欢BTS4", mask.restore("我和 $code 都喜欢BTS4", final = true))
        mask.verify("我和 $code 都喜欢BTS4")
    }

    @Test fun maskingTranslatorOnlySendsMaskedTextAndRestoresStream() = runTest {
        val sent = mutableListOf<String>()
        val echo = object : Translator {
            override fun translate(text: String, from: Lang, to: Lang): Flow<String> = flow {
                sent += text
                for (n in 1..text.length) emit(text.substring(0, n)) // splits every code across chunks
            }
        }
        var style = ""
        val t = MaskingTranslator(rules, "叫张三丰师父", listOf("张三说过"), random = Random(3)) { st, ctx ->
            style = st + ctx.joinToString(); echo
        }
        val text = "Ann Lee 和张三见面"
        val out = t.translate(text, Lang.AUTO, Lang.EN).toList()
        assertFalse(sent.single().contains("张三") || sent.single().contains("Ann"))
        assertFalse(style.contains("张三"))
        assertEquals("Ann Lee 和Zhang San见面", out.last())
        assertTrue(out.none { s -> codeRe.containsMatchIn(s) })
    }

    @Test fun guardDecodesJsonFormAndHtml() {
        val json = """{"messages":[{"content":"\u5f20\u4e09 &amp; co"}],"u":"a\/b"}"""
        val req = Request.Builder().url("https://x.test/t?q=hi").post(json.toRequestBody("application/json".toMediaType())).build()
        assertTrue(PrivacyGuard.texts(req).any(rules::containsEntry))
        val form = Request.Builder().url("https://x.test/t").post(FormBody.Builder().add("q", "ann lee").build()).build()
        assertTrue(PrivacyGuard.texts(form).any(rules::containsEntry))
        val clean = Request.Builder().url("https://x.test/t?q=ZQX7").build()
        assertFalse(PrivacyGuard.texts(clean).any(rules::containsEntry))
    }

    private fun assertThrows(block: () -> Unit) {
        try { block() } catch (e: TranslateException) { return }
        throw AssertionError("expected TranslateException")
    }
}
