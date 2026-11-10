package com.livetranslate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.livetranslate.App
import com.livetranslate.BuildConfig
import com.livetranslate.a11y.A11yEnabler
import com.livetranslate.a11y.ServiceLog
import com.livetranslate.data.ApiFormat
import com.livetranslate.data.AppScope
import com.livetranslate.data.AppSettings
import com.livetranslate.data.Styles
import com.livetranslate.data.TranslationStyle
import com.livetranslate.data.TriggerMode
import com.livetranslate.keepalive.KeepAlive
import com.livetranslate.privacy.PrivacyEntry
import com.livetranslate.privacy.PrivacyRules
import com.livetranslate.shizuku.ShizukuHelper
import com.livetranslate.translate.DoubaoSession
import com.livetranslate.translate.Lang
import com.livetranslate.translate.Providers
import com.livetranslate.tts.TtsManager
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(host: SettingsHost, tts: TtsManager, refreshTick: Int, onBack: () -> Unit) {
    val s by App.settings.flow.collectAsState()
    val update: Update = { App.settings.update(it) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { ProviderSection(s, update, refreshTick, host) }
            item { StyleSection(s, update) }
            item { TranslateSection(s, update) }
            item { PrivacySection(s, update) }
            item { TtsSection(s, update, tts, host) }
            item { AppScopeSection(s, update) }
            item { KeepAliveSection(s, update, refreshTick, host) }
            item { DiagnosticsSection(host, refreshTick) }
        }
    }
}

