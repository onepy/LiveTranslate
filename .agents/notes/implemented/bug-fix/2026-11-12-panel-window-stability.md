# Agent Note: 翻译悬浮面板替换后永久消失

Status: implemented

## Problem

在真机上，点击“替换”或点击面板上的任意按键后，翻译面板会永久消失，必须彻底杀死进程重启才能恢复。

只读排查定位到三个独立缺陷，它们叠加成“必须杀进程”：

1. `OverlayController.render()` 在**每次状态变化**时都 `removeViewImmediate` + `disposeComposition`，需要时再 `addView`。替换成功、切换翻译源、⇄ 都会把正文清空，于是每次都在拆除并重建一个 `TYPE_ACCESSIBILITY_OVERLAY` 窗口。EMUI/HarmonyOS 上反复拆除重建无障碍悬浮窗可能再也加不回来。
2. `recheckFocus()` 一旦读不到 `rootInActiveWindow` 或 `findFocus`（面板被触摸后很常见）就 `clearFocus()`。焦点被永久丢弃后没有任何路径能重新取得，面板因此不再出现。
3. 两个长生命周期收集器（渲染合并流、`TranslationPipeline` 的 `collectLatest`）只包了 `runCatching` 或完全没包，任何一个异常都会让它们永久结束，且不留下任何日志。

首次安装后实测又暴露了两个让“该不该显示”疯狂翻转的判定：

4. `TranslationRanges.select()` 会把段落 `trim()`，而 `TranslationRange.containsSelection()` 要求选区两端都落在范围内。每敲一个空格，光标就落到段落末尾之外，于是每次选区变化都 `submit("")` 清空面板，下一个字母又点亮它——逐词闪烁；停在空格或句末时则停在被清空的那一侧。
5. “同一个输入框”用的是 `AccessibilityNodeInfo` 的对象同一性：有些编辑器每敲一个字符就换一个新节点，于是每个字符都被当成换了输入框，每次都要清空再重译。

## Decision

窗口生命周期改为“每个服务运行期一个窗口，只切换可见性”。`PanelWindow` 用纯逻辑记录期望状态（`wanted`）与实际状态（`attached/alive/visible`），并给出下一步动作 `CREATE/SHOW/HIDE/NONE`；`OverlayController.sync()` 最多执行 4 步。隐藏时视图为 `GONE` 并加 `FLAG_NOT_TOUCHABLE`，窗口不销毁、composition 不释放，`disposeComposition()` 只在服务销毁或系统确实移除窗口时调用。`addView` 失败按 1.5s×失败次数（上限 8 倍）退避，不再每次状态变化都重试。

焦点判定抽成 `FocusRules.decide()`：读不到活动窗口、活动窗口属于本应用、或跟踪的输入仍存活时一律 `KEEP`；只有明确看到其它应用的可用输入框才 `CLEAR`。设置变化（切换源、⇄、透明度）不再 `submit("")`，而是用当前屏幕范围重新提交，面板内容原地刷新。服务每 3 秒跑一次守护循环：检查窗口是否仍在（`isAttachedToWindow`，带 2 秒刚建窗口宽限），并在没有跟踪输入时重新定位。

收集器统一用 `collectForever(name)` 监督：`CancellationException` 直接抛出，其它异常记录后按 0.5s×2^n（上限 30s）重启。`TranslationPipeline` 同样自监督，异常时把 `error = "翻译流程异常，已自动恢复"` 写进 UI 状态，让面板自己显示可恢复提示。诊断只写异常类型与堆栈帧（`ServiceLog.recordError`、`last_error`），绝不写入原文或译文。

第二轮补三处。光标移动不再等于清空：只有重新算出的段落范围与当前跟踪范围不同才重新提交，相同则什么都不做。`sameEditor` 改为比较包名 + `windowId`（同一个输入域，而不是同一个 `AccessibilityNodeInfo` 对象）。隐藏判定改成对称的双向驻留：`PanelWindow` 除了 `minVisibleMs = 450ms`（刚显示不许立刻藏），再加 `hideDelayMs = 400ms`（内容变空后要连续空够 400ms 才真的隐藏），因此 IME 提交、编辑器中间态造成的瞬时空白再也不会让面板眨一下。诊断只增加状态名：`翻译面板隐藏：<原因分类>`（仅在可见→隐藏过渡时记一次）、`翻译面板窗口重建（第N次）`、`翻译面板布局更新失败：<异常类型>`，绝不写原文或译文。

## Alternatives considered

- 只在 `detach()` 前加延时或重试：掩盖了每次都拆窗口这个根因，仍会在某些状态变化后失联。
- 用 `visibility = INVISIBLE` 代替 `GONE`：窗口仍占据布局并可能拦截视觉区域，`GONE` 才是真正的“不显示”。
- 失败后直接重启无障碍服务：可见的闪断，且会丢失当前输入跟踪，保留为最后手段。
- 让面板常驻并移出屏幕：仍会被系统回收，且截图与投屏中会留下残影。

## Testing

新增 13 个单测：`PanelWindowTest`（面板回来不重建窗口、隐藏态无动作、系统移除后重建、失败退避不狂刷、成功后清零）与 `FocusRulesTest`（读不到窗口/本应用/可用节点/越界节点/同包存活/同包失效/其它应用/无跟踪）。全量单测 62 个通过，`assembleRelease` 成功（APK 2.81 MB）。静态核对：`addView` 只出现在 `create()`，`removeViewImmediate` 与 `disposeComposition()` 不在 `render()` 路径上。

第二轮补 4 个单测（光标越过结尾空格仍是同一段、面板不因光标步进闪烁、驻留不会永久保留、瞬时空白不隐藏），全量 66 个单测通过，`assembleRelease` 成功（APK 2,811,546 字节）。

真机（HUAWEI HBN-AL00，Android 12）本轮已连接并实测：连续输入含空格的单词时按 40~120ms 采样 `dumpsys window windows`，悬浮窗 `mViewVisibility` 全程 `0x0`（可见），一次都没隐藏；清空输入并停顿后隐藏一次，只留下一条 `翻译面板隐藏：原文为空`；再次输入时面板在同一个窗口内复现（`0x0`），全程只有一个悬浮窗、无重建、无异常日志；删除全部再立刻重打（空白只持续约 300ms）时窗口保持可见。仍未覆盖的是面板按钮（替换、复制、⇄、连读）的连续点击，需要用户手指实测。

## Consequences

面板不再因为内容变空而拆除窗口，替换、切源、⇄ 之后继续输入即可立即复用同一个窗口；系统真的把窗口收回时能在 3 秒守护周期内重建。代价是空闲时窗口仍然存在（0×0、不可触摸、不拦截输入），以及多了一个 3 秒周期的轻量检查。短时上下文、脱敏、风格与单词释义的功能语义未改动。

第二轮改动只影响“什么时候显示/隐藏”：面板的隐藏从此至少比内容变空晚 400ms，清空输入后面板会多停留一小会儿；换来的是打字过程中不闪烁这一确定性行为，且与翻译源、脱敏、短时上下文、风格、单词释义的语义无关。

## Note audit

修改前检索到同目录两篇活跃笔记（段落范围替换、短时上下文与单词释义），本篇不改变其替换与上下文条件，只改变窗口与焦点的存活策略，因此互链而不归档。
