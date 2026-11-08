package com.livetranslate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.livetranslate.App
import com.livetranslate.a11y.InputWatcherService
import com.livetranslate.data.AppSettings
import com.livetranslate.data.DisplayMode
import com.livetranslate.data.Styles
import com.livetranslate.keepalive.KeepAlive
import com.livetranslate.overlay.PanelActions
import com.livetranslate.overlay.PanelModel
import com.livetranslate.overlay.TranslationPanel
import com.livetranslate.shizuku.ShizukuHelper
import com.livetranslate.translate.Providers
import com.livetranslate.translate.TranslationPipeline
import com.livetranslate.translate.TranslationUi
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(host: SettingsHost, pipeline: TranslationPipeline, refreshTick: Int, onOpenSettings: () -> Unit) {
    val s by App.settings.flow.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("边写边译", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "设置") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { StatusHero(s, host, refreshTick, onOpenSettings) }
            item { PreviewCard(s, pipeline, host, onOpenSettings) }
            item { StyleCard(s) }
        }
    }
}

/** One card, one message, at most one big button: tells the user exactly what (if anything) to do next. */
@Composable
private fun StatusHero(s: AppSettings, host: SettingsHost, refreshTick: Int, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val running by InputWatcherService.isRunning.collectAsState()
    val shizuku = remember(refreshTick) { ShizukuHelper.isRunning() }
    val keep = remember(refreshTick, s.keepAliveNotification) { KeepAlive.status(context, s) }
    var showHelp by remember { mutableStateOf(false) }

    val needsSetup = s.enabled && !running
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when {
        !s.enabled -> scheme.surfaceVariant to scheme.onSurfaceVariant
        needsSetup -> scheme.errorContainer to scheme.onErrorContainer
        else -> scheme.primaryContainer to scheme.onPrimaryContainer
    }
    val (title, subtitle) = when {
        !s.enabled -> "已暂停" to "打开右边的开关继续"
        needsSetup -> "还差一步" to "需要开启无障碍服务"
        else -> "正在工作" to "去任意 App 的输入框打字就行"
    }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when {
                        !s.enabled -> Icons.Filled.Info
                        needsSetup -> Icons.Filled.Warning
                        else -> Icons.Filled.CheckCircle
                    },
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium)
                }
                Switch(checked = s.enabled, onCheckedChange = { v -> App.settings.update { it.copy(enabled = v) } })
            }

            if (needsSetup) {
                Button(onClick = host::oneTapSetup, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(if (shizuku) "用 Shizuku 一键开启" else "去开启", style = MaterialTheme.typography.titleMedium)
                }
                if (!shizuku) {
                    TextButton(onClick = { showHelp = !showHelp }) { Text("开关是灰色的、打不开？") }
                    if (showHelp) {
                        Text(
                            "这是系统对非商店安装 App 的限制：打开「应用信息」→ 右上角菜单 →「允许受限制的设置」，再回来点「去开启」。",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedButton(onClick = host::openAppDetails) { Text("打开应用信息") }
                    }
                }
            } else if (s.enabled && keep.ready < keep.total) {
                Surface(
                    onClick = onOpenSettings,
                    shape = RoundedCornerShape(14.dp),
                    color = content.copy(alpha = 0.08f),
                    contentColor = content,
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "防杀后台 ${keep.ready}/${keep.total}，去完善",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    }
                }
            }
        }
    }
}

/** The real panel composable rendered over a fake chat, with a real input box: what you see is what you get. */
@Composable
private fun PreviewCard(s: AppSettings, pipeline: TranslationPipeline, host: SettingsHost, onOpenSettings: () -> Unit) {
    val live by pipeline.state.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    var replaced by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(input) {
        if (input == replaced) return@LaunchedEffect
        replaced = null
        pipeline.submit(input)
    }

    val providerName = Providers.info(s.providerId).name
    val shown = if (input.isBlank()) {
        TranslationUi(SAMPLE_SOURCE, sampleTranslation(s.targetLang), providerName = providerName)
    } else {
        live
    }
    val actions = object : PanelActions {
        override fun replace() {
            if (shown.result.isBlank()) return
            replaced = shown.result
            input = shown.result
            pipeline.submit("")
            host.toast("已替换")
        }
        override fun copy() = host.copyText(shown.result)
        override fun speak(text: String) = host.speak(text)
        override fun cycleProvider() = App.settings.update { it.copy(providerId = Providers.next(it.providerId, it)) }
        override fun swapLanguages() = App.settings.update { it.swappedLanguages() }
        override fun retry() = pipeline.retry()
        override fun openSettings() = onOpenSettings()
    }

    SectionCard("试一试", subtitle = "下面就是真实的翻译面板，样式改动会立即显示") {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.fillMaxWidth()) {
                FakeChat()
                if (input != replaced) TranslationPanel(
                    model = PanelModel(shown, s.displayMode, s.fontSizeSp, s.sourceLang, s.targetLang, s.panelOpacity),
                    actions = actions,
                    onDismiss = { input = "" },
                    onDrag = { _, _ -> },
                    onDragEnd = {},
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .then(if (s.displayMode == DisplayMode.ANCHORED) Modifier.fillMaxWidth() else Modifier.widthIn(max = 300.dp)),
                )
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("在这里打字试试…") },
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                buildString {
                    append("$providerName → ${s.targetLang.label}")
                    if (Styles.supports(s.providerId)) append(" · ${Styles.current(s).name}")
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenSettings) { Text("更换") }
        }
    }
}

@Composable
private fun FakeChat() {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Bubble("周末有空吗？", mine = false)
        Bubble("有空呀，怎么啦", mine = true)
        Bubble("想约你出来玩", mine = false)
        Bubble("好啊，去哪儿？", mine = true)
    }
}

@Composable
private fun Bubble(text: String, mine: Boolean) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .background(
                    if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                    RoundedCornerShape(14.dp),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun StyleCard(s: AppSettings) {
    val update: Update = { App.settings.update(it) }
    SectionCard("样式") {
        ChipGroup(
            listOf(DisplayMode.ANCHORED to "贴在输入框上方", DisplayMode.FLOATING to "悬浮窗（可拖动）"),
            s.displayMode,
        ) { m -> update { it.copy(displayMode = m) } }
        LabeledSlider("字号", "${s.fontSizeSp}", s.fontSizeSp.toFloat(), 12f..22f, 9) { v ->
            update { it.copy(fontSizeSp = v.roundToInt()) }
        }
        LabeledSlider("不透明度", "${(s.panelOpacity * 100).roundToInt()}%", s.panelOpacity, 0.3f..1f, 13) { v ->
            update { it.copy(panelOpacity = (v * 20).roundToInt() / 20f) }
        }
    }
}
