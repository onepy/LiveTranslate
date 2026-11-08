package com.livetranslate.a11y

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

data class FocusedInput(
    val pkg: String,
    val text: String,
    val bounds: Rect,
    val node: AccessibilityNodeInfo,
)

object InputNodes {
    /** Current text of an editable node; empty when the field only shows its hint. */
    fun read(node: AccessibilityNodeInfo): String =
        if (node.isShowingHintText) "" else node.text?.toString().orEmpty()

    fun bounds(node: AccessibilityNodeInfo): Rect = Rect().also { node.getBoundsInScreen(it) }

    /** "Valid text" for long-press translation: at least one letter or digit, not just spaces/punctuation. */
    fun hasMeaningfulText(text: String): Boolean = text.any { it.isLetterOrDigit() }

    fun isUsable(node: AccessibilityNodeInfo?): Boolean =
        node != null && node.isEditable && !node.isPassword

    /** Replaces only the approved range. A changed document or unsupported selection fails closed. */
    fun replace(context: Context, node: AccessibilityNodeInfo, range: TranslationRange, text: String): Boolean {
        if (!node.refresh() || !node.isFocused || !isUsable(node)) return false
        val updated = range.replace(read(node), text) ?: return false
        val setArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
        }
        var ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)
        if (!ok) {
            if (!node.refresh() || read(node) != range.snapshot) return false
            if (!select(node, range.start, range.end)) return false
            if (!node.refresh() || read(node) != range.snapshot ||
                node.textSelectionStart != range.start || node.textSelectionEnd != range.end) return false
            copy(context, text)
            ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }
        if (ok) select(node, range.start + text.length, range.start + text.length)
        return ok
    }

    fun copy(context: Context, text: String) {
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("translation", text))
    }

    private fun select(node: AccessibilityNodeInfo, start: Int, end: Int): Boolean =
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_SELECTION,
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
            }
        )
}
