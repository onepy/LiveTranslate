package com.livetranslate.overlay

import android.os.Build
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.collectAsState
import com.livetranslate.translate.toUserMessage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.livetranslate.data.DisplayMode

@Composable
fun PanelTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TranslationPanel(
    model: PanelModel,
    actions: PanelActions,
    onDismiss: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val st = model.state
    var showWords by remember(st.source) { mutableStateOf(false) }
    val compact = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
    var selectedWord by remember(st.source, st.result) { mutableStateOf<String?>(null) }
    var meaning by remember(st.source, st.result) { mutableStateOf("") }
    var meaningError by remember(st.source, st.result) { mutableStateOf<String?>(null) }
    var meaningLoading by remember { mutableStateOf(false) }
    var meaningRetry by remember { mutableStateOf(0) }
    val analysisSettings by com.livetranslate.App.settings.flow.collectAsState()
    androidx.compose.runtime.LaunchedEffect(showWords, selectedWord, st.source, st.result, meaningRetry,
        analysisSettings.providerId, analysisSettings.customFormat, analysisSettings.customBaseUrl,
        analysisSettings.customModel, analysisSettings.customApiKey, analysisSettings.biliModel, model.to) {
        meaningLoading = false
        meaning = ""
        meaningError = null
        val word = selectedWord
        if (showWords && word != null) {
            meaningLoading = true
            try {
                meaning = com.livetranslate.translate.WordMeanings.load(word, model.to, analysisSettings)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { meaningError = e.toUserMessage()
            } finally { meaningLoading = false }
        }
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = model.opacity),
        shadowElevation = if (model.opacity >= 0.9f) 8.dp else 0.dp,
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                if (model.mode == DisplayMode.FLOATING) {
                    DragHandle(onDrag, onDragEnd)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = "${st.providerName} · 切换",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { actions.cycleProvider() }
                        .padding(vertical = 6.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "${model.from.short} ⇄ ${model.to.short}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp))
                        .clickable { actions.swapLanguages() }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
                if (model.contextCount > 0) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "上文${model.contextCount}条 · 清空",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .clickable { actions.clearContext() }
                            .padding(vertical = 6.dp),
                    )
                }
                if (st.loading) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭", modifier = Modifier.size(18.dp))
                }
            }

            Box(
                Modifier
                    .padding(end = 8.dp)
                    .heightIn(max = 180.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (showWords && st.result.isNotBlank()) {
                    Column {
                        FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(vertical = 4.dp),
                    ) {
                        Words.split(st.result, model.to.locale).forEach { word ->
                            Text(
                                text = word,
                                fontSize = model.fontSp.sp,
                                color = androidx.compose.ui.graphics.Color.White,
                                modifier = Modifier
                                    .background(androidx.compose.ui.graphics.Color(0xFF153E68), RoundedCornerShape(8.dp))
                                    .clickable {
                                        actions.speak(word)
                                        if (selectedWord == word && meaningError != null) meaningRetry++
                                        selectedWord = word
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                        }
                        if (selectedWord != null) {
                            Surface(color = androidx.compose.ui.graphics.Color(0xFF153E68),
                                contentColor = androidx.compose.ui.graphics.Color.White,
                                shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(10.dp)) {
                                    Text(selectedWord.orEmpty(), color = androidx.compose.ui.graphics.Color.White)
                                    if (meaningLoading) Text("查询中…", color = androidx.compose.ui.graphics.Color.White)
                                    if (meaning.isNotEmpty()) Text(meaning, color = androidx.compose.ui.graphics.Color.White)
                                    meaningError?.let { msg ->
                                        Text("$msg · 点此重试", color = androidx.compose.ui.graphics.Color.White,
                                            modifier = Modifier.clickable { meaningRetry++ })
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        text = st.result.ifBlank { if (st.loading) "翻译中…" else "" },
                        fontSize = model.fontSp.sp,
                        lineHeight = (model.fontSp * 1.35f).sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.alpha(if (st.loading && st.result.isBlank()) 0.5f else 1f),
                    )
                }
            }

            st.error?.let { err ->
                Text(
                    text = "$err（点此重试）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .padding(end = 8.dp, top = 2.dp)
                        .clickable { actions.retry() },
                )
            }

            val hasResult = st.result.isNotBlank()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { actions.speak(st.result) }, enabled = hasResult, contentPadding = compact) { Text("朗读") }
                TextButton(onClick = { selectedWord = null; showWords = !showWords }, enabled = hasResult, contentPadding = compact) {
                    Text(if (showWords) "整句" else "单词")
                }
                TextButton(onClick = { actions.copy() }, enabled = hasResult, contentPadding = compact) { Text("复制") }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { actions.replace() },
                    enabled = hasResult && !st.loading,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                    modifier = Modifier.padding(end = 4.dp),
                ) { Text("替换") }
            }
        }
    }
}

/** Uses raw screen coordinates so moving the window under the finger does not cause jitter. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DragHandle(onDrag: (Float, Float) -> Unit, onDragEnd: () -> Unit) {
    val last = remember { floatArrayOf(0f, 0f) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = 28.dp, height = 28.dp)
            .pointerInteropFilter { ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        last[0] = ev.rawX; last[1] = ev.rawY
                    }
                    MotionEvent.ACTION_MOVE -> {
                        onDrag(ev.rawX - last[0], ev.rawY - last[1])
                        last[0] = ev.rawX; last[1] = ev.rawY
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onDragEnd()
                }
                true
            },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(3) {
                Box(
                    Modifier
                        .size(width = 16.dp, height = 2.dp)
                        .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(1.dp))
                )
            }
        }
    }
}
