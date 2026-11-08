package com.livetranslate.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppEntry(val pkg: String, val label: String, val icon: ImageBitmap?)

object AppList {
    /** Chat-ish apps shown first so the common choice is one tap away. */
    private val popular = listOf(
        "com.tencent.mm", "com.tencent.mobileqq", "com.tencent.tim", "com.tencent.wework",
        "com.alibaba.android.rimet", "com.ss.android.lark", "org.telegram.messenger", "com.whatsapp",
        "jp.naver.line.android", "com.discord", "com.google.android.apps.messaging", "com.android.mms",
        "com.sina.weibo", "com.xingin.xhs", "tv.danmaku.bili", "com.ss.android.ugc.aweme",
    )

    suspend fun load(context: Context, selectedFirst: Set<String>): List<AppEntry> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0)
            .distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName != context.packageName }
            .map { ri ->
                AppEntry(
                    pkg = ri.activityInfo.packageName,
                    label = ri.loadLabel(pm).toString(),
                    icon = runCatching { ri.loadIcon(pm).toBitmap(72, 72).asImageBitmap() }.getOrNull(),
                )
            }
            .sortedWith(
                compareBy<AppEntry>(
                    { it.pkg !in selectedFirst },
                    { popular.indexOf(it.pkg).let { i -> if (i < 0) Int.MAX_VALUE else i } },
                    { it.label.lowercase() },
                )
            )
    }

    fun label(context: Context, pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerDialog(initial: Set<String>, onDone: (Set<String>) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var selected by remember { mutableStateOf(initial) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { apps = AppList.load(context, initial) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                TopAppBar(
                    title = { Text("选择应用（已选 ${selected.size}）") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "取消") }
                    },
                    actions = { TextButton(onClick = { onDone(selected) }) { Text("完成") } },
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索应用") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                val list = apps
                if (list == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    val q = query.trim()
                    val shown = if (q.isEmpty()) list else list.filter {
                        it.label.contains(q, ignoreCase = true) || it.pkg.contains(q, ignoreCase = true)
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(shown, key = { it.pkg }) { app ->
                            val checked = app.pkg in selected
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selected = if (checked) selected - app.pkg else selected + app.pkg }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                            ) {
                                if (app.icon != null) {
                                    Image(app.icon, contentDescription = null, modifier = Modifier.size(40.dp))
                                } else {
                                    Spacer(Modifier.size(40.dp))
                                }
                                Spacer(Modifier.width(14.dp))
                                Text(
                                    app.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Checkbox(checked = checked, onCheckedChange = null)
                            }
                        }
                    }
                }
            }
        }
    }
}