@Composable
private fun DiagnosticsSection(host: SettingsHost, refreshTick: Int) {
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val lines = remember(refreshTick, tick) { ServiceLog.lines().takeLast(15) }
    val crash = remember(refreshTick, tick) { ServiceLog.lastCrash.lineSequence().drop(1).firstOrNull().orEmpty() }
    SectionCard("诊断", subtitle = "服务启动、停止、自动恢复和崩溃的记录，只保存在本机") {
        if (lines.isEmpty()) Hint("暂无记录")
        else Text(lines.joinToString("\n"), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        if (crash.isNotBlank()) {
            Text("上次崩溃：$crash", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        FlowRow2 {
            OutlinedButton(onClick = { host.copyText(ServiceLog.report(context, BuildConfig.VERSION_NAME)) }) { Text("复制诊断信息") }
            TextButton(onClick = { ServiceLog.clear(); tick++ }) { Text("清空") }
        }
        Hint("面板不出来时，把诊断信息发给开发者。记录里不含你输入的文字。")
    }
}

@Composable
private fun ProviderSection(s: AppSettings, update: Update, refreshTick: Int, host: SettingsHost) {
    SectionCard("翻译源") {
        Providers.all.forEach { p ->
            RadioRow(p.name, p.note, s.providerId == p.id) { update { it.copy(providerId = p.id) } }
        }
        when (s.providerId) {
            Providers.BILIBILI -> {
                Text("模型", style = MaterialTheme.typography.labelLarge)
                ChipGroup(Providers.biliModels.map { it to it }, s.biliModel) { m -> update { it.copy(biliModel = m) } }
            }
            in Providers.doubaoIds -> DoubaoLogin(refreshTick, host)
            Providers.CUSTOM -> CustomAi(s, update)
        }
    }
}

@Composable
private fun DoubaoLogin(refreshTick: Int, host: SettingsHost) {
    var tick by remember { mutableStateOf(0) }
    val loggedIn = remember(refreshTick, tick) { DoubaoSession.isLoggedIn() }
    var showPaste by remember { mutableStateOf(false) }

    CheckItem(
        done = loggedIn,
        title = if (loggedIn) "豆包已登录" else "需要登录豆包账号",
        desc = if (loggedIn) null else "登录一次即可，登录成功后自动返回",
    ) {
        FlowRow2 {
            Button(onClick = host::openDoubaoLogin) { Text(if (loggedIn) "重新登录" else "登录豆包") }
            if (loggedIn) {
                TextButton(onClick = { DoubaoSession.logout(); tick++ }) { Text("退出") }
            }
        }
        TextButton(onClick = { showPaste = true }) { Text("用电脑浏览器的登录（粘贴 Cookie）") }
    }

    if (showPaste) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPaste = false },
            title = { Text("粘贴豆包 Cookie") },
            text = {
                androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "在已登录豆包的电脑浏览器打开 doubao.com → F12 → Network（网络）→ 任选一个请求 → " +
                            "复制请求头里 Cookie 的整段内容，发到手机后粘贴到这里。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = { Text("sessionid=…; …") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val ok = DoubaoSession.importCookies(text)
                    host.toast(if (ok) "豆包已登录" else "没有找到 sessionid，请复制完整的 Cookie")
                    if (ok) {
                        showPaste = false
                        tick++
                    }
                }) { Text("导入") }
            },
            dismissButton = { TextButton(onClick = { showPaste = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun CustomAi(s: AppSettings, update: Update) {
    Text("接口格式", style = MaterialTheme.typography.labelLarge)
    ChipGroup(
        listOf(ApiFormat.CHAT to "Chat Completions", ApiFormat.RESPONSES to "Responses"),
        s.customFormat,
    ) { f -> update { it.copy(customFormat = f) } }
    OutlinedTextField(
        value = s.customBaseUrl,
        onValueChange = { v -> update { it.copy(customBaseUrl = v.trim()) } },
        label = { Text("接口地址") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = s.customApiKey,
        onValueChange = { v -> update { it.copy(customApiKey = v.trim()) } },
        label = { Text("API Key（本地模型可留空）") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = s.customModel,
        onValueChange = { v -> update { it.copy(customModel = v.trim()) } },
        label = { Text("模型") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    if (s.customBaseUrl.isNotBlank()) Hint("实际请求：${Providers.customEndpoint(s)}")
}

@Composable
private fun StyleSection(s: AppSettings, update: Update) {
    var editing by remember { mutableStateOf<TranslationStyle?>(null) }
    var creating by remember { mutableStateOf(false) }
    val supported = Styles.supports(s.providerId)

    SectionCard(
        "翻译风格",
        subtitle = if (supported) null else "当前翻译源是机器翻译，不支持风格；换成「哔哩翻译」或「自定义 AI」才生效",
    ) {
        Styles.all(s).forEach { style ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                    RadioRow(style.name, style.desc, s.styleId == style.id) { update { it.copy(styleId = style.id) } }
                }
                if (!style.isBuiltin) TextButton(onClick = { editing = style }) { Text("编辑") }
            }
        }
        OutlinedButton(onClick = { creating = true }) { Text("新增风格") }
    }

    if (creating || editing != null) {
        val original = editing
        StyleEditor(
            initial = original,
            onSave = { name, desc ->
                update { st ->
                    if (original == null) {
                        val created = Styles.newCustom(name, desc)
                        st.copy(customStyles = st.customStyles + created, styleId = created.id)
                    } else {
                        st.copy(customStyles = st.customStyles.map {
                            if (it.id == original.id) it.copy(name = name.trim(), desc = desc.trim(), instruction = desc.trim()) else it
                        })
                    }
                }
                creating = false
                editing = null
            },
            onDelete = original?.let {
                {
                    update { st ->
                        st.copy(
                            customStyles = st.customStyles.filter { c -> c.id != it.id },
                            styleId = if (st.styleId == it.id) Styles.DEFAULT_ID else st.styleId,
                        )
                    }
                    editing = null
                }
            },
            onDismiss = {
                creating = false
                editing = null
            },
        )
    }
}

@Composable
private fun StyleEditor(
    initial: TranslationStyle?,
    onSave: (String, String) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var desc by remember { mutableStateOf(initial?.desc.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增翻译风格" else "编辑翻译风格") },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名字") },
                    placeholder = { Text("例如：美式俚语") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("想要什么样的说法") },
                    placeholder = { Text("例如：像美国年轻人一样说话，多用俚语，简短") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, desc) }, enabled = name.isNotBlank() && desc.isNotBlank()) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
private fun TranslateSection(s: AppSettings, update: Update) {
    SectionCard("翻译") {
        SwitchRow("短时上下文", s.shortContext) { value -> update { it.copy(shortContext = value) } }
        Hint("仅哔哩和自定义 AI 生效。替换成功后 5 秒内开始下一段，携带最近 3 条、最多 500 字；开始后停顿不清空。仅内存保存，上文会发送给翻译源。")
        Text("什么时候翻译", style = MaterialTheme.typography.labelLarge)
        RadioRow("边打边译", "打字时自动翻译", s.triggerMode == TriggerMode.REALTIME) {
            update { it.copy(triggerMode = TriggerMode.REALTIME) }
        }
        RadioRow(
            "长按输入框再翻译",
            "长按输入框才翻译里面的文字；不影响粘贴、全选、复制等长按菜单",
            s.triggerMode == TriggerMode.LONG_PRESS,
        ) { update { it.copy(triggerMode = TriggerMode.LONG_PRESS) } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("从", Modifier.width(40.dp))
            Choice(s.sourceLang.label, Lang.entries.map { it.label }) { i -> update { it.copy(sourceLang = Lang.entries[i]) } }
            IconButton(onClick = { update { it.swappedLanguages() } }) { Text("⇄", style = MaterialTheme.typography.titleLarge) }
            Text("译成", Modifier.width(40.dp))
            Choice(s.targetLang.label, Lang.targets.map { it.label }) { i -> update { it.copy(targetLang = Lang.targets[i]) } }
        }
        if (s.triggerMode == TriggerMode.REALTIME) {
            LabeledSlider("停顿多久开始翻译", "${s.debounceMs}ms", s.debounceMs.toFloat(), 150f..1200f, 20) { v ->
                update { it.copy(debounceMs = (v / 50).roundToInt() * 50L) }
            }
        }
    }
}

@Composable
private fun PrivacySection(s: AppSettings, update: Update) {
    val p = s.privacy
    val setP: ((PrivacyRules) -> PrivacyRules) -> Unit = { f -> update { it.copy(privacy = f(it.privacy)) } }
    var adding by remember { mutableStateOf(false) }
    SectionCard("隐私脱敏", subtitle = "命中的内容先换成随机代号再发给翻译源，译文回来后在本机换回") {
        SwitchRow("发送前脱敏", p.enabled) { v -> setP { it.copy(enabled = v) } }
        if (p.enabled) {
            SwitchRow("邮箱", p.emails) { v -> setP { it.copy(emails = v) } }
            SwitchRow("身份证号", p.idCards) { v -> setP { it.copy(idCards = v) } }
            SwitchRow("6 位以上数字（手机号、银行卡等）", p.longDigits) { v -> setP { it.copy(longDigits = v) } }
            Text("名单", style = MaterialTheme.typography.labelLarge)
            if (App.settings.privacyListLost && p.entries.isEmpty()) {
                Text("名单解密失败，已清空，请重新添加", color = MaterialTheme.colorScheme.error)
            }
            if (p.entries.isEmpty()) Hint("人名、公司、地址、项目代号等，至少 ${PrivacyRules.MIN_KEY} 个字")
            p.entries.forEach { e ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (e.alias.isBlank()) e.text else "${e.text} → ${e.alias}", Modifier.weight(1f))
                    TextButton(onClick = { setP { it.copy(entries = it.entries - e) } }) { Text("删除") }
                }
            }
            OutlinedButton(onClick = { adding = true }) { Text("添加") }
            Hint("名单加密保存在本机。边打边译时，光标前没打完的名单词和数字也会先遮住。朗读和复制的是还原后的译文，在线朗读引擎、输入法云剪贴板不受保护。")
        }
    }
    if (adding) {
        var text by remember { mutableStateOf("") }
        var alias by remember { mutableStateOf("") }
        val valid = text.trim().length >= PrivacyRules.MIN_KEY && p.entries.none { it.text == text.trim() }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("添加脱敏内容") },
            text = {
                androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("原文") },
                        placeholder = { Text("例如：张三") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = alias, onValueChange = { alias = it }, label = { Text("译文中显示为（可选）") },
                        placeholder = { Text("例如：Zhang San") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = valid, onClick = {
                    setP { it.copy(entries = it.entries + PrivacyEntry(text.trim(), alias.trim())) }
                    adding = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun TtsSection(s: AppSettings, update: Update, tts: TtsManager, host: SettingsHost) {
    val engines by tts.engines.collectAsState()
    SectionCard("朗读") {
        val options = listOf("" to "系统默认") + engines.map { it.name to it.label }
        ChipGroup(options, s.ttsEngine) { e -> update { it.copy(ttsEngine = e) } }
        LabeledSlider("语速", "${"%.1f".format(s.ttsRate)}x", s.ttsRate, 0.5f..2f, 14) { v ->
            update { it.copy(ttsRate = (v * 10).roundToInt() / 10f) }
        }
        OutlinedButton(onClick = { host.speak(sampleTranslation(s.targetLang)) }) { Text("试听") }
    }
}

@Composable
private fun AppScopeSection(s: AppSettings, update: Update) {
    val context = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    SectionCard("在哪些应用里生效") {
        ChipGroup(listOf(AppScope.ALL to "所有应用", AppScope.WHITELIST to "只在选中的应用"), s.appScope) { v ->
            update { it.copy(appScope = v) }
        }
        if (s.appScope == AppScope.WHITELIST) {
            val names = remember(s.whitelist) { s.whitelist.map { AppList.label(context, it) }.sorted() }
            Text(
                if (names.isEmpty()) "还没有选择应用" else names.joinToString("、"),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = { picking = true }) { Text("选择应用") }
        }
        Hint("密码框永远不会被读取。")
    }
    if (picking) {
        AppPickerDialog(
            initial = s.whitelist,
            onDone = { set ->
                update { it.copy(whitelist = set) }
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun KeepAliveSection(s: AppSettings, update: Update, refreshTick: Int, host: SettingsHost) {
    val context = LocalContext.current
    val status = remember(refreshTick, s.keepAliveNotification) { KeepAlive.status(context, s) }
    val shizuku = remember(refreshTick) { ShizukuHelper.isRunning() }
    val canWrite = remember(refreshTick) { A11yEnabler.canWriteSecureSettings(context) }

    SectionCard("防止被杀后台", subtitle = "授权自动恢复，并允许系统保留后台服务") {
        OutlinedButton(onClick = host::restartService) { Text("重启服务 / 重建面板") }
        CheckItem(
            done = status.autoRestore,
            title = "被杀后自动恢复",
            desc = if (status.autoRestore) "已授权，服务掉了会自己拉起来" else "需要一次授权（Shizuku 或电脑 adb）",
        ) {
            if (!canWrite) {
                FlowRow2 {
                    if (shizuku) Button(onClick = host::runShizukuBoost) { Text("Shizuku 一键授权") }
                    OutlinedButton(onClick = { host.copyText(KeepAlive.adbGrantCommand(context.packageName)) }) {
                        Text("复制 adb 命令")
                    }
                }
            }
        }
        CheckItem(
            done = status.notification,
            title = "常驻通知",
            desc = "提高后台优先级，通知栏里还能一键暂停",
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = s.keepAliveNotification,
                    onCheckedChange = { v ->
                        update { it.copy(keepAliveNotification = v) }
                        if (v && !KeepAlive.hasNotificationPermission(context)) host.requestNotificationPermission()
                    },
                )
                if (s.keepAliveNotification && !status.notification) {
                    TextButton(onClick = host::openNotificationSettings) { Text("通知设置") }
                }
            }
        }
        CheckItem(done = status.battery, title = "不限制电池") {
            if (!status.battery) OutlinedButton(onClick = host::requestIgnoreBatteryOptimizations) { Text("去设置") }
        }
        CheckItem(done = null, title = "允许自启动 / 后台运行", desc = "小米、华为、OPPO、vivo 等必须打开") {
            OutlinedButton(onClick = host::openAutostart) { Text("去设置") }
        }
        CheckItem(done = null, title = "厂商后台弹出 / 悬浮显示权限", desc = "部分系统单独限制后台显示，请在应用信息里检查；App 无法统一读取厂商开关") {
            OutlinedButton(onClick = host::openAppDetails) { Text("打开应用信息") }
        }
        Hint("翻译面板使用无障碍悬浮层，Android 标准悬浮窗权限不决定它是否可用。首页显示服务连接状态，窗口显示失败会单独提示。")
        CheckItem(done = null, title = "在最近任务里锁住本 App", desc = "打开最近任务，下拉或长按本 App 的卡片，选「锁定」")
        CheckItem(done = null, title = "加一个快捷开关", desc = "下拉通知栏 → 编辑 → 把「边写边译」拖进去，一键暂停/恢复")
        if (shizuku && canWrite) {
            OutlinedButton(onClick = host::runShizukuBoost) { Text("用 Shizuku 再加固一次") }
        }
    }
}
