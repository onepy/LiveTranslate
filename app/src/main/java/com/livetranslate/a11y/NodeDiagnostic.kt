package com.livetranslate.a11y

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.ArrayDeque
import android.view.accessibility.AccessibilityNodeInfo

/** Shell-only probe. Optional consented text returns only in the broadcast result, never in logs. */
class NodeDiagnostic(private val service: AccessibilityService) : BroadcastReceiver() {
    fun register() {
        ContextCompat.registerReceiver(service, this,
            IntentFilter("com.livetranslate.DIAGNOSE_NODES"),
            "android.permission.DUMP", null, ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.livetranslate.DIAGNOSE_NODES") return
        val started = SystemClock.uptimeMillis()
        val targetPackage = intent.getStringExtra("target_package") ?: "com.tencent.mm"
        val includeText = intent.getBooleanExtra("include_text", false) && isOrderedBroadcast &&
            targetPackage in setOf("com.tencent.mm", "com.tencent.mobileqq", "com.tencent.tim")
        val report = StringBuilder()
        Log.i(TAG, "begin uid=${android.os.Process.myUid()}")
        try {
            val windows = service.windows
            Log.i(TAG, "windows=${windows.size}")
            for (window in windows.take(12)) {
                val root = window.root
                if (root == null) {
                    Log.i(TAG, "window=${window.id} type=${window.type} active=${window.isActive} focused=${window.isFocused} root=null")
                    continue
                }
                val pkg = root.packageName?.toString().orEmpty()
                // Only inspect the chat apps under test; do not traverse other apps or the keyboard.
                if (pkg !in setOf("com.tencent.mm", "com.tencent.mobileqq", "com.tencent.tim")) {
                    Log.i(TAG, "window=${window.id} package=$pkg skipped=true")
                    continue
                }
                if (includeText && (!window.isActive || pkg != targetPackage)) continue
                var nodes = 0
                var texts = 0
                var descriptions = 0
                var editable = 0
                var passwords = 0
                var incomplete = false
                val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
                queue.add(root to 0)
                try {
                    while (queue.isNotEmpty()) {
                        if (nodes >= 1000 || SystemClock.uptimeMillis() - started > 2000) {
                            incomplete = true
                            break
                        }
                        val (node, depth) = queue.removeFirst()
                        try {
                            nodes++
                            if (node.isPassword) passwords++ else {
                                if (!node.text.isNullOrEmpty()) texts++
                                if (!node.contentDescription.isNullOrEmpty()) descriptions++
                                if (includeText && node.isVisibleToUser && report.length < 10000) {
                                    val text = node.text?.toString().orEmpty().take(400)
                                    val desc = node.contentDescription?.toString().orEmpty().take(400)
                                    if (text.isNotBlank() || desc.isNotBlank()) {
                                        val rect = android.graphics.Rect().also { node.getBoundsInScreen(it) }
                                        report.append("node=$nodes editable=${node.isEditable} bounds=$rect text=")
                                            .append(text.replace('\n', ' ')).append(" description=")
                                            .append(desc.replace('\n', ' ')).append('\n')
                                    }
                                }
                            }
                            if (node.isEditable) editable++
                            if (depth >= 40) {
                                if (node.childCount > 0) incomplete = true
                            } else {
                                for (i in 0 until minOf(node.childCount, 1000 - nodes - queue.size)) {
                                    node.getChild(i)?.let { queue.add(it to depth + 1) }
                                        ?: run { incomplete = true }
                                }
                                if (node.childCount > 1000 - nodes) incomplete = true
                            }
                        } finally {
                            @Suppress("DEPRECATION")
                            node.recycle()
                        }
                    }
                } finally {
                    while (queue.isNotEmpty()) {
                        @Suppress("DEPRECATION")
                        queue.removeFirst().first.recycle()
                    }
                }
                Log.i(TAG, "window=${window.id} package=$pkg active=${window.isActive} focused=${window.isFocused} nodes=$nodes textNodes=$texts descriptionNodes=$descriptions editable=$editable passwords=$passwords incomplete=$incomplete")
            }
        } catch (e: Exception) {
            Log.i(TAG, "failed=${e.javaClass.simpleName}")
        }
        if (includeText) resultData = report.toString().ifEmpty { "No visible text in selected active chat app" }
        Log.i(TAG, "end elapsedMs=${SystemClock.uptimeMillis() - started}")
    }

    companion object { const val TAG = "LTNodeDiagnostic" }
}
