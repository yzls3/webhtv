# WebHome 首页端到端融合方案评估

日期：2026-06-10（2026-06-11 评审修订；2026-06-13 实施回填）

本文最初用于可行性和方案评估；2026-06-13 已按本文核心方向落地手机端 WebHome chrome runtime、`normal/edge/immersive` 模式、真实 viewport/safe area 注入、`setChrome/restoreChrome/getViewport/openVod` SDK、内置 demo 迁移、主开发文档和扩展文档回写。仍未覆盖的内容以文内技术债和待定问题为准。

兼容性前提：新版 App 可以改变 WebHome 的安全区注入契约；现有 HTML 页面允许作为迁移对象，不作为阻止真实 insets / edge 默认化的兼容约束。落地时应同步改造内置 demo、模板和文档，旧页面若仍使用 `var(--fm-safe-bottom) + env(...)` 这类相加写法，需要按新版契约迁移后再作为验收对象。

## 1. 结论

推荐方案不是简单隐藏系统栏，也不是把 WebView 缩回系统栏以内，而是引入 WebHome 专用的 `edge` 融合模式：

- 视觉层：WebHome 背景延伸到整块屏幕，包括状态栏和导航栏背后。
- 交互层：WebHome 顶部、底部按钮必须避开系统栏、刘海、手势返回、底部 Home 手势区。
- 原生层：App 提供真实 WindowInsets、安全区 CSS 变量、系统栏图标明暗控制、原生兜底恢复入口。
- 沉浸层：真正隐藏状态栏/导航栏只作为用户主动开启的 `immersive` 模式，不作为默认首页体验。

最终推荐提供三种 chrome 模式：

| 模式 | 用途 | 系统栏 | 原生顶部/底部 UI | WebHome 职责 |
| --- | --- | --- | --- | --- |
| `normal` | 兼容现有行为 | 显示 | 显示 | 普通 Web 页面 |
| `edge` | 推荐默认融合方案 | 显示，透明/半透明 | 透明覆盖或隐藏 | 画满背景，交互控件按 safe area 避让 |
| `immersive` | 视频、阅读、用户主动专注 | 隐藏，可边缘滑出 | 隐藏 | 提供页面内恢复/操作区，原生提供兜底恢复 |

## 2. 为什么不能只做全屏隐藏

只把 WebView 全屏铺满并隐藏原生 UI，会带来几个实际问题：

- 顶部按钮可能落入状态栏、刘海、挖孔、系统通知图标区域。
- 底部按钮可能落入三键导航栏或手势导航 Home 区域。
- 左右边缘滑动控件可能和 Android 返回手势冲突。
- WebHome 脚本异常时，页面内恢复按钮可能不可用。
- 用户想临时使用 App 原生功能时，没有稳定入口。
- 沉浸模式会降低系统导航可见性，官方建议只在媒体、游戏、图片、书籍、演示等明显受益场景使用。

因此全屏隐藏只能作为 `immersive` 次级能力，不能作为默认融合方案。

## 3. 外部最佳实践结论

### 3.1 Android 官方 edge-to-edge 方向

Android 官方文档明确要求 edge-to-edge 时内容可以画到系统栏后面，但要处理遮挡：

- target SDK 35 及以上在 Android 15 会被强制 edge-to-edge。
- 顶部 AppBar 应延伸到状态栏后面，底部 AppBar/BottomNavigation 也应延伸到导航栏后面，或者让滚动内容显示在导航栏后面。
- 需要处理视觉重叠，并在必要时为系统栏加保护层/scrim。
- `systemBars` insets 用于可点击且不能被系统栏遮住的 UI。
- `systemGestures` insets 用于系统手势优先的区域，底部 Home 手势和左右返回手势都应考虑。

参考：

- https://developer.android.com/develop/ui/views/layout/edge-to-edge
- https://developer.android.com/design/ui/mobile/guides/layout-and-content/edge-to-edge

对本项目的含义：

- WebHome 背景应该可以画到状态栏/导航栏背后。
- WebHome 的搜索、菜单、播放、设置等按钮不能直接贴边，必须使用 Insets 避让。
- 需要区分视觉背景和可点击控件，不能用同一套 padding 粗暴处理。

### 3.2 Android WebView Insets 机制

Android WebView 官方文档说明，WebView 对 window insets 的支持正在演进：

- M136：fullscreen WebView 支持通过 CSS `safe-area-inset-*` 暴露 `displayCutout()` 和 `systemBars()`。
- M139：IME 通过 visual viewport resizing 支持键盘避让。
- M144：所有 WebView 都支持 `displayCutout()` 和 `systemBars()`，不再只限 fullscreen WebView。
- WebView 只有在系统 UI 与自身边界重叠时才会收到非零 inset；如果 WebView 没有触到屏幕边缘，CSS safe area 可能是 0。

参考：

- https://developer.android.com/develop/ui/views/layout/webapps/understand-window-insets

对本项目的含义：

- 不能只依赖 WebView/Chrome 自带的 `env(safe-area-inset-*)`，因为不同 WebView 版本、fullscreen 状态、WebView 边界是否触边都会影响结果。
- App 应主动采集 Android `WindowInsetsCompat`，通过自定义 CSS 变量和 `fmviewport` 事件传给 WebHome，作为稳定兜底。
- WebView 自带 `env()` 可以作为增强，但项目自己的 `--fm-safe-*` 才是兼容层。

### 3.3 Web / Chrome edge-to-edge 经验

Chrome Android edge-to-edge 迁移文档给出的核心做法：

- 页面可通过 `viewport-fit=cover` 表示愿意处理 edge-to-edge。
- 底部固定元素不要直接 `bottom: 0`，否则会被底部手势导航区域遮挡。
- 推荐用 safe area inset 让底部元素停在手势导航之上。
- 对底部固定栏，单纯把 `padding-bottom` 设为动态 safe-area-inset 可能造成 layout thrashing；更优做法是结合 `safe-area-inset-bottom` 和 `safe-area-max-inset-bottom`，让视觉背景延伸、交互位置稳定。

参考：

- https://developer.chrome.com/docs/css-ui/edge-to-edge
- https://developer.mozilla.org/en-US/docs/Web/HTML/Reference/Elements/meta/name/viewport
- https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/Values/env

对本项目的含义：

- WebHome 模板需要明确支持 `viewport-fit=cover`。
- 底部操作区建议使用“双层结构”：背景层延伸到底部设备边缘，按钮层停在 `safeBottom` 之上。
- 对动态安全区，不要让大块布局频繁 reflow；优先移动固定定位层或使用 transform/bottom 计算。

### 3.4 开源和跨端社区经验

React Native、Ionic/Capacitor 等跨端社区在 Android 15 后遇到的问题和本项目非常接近：WebView 或 JS 渲染内容扩展到系统栏后，底部按钮可能被 Android 按键遮住，导致不可点击。主流解决方向都是：

- 开启 edge-to-edge。
- 使用 Safe Area/Insets 体系处理交互控件。
- 管理系统栏图标颜色/明暗。
- 不把“给 WebView 加 margin 缩回安全区”作为沉浸体验的主方案。

参考：

- https://github.com/ionic-team/ionic-framework/issues/30090
- https://github.com/react-native-community/discussions-and-proposals/discussions/921
- https://github.com/zoontek/react-native-edge-to-edge

Google 的 Now in Android 示例也采用 Activity 级 `enableEdgeToEdge`，并在 UI 层用 `safeDrawing`/insets 消费解决布局重叠，而不是把整个窗口退回旧式非 edge-to-edge。

参考：

- https://github.com/android/nowinandroid

对本项目的含义：

- WebHome 本质上也是“原生容器 + Web/JS UI”，应采用和跨端框架类似的 safe area provider 模型。
- `fmviewport` 可以扮演 WebHome 的 SafeAreaProvider。
- 系统栏控制应做成有状态的原生服务，供 WebHome 受控调用。

## 4. 当前项目现状

本章是 2026-06-10/11 做方案评估时的实施前代码审计快照，保留用于解释改造动因。2026-06-13 后的实际行为以本文开头实施回填、§16 回写状态和 `docs/应用完整开发文档.md` 为准。

### 4.1 已有基础

手机端 `BaseActivity` 已启用 edge-to-edge：

- `app/src/mobile/java/com/fongmi/android/tv/ui/base/BaseActivity.java`
- 当前调用：`EdgeToEdge.enable(this, SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))`
- Android Q+ 已关闭 status/navigation bar contrast enforcement。
- 当前工程 `targetSdk=28`，Android 15 的 target 35 强制 edge-to-edge 不是现阶段直接触发因素；但项目已经主动启用 `EdgeToEdge.enable()`，且未来 target bump 必然要面对同类问题，所以本方案仍按现代 edge-to-edge 模型设计。

首页已有隐藏导航和系统栏能力：

- `app/src/mobile/java/com/fongmi/android/tv/ui/activity/HomeActivity.java`
- `setNavigationVisible(false)` 会隐藏底部 `BottomNavigationView` 并让容器铺满。
- `setWebHomeFullscreen(true)` 会把 root `fitsSystemWindows` 设为 false，并调用 `Util.hideSystemUI(this)`。

WebHome 已有桥接能力：

- `app/src/main/java/com/fongmi/android/tv/web/HomeWebBridge.java`
- 当前已有 `fm.ui.setToolbar(visible)`，最终控制 `VodFragment.setToolbar()`。

WebHome 视口注入已有雏形：

- `app/src/main/java/com/fongmi/android/tv/web/HomeWebController.java`
- 当前注入 `--fm-web-width`、`--fm-web-height`、`--fm-safe-bottom: 0px` 和 `fmviewport`。

### 4.2 主要缺口

当前实现更接近二值开关：

- `toolbar=true`：原生 AppBar/底部导航存在，WebHome 只在中间区域，割裂感明显。
- `toolbar=false`：隐藏原生 UI 和系统栏，进入沉浸，但没有完整安全区和恢复体系。

缺少的关键能力：

- 没有真实 `safeTop/safeBottom/safeLeft/safeRight`。
- 没有 `systemGestureInsets`。
- 没有 display cutout 信息。
- 没有确认 `layoutInDisplayCutoutMode`，刘海区能否延伸无保障（见 TD-12）。
- 没有状态栏/导航栏图标明暗控制 API。
- 没有 chrome 状态机，只有 `setToolbar(boolean)`。
- 没有原生兜底恢复入口。
- 没有 WebHome 侧操作区设计规范。

## 5. 推荐产品体验

### 5.1 默认体验：edge 融合模式

手机端 WebHome 首页默认进入 `edge` 模式：

- App 原生底部导航隐藏或转成透明覆盖态。
- WebView 铺满整个窗口。
- 状态栏和手势导航栏透明。
- WebHome 的背景图/渐变/主题色延伸到状态栏和底部导航区背后。
- WebHome 顶部交互区从 `safeTop` 下方开始。
- WebHome 底部操作区从 `safeBottom` 上方开始。
- 系统栏图标颜色根据 WebHome 背景选择 light/dark。

用户感知：

- 首页像一个完整 App，而不是 App 框里套网页。
- 状态栏/底部手势区仍然存在，用户不会丢失系统导航。
- 顶部/底部按钮可点击，不被系统区域遮挡。

### 5.2 沉浸体验：immersive 模式

沉浸模式只在用户主动触发时进入，例如：

- WebHome 的“专注/全屏”按钮。
- 视频/阅读类 WebHome 页面。
- 用户在设置中选择“首页默认沉浸”。

进入后：

- App 原生 UI 隐藏。
- 系统状态栏/导航栏隐藏。
- Android 边缘滑动可临时呼出系统栏。
- 页面内提供恢复按钮。
- 原生层保留一个低干扰兜底恢复入口。
- 返回键第一次退出沉浸，第二次才执行页面返回/退出。

### 5.3 恢复入口

不能只依赖 WebHome 自己画恢复按钮。推荐三层恢复：

1. 系统层：保留 Android transient bars by swipe，用户从边缘滑动可临时显示系统栏。
2. 原生层：App 提供安全区内的微型悬浮恢复入口，例如右上角/右下角 32dp 胶囊按钮，短按恢复 `edge/normal`，长按打开更多原生操作。
3. 返回键：沉浸状态下第一次返回恢复 chrome，避免用户被困在全屏页面。

原生恢复入口建议只在 `immersive` 下显示；`edge` 下可不显示，或者只在 WebHome 页面没有声明自己提供恢复能力时显示。

## 6. 推荐技术方案

### 6.0 SDK 命名约定（以《应用完整开发文档》§16 为准）

本方案新增的所有 JS API 必须沿用现有 SDK 命名规范，不要自创 `fm.app.*` 这类命名空间。规范要点：

- 完整命名空间是 `fongmi.<group>.<method>`，`window.fm` 是短别名。`group` 取值：`net` / `player` / `app` / `pan` / `cache` / `ui` / `device` / `site` / `config` / `navigation`。
- `app`、`navigation`、`player`、`net` 组的方法在短别名上是**扁平**的，不保留 `app` 段：`fongmi.app.search` → `fm.search`、`fongmi.app.openSetting` → `fm.openSetting`、`fongmi.navigation.back` → `fm.back`、`fongmi.navigation.reload` → `fm.reload`。**不存在 `fm.app.search` / `fm.navigation.back` 这种写法。**
- 结构化分组在短别名上**保留嵌套**：`fm.pan.*`、`fm.cache.*`、`fm.ui.*`（例如已有的 `fm.ui.setToolbar`）。因此本方案新增的 chrome 能力都放在 `ui` 组：`fongmi.ui.setChrome` / `fongmi.ui.restoreChrome` / `fongmi.ui.getViewport`，短别名分别是 `fm.ui.setChrome` / `fm.ui.restoreChrome` / `fm.ui.getViewport`。
- `fm.device()`、`fm.site()`、`fm.config()` 在短别名上是**可调用函数**（对应 `fongmi.device.info` / `fongmi.site.info` / `fongmi.config.info`）。所以**不能**在短别名上挂 `fm.device.safeArea()`、`fm.site.select()`、`fm.config.reload()` 等子方法——会和函数本体冲突。这类新方法应放在完整命名空间（`fongmi.site.select()`、`fongmi.config.reload()`），短别名另起独立名字（如 `fm.selectSite()`、`fm.reloadConfig()`），且要避开已被占用的 `fm.reload`（页面重载）。
- **复用优先、禁止重复定义**：能用已有 API 就不要新增。不要为同一语义提供两个入口（例如曾考虑的 `switchTab("vod")` 与 `openVod()` 重复，且 `switchTab` 还会和已有的 `openSetting`/`openLive`/`openKeep` 重叠——已统一为只保留 `openVod`）。新增 app 动作一律沿用扁平动词命名（`openVod`、`openPush`），不要引入并行范式。
- 两处"看起来像重复其实不是"的成对接口，需在文档里写明区别，避免开发者困惑：`fongmi.ui.getViewport()` 是一次性 pull，`fmviewport` 事件是持续 push，二者同源；`restoreChrome()` 是"回到进入沉浸前的模式"的语义糖（Native 记住上一个 mode），与需要显式指定目标的 `setChrome({mode})` 不重复。

下文出现的 API 一律按此规范书写。

### 6.1 Chrome 模式状态机

新增统一状态，而不是继续扩展 `setToolbar(boolean)`：

```text
normal -> edge -> immersive
   ^       ^        |
   |       |        v
   +-------+----- restore
```

建议 API：

```js
fm.ui.setChrome({
  mode: "normal" | "edge" | "immersive",
  statusBarStyle: "auto" | "light" | "dark",
  navigationBarStyle: "auto" | "light" | "dark",
  scrim: {
    top: "transparent" | "auto" | "#RRGGBBAA",
    bottom: "transparent" | "auto" | "#RRGGBBAA"
  },
  restoreAffordance: "auto" | "native" | "web" | "none"
})
```

兼容旧 API：

```js
fm.ui.setToolbar(false)
```

旧 API 定义为 legacy chrome API：

- `fm.ui.setToolbar(false)` 保持当前语义：隐藏原生顶部/底部 UI 并进入旧式全屏/沉浸行为。
- `fm.ui.setToolbar(true)` 恢复原生 UI。
- 新模板和新版 HTML 不再用 `setToolbar(false)` 表示融合模式，统一改用 `fm.ui.setChrome({ mode: "edge" })` 或 Native 预声明的 `chromeMode`。
- 后续如需移除旧语义，应通过模板迁移和文档升级完成，而不是把 `setToolbar(false)` 静默改成 `edge`。

首帧时序要求：

- `edge` 不能只依赖 HTML 加载后调用 `fm.ui.setChrome({ mode: "edge" })`。当前 SDK 是 `onPageFinished` 后注入，页面 JS 调用会晚于 WebView 首次布局，容易出现 toolbar/top margin 先按 `normal` 绘制、随后跳到 `edge` 的首帧抖动。
- App 必须支持 Native 预应用 chrome mode：在 `HomeWebController.load(site)` 显示 WebView 前，根据站点配置、用户设置或本地 WebHome 元数据先应用 `normal/edge/immersive/tv-*`。
- HTML 内 `setChrome()` 只用于运行时切换；默认模式应来自 Native 可预读字段，例如站点级 `chromeMode` / `webHomeChrome`，或 App 设置里的“WebHome 默认模式”。
- 当前 `Site` 还没有 `chromeMode` 字段，落地时需新增解析字段并在配置文档中登记。不能把“HTML 里的脚本启动后再声明 edge”当成默认启用方案。

### 6.2 Insets 注入

App 原生层采集以下信息：

- `systemBars`: status/navigation/caption bar。
- `displayCutout`: 刘海、挖孔、圆角裁切相关区域。
- `systemGestures`: 左右返回、底部 Home 手势优先区域。
- `ime`: 键盘可见时的底部遮挡。
- 当前 chrome mode。
- 当前系统栏是否隐藏。

注入 CSS 变量：

```css
:root {
  --fm-web-width: 360px;
  --fm-web-height: 800px;
  --fm-safe-top: 24px;
  --fm-safe-right: 0px;
  --fm-safe-bottom: 24px;
  --fm-safe-left: 0px;
  --fm-gesture-left: 18px;
  --fm-gesture-right: 18px;
  --fm-gesture-bottom: 24px;
  --fm-status-bar-height: 24px;
  --fm-navigation-bar-height: 24px;
  --fm-keyboard-bottom: 0px;
  --fm-chrome-mode: edge;
}
```

注意两点：

- `--fm-keyboard-bottom` 首期不要注入，只保留字段占位：M139+ 的 WebView 会自行通过 visual viewport resizing 做键盘避让（仅对 WebView 底部生效）。若原生再注入 `--fm-keyboard-bottom` 让页面避让一次，会出现与 TD-11 同构的“双重内缩”。是否需要原生注入，按 Q15 的真机实测结论定（见 TD-13）。
- 注入发生在 WebView layout 之后，页面首帧会按 CSS `var()` 的 fallback 值渲染，注入到达后才校正。模板应在 `:root` 自带量级合理的默认值（如 `--fm-safe-top: 24px; --fm-safe-bottom: 24px`），保证“默认值渲染 → 注入校正”之间不发生大幅跳动；迁移指引需写明该约定。

派发事件：

```js
window.dispatchEvent(new CustomEvent("fmviewport", {
  detail: {
    width,
    height,
    safeTop,
    safeRight,
    safeBottom,
    safeLeft,
    gestureLeft,
    gestureRight,
    gestureBottom,
    statusBarHeight,
    navigationBarHeight,
    keyboardBottom,
    chromeMode,
    systemBarsHidden
  }
}))
```

WebHome 页面应优先使用 `--fm-safe-*`，再用 CSS `env(safe-area-inset-*)` 做补充：

```css
--safe-top: max(var(--fm-safe-top, 0px), env(safe-area-inset-top, 0px));
--safe-bottom: max(var(--fm-safe-bottom, 0px), env(safe-area-inset-bottom, 0px));
```

### 6.3 WebHome 页面结构规范

推荐 WebHome 首页采用三层结构：

```text
root/fullscreen background
  background layer: 延伸到设备四边
  scroll/content layer: 可滚动内容，可进入系统栏背后
  controls layer: 顶部/底部按钮，使用 safe area 定位
```

顶部：

- 背景可以延伸到 `top: 0`。
- 搜索框、设置、返回、菜单等按钮从 `safeTop + 8/12dp` 开始。
- 不要在 `safeTop` 以内放可点击目标。

底部：

- 底部背景可以延伸到 `bottom: 0`。
- 按钮栏的可点击部分必须在 `safeBottom` 之上。
- 底部按钮栏建议固定高度，背景层另行填充到设备底边，避免动态 safe area 导致布局抖动。

左右边缘：

- 轮播、侧滑抽屉、横向列表不要把拖拽起点放在 `gestureLeft/gestureRight` 内。
- 必要时留出 16dp-24dp 起始空白，或只允许从控件内部拖动。

### 6.4 原生功能开放边界

为了让 WebHome 可以自己实现融合后的顶部/底部操作区，需要开放常用 App 操作，但必须受控。

优先开放（命名遵循 §6.0，括号内为短别名）：

- `fongmi.app.search(keyword, options)`（`fm.search`）：已有。
- `fongmi.app.openSetting()`（`fm.openSetting`）：已有。
- `fongmi.app.history()`（`fm.history`）：已有。
- `fongmi.app.openLive()`（`fm.openLive`）：已有。
- `fongmi.app.openKeep()`（`fm.openKeep`）：已有。
- `fongmi.ui.setChrome()`（`fm.ui.setChrome`）：新增。
- `fongmi.ui.restoreChrome()`（`fm.ui.restoreChrome`）：新增。
- `fongmi.ui.getViewport()`（`fm.ui.getViewport`）：新增；一次性读取当前安全区/手势区（与持续推送的 `fmviewport` 事件同源，一个是 pull、一个是 push，非重复定义）。注意不要写成 `fm.device.safeArea()`（`fm.device` 是函数，见 §6.0）。
- `fongmi.app.openVod()`（`fm.openVod`）：新增，退出 WebHome chrome 并回到原生点播首页。对齐已有的 `openSetting`/`openLive`/`openKeep` 扁平动词，**不引入 `switchTab` 这类会与现有 `openX` 重叠的新范式**（见 §6.0）。
- `fongmi.app.openAction(action, payload)`（`fm.openAction`）：可选，统一收口增强功能入口，例如 `openAction("webhomeExtension" | "siteInject" | "loginState" | "manage")`，比为每个增强功能单开方法更可控（与 §6.5 阶段3 的统一 action 入口一致）。

谨慎开放：

- 文件管理。
- 登录态迁移。
- Cookie/账号相关操作。
- 配置删除、清缓存、批量同步。
- 任意本地服务管理。

这些高风险能力应至少满足：

- 只允许可信 WebHome 或当前配置白名单调用。
- 原生二次确认。
- 明确 toast/日志。
- 不允许静默执行破坏性操作。

## 7. 被否掉的方案

### 7.1 只隐藏顶部/底部系统栏

优点：

- 实现最短。
- 视觉上立刻不割裂。

问题：

- 顶部/底部交互容易被系统手势、刘海、虚拟键影响。
- 用户会失去系统导航和 App 原生入口。
- 页面异常时恢复困难。
- 不符合 Android 对普通 App 的默认体验建议。

结论：只作为 `immersive` 次级模式，不作为默认方案。

### 7.2 给 WebView 加上下 margin，缩回安全区

优点：

- 几乎不会遮挡按钮。
- 旧 Android 行为接近。

问题：

- 视觉割裂仍然存在。
- 背景不能进入系统栏，无法做到“融合为一体”。
- Android 15+ edge-to-edge 趋势下属于保守兜底，不是长期最佳体验。

结论：只可作为兼容/无能力 WebHome 的 fallback，不作为 WebHome 首页目标方案。

### 7.3 原生采样 WebHome 背景色并同步系统栏颜色

优点：

- 对不支持 edge-to-edge 的页面可能有一定美化。

问题：

- WebView 内容动态变化、滚动、视频、透明层都难以稳定采样。
- 性能和时序复杂。
- 状态栏图标可读性难保障。
- 仍然不能解决交互区避让。

结论：不建议首期实现。应让 WebHome 自己画满背景，并由 WebHome 或配置声明系统栏图标明暗。

### 7.4 完全交给 WebHome 控制，无原生兜底

优点：

- 原生实现轻。
- WebHome 自由度最高。

问题：

- WebHome 脚本异常时用户可能无法恢复。
- 恶意或错误页面可能隐藏入口。
- 高风险原生功能容易被滥用。

结论：WebHome 可以实现主操作区，但原生必须保留恢复和权限边界。

## 8. 分阶段落地建议

### 阶段 1：安全区基础设施

目标：先解决按钮遮挡和信息注入，同时建立新版 WebHome HTML 契约。

- 在 `HomeActivity` 或 WebHome 容器根节点监听 `WindowInsetsCompat`。
- 采集 `systemBars | displayCutout | systemGestures | ime`。
- 扩展 `HomeWebController.injectViewport()`，注入真实 `--fm-safe-*` 和 `fmviewport.detail`。
- `--fm-safe-bottom` 不再硬编码 0；新版 App 直接注入真实值。
- 同步改造内置 demo / 模板：`--fm-safe-*` 与 `env(safe-area-inset-*)` 必须用 `max()` 去重，不能相加。
- WebHome 扩展文档补充安全区变量使用规范和旧 HTML 迁移说明。
- 同轮完成两项真机实测并把结论回填本文：目标 WebView 版本是否把重叠 insets 自动转成内部 padding（TD-11 / Q5）；当前 androidx.activity 版本的 `EdgeToEdge.enable()` 是否已设置 `layoutInDisplayCutoutMode = shortEdges`（TD-12 / Q14）。

风险：中。除了 Insets 分发和生命周期细节，还会影响所有按旧契约写死 `--fm-safe-bottom + env()` 的 HTML；本项目接受该破坏性变化，但必须把 HTML 迁移纳入同一阶段验收。

### 阶段 2：edge 模式

目标：默认融合但不隐藏系统导航。

- 新增 chrome mode 状态机。
- 新增 Native 可预读的默认模式来源，例如站点 `chromeMode` / `webHomeChrome` 字段或 App 设置。
- 在 WebView 首次显示前应用 `edge`，避免页面 `onPageFinished` 后调用 `setChrome()` 造成首帧跳动。
- `edge` 下 WebView 铺满窗口，原生 AppBar/BottomNavigation 隐藏或透明 overlay。
- 系统栏透明，图标明暗可设置。
- 刘海/挖孔设备上确认内容可延伸进 cutout 区域：按 TD-12 / Q14 实测结论决定是否显式设置 `layoutInDisplayCutoutMode = shortEdges`。
- 让 WebHome 背景延伸到系统栏后面。
- 回退到普通 Vod UI、设置页、其他 Activity 时恢复 `normal`。

风险：中。需要处理页面切换、返回、旋转、键盘、三键导航。

### 阶段 3：WebHome 操作区 API

目标：让 WebHome 可以优雅替代原生顶部/底部操作区。

- 新增 `fongmi.ui.setChrome()` / `fongmi.ui.restoreChrome()`（短别名 `fm.ui.setChrome` / `fm.ui.restoreChrome`）。
- 新增常用 App action API 或统一 `fongmi.app.openAction(action, payload)`（短别名 `fm.openAction`）。
- 对高风险 action 做白名单和原生确认。
- 给 WebHome 模板提供推荐顶部/底部控件布局。

风险：中。重点是权限边界和长期 API 稳定性。

### 阶段 4：immersive 模式和恢复兜底

目标：提供真正隐藏系统栏的专注模式。

- 先重构现有 `Util.hideSystemUI()`：移除 `FLAG_FULLSCREEN`，改为纯 `WindowInsetsControllerCompat.hide(systemBars())` + `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`。
- edge 模式绝不调用 `hideSystemUI()`；只有 `immersive` 调用隐藏系统栏能力。
- 原生增加安全区内小型恢复入口。
- 返回键第一下恢复 chrome。
- WebHome 可声明自己是否提供恢复按钮。
- 系统栏临时显示/隐藏时同步 `fmviewport`。

风险：中。重点是不要把用户困住，同时要回归 `hideSystemUI()` 的其它调用方（扫码、直播、视频播放、TV BaseActivity）。

### 阶段 5：细节优化

目标：体验打磨。

- 三键导航模式下为底部提供轻微 scrim。
- 根据 WebHome 声明或实时状态切换系统栏图标明暗。
- 横屏、折叠屏、平板适配。
- 调试工作台展示当前 insets/chrome mode。
- 加入 WebHome 安全区预览工具。

风险：低到中。

## 9. 验收标准

必须覆盖以下设备/场景：

- Android 10、12、14、15+。
- 手势导航。
- 三键导航。
- 有刘海/挖孔设备。
- 无刘海设备。
- 竖屏。
- 横屏。
- 横屏 + 三键导航（导航栏位于屏幕侧边，`--fm-safe-left`/`--fm-safe-right` 非零；即便按 Q12 竖屏先行，本场景也应在验收矩阵登记）。
- 键盘弹出。
- WebHome 页面滚动。
- WebHome 顶部搜索框、设置按钮、底部主按钮。
- 从 WebHome 切到原生设置、返回首页。
- 沉浸模式进入/退出。
- WebHome JS 报错或页面空白时恢复。

验收结果应满足：

- 背景视觉上进入状态栏和底部系统区域。
- 刘海/挖孔设备上背景延伸进 cutout 区域，状态栏一带无黑边（依赖 TD-12）。
- 键盘弹出时输入框可见，且底部不出现双重垫高（依赖 TD-13 的避让路径决议）。
- 顶部/底部可点击按钮不被遮挡。
- 系统状态栏图标在浅/深背景下可读。
- 三键导航下底部按钮不被虚拟键遮挡。
- 手势导航下底部按钮不与 Home 手势冲突。
- 左右边缘主交互不和返回手势严重冲突。
- 用户始终能通过返回键或原生恢复入口退出沉浸。
- WebHome 没有安全区适配时，App 能退回 `normal` 或显示原生恢复入口。

## 10. 最终推荐形态

用户体验最佳、实现也相对优雅的形态是：

1. App 提供稳定的 WebHome chrome runtime：`normal/edge/immersive`。
2. WebHome 默认使用 `edge`，视觉全屏但交互控件避让。
3. App 注入真实安全区和手势区，WebHome 不猜设备。
4. WebHome 自绘顶部/底部主操作区，调用受控 Native API。
5. 原生保留恢复入口和高风险功能确认。
6. `immersive` 只作为用户主动开启的专注模式。

这套方案兼顾：

- 视觉融合：WebHome 背景覆盖整屏。
- 可操作性：按钮避开系统保护区。
- 可靠性：WebHome 异常时原生可恢复。
- 兼容性：不依赖单一 WebView 版本的 `env()` 行为。
- 可演进性：未来 Android 15/16 edge-to-edge 强制行为下仍然符合平台方向。

## 11. 搜索校准后的定案

结合 Android 官方、Chrome Android、WebView、Now in Android、Ionic、React Native edge-to-edge 社区资料后，方案需要坚持以下边界。

> 本章是全文的最终定案。前文 §5（推荐产品体验）、§6（推荐技术方案）与本章表述如有出入，一律以本章为准；后续修订也应只改本章并在前文回链，避免两处漂移。

### 11.1 最优雅的原生实现边界

不要继续把状态分散在 `HomeActivity.setNavigationVisible()`、`VodFragment.setWebFullscreen()`、`HomeWebController.injectViewport()` 和 `HomeWebBridge.setToolbar()` 里各管一段。建议新增一个单一职责的 WebHome chrome runtime：

```text
WebHomeChromeController
  - currentMode: normal | edge | immersive
  - currentInsets: InsetsState
  - applyMode(mode, options)
  - restore()
  - updateInsets(insets)
  - dispatchViewportToWeb()
```

职责边界：

- `HomeActivity` 只负责窗口级系统栏、底部原生导航可见性、返回键兜底。
- `VodFragment` 只负责 WebHome 容器和 Vod 原生内容之间的显示切换。
- `HomeWebController` 只负责向 WebView 注入 `fmviewport`、CSS 变量和 SDK。
- `HomeWebBridge` 只负责解析 JS 调用，转发给 chrome runtime。

这样可以避免后续出现“WebHome 以为是 edge，Activity 实际隐藏了系统栏，Fragment 又把底部导航加回来了”的状态撕裂。

### 11.2 默认模式修订

新 WebHome 模板和新版 App 默认使用 `edge`。现有 HTML 可以迁移，因此不需要为了保留旧 `--fm-safe-bottom=0` 契约而阻塞真实 insets 注入。

定案：

- 保留 `fm.ui.setToolbar(false)` 的现有行为，作为 legacy immersive/fullscreen API。
- 新增 `fm.ui.setChrome({ mode: "edge" })` 作为运行时切换 API。
- 新增 Native 可预读默认模式字段，例如站点级 `chromeMode: "edge"` 或 `webHomeChrome: { "mode": "edge" }`，并在 `HomeWebController.load(site)` 显示 WebView 前应用。
- README 和 WebHome 扩展文档把 `setToolbar` 标为 legacy chrome API。
- HTML 内启动脚本调用 `setChrome({ mode: "edge" })` 只能作为兜底或运行时切换，不能作为默认启用 edge 的唯一机制；否则当前 `onPageFinished` 后注入 SDK 的时序会导致首帧按 normal 布局绘制，再跳到 edge。
- 内置 demo 和模板迁移到新版 safe area 写法后，App 可以直接注入真实 `--fm-safe-*`，不再需要 legacy gate。

### 11.3 底部操作区定案

Chrome Android 的 edge-to-edge 文档特别指出，底部固定元素如果直接 `bottom: 0`，会在动态底部栏/手势导航收起后被导航区域遮挡；单纯用动态 `padding-bottom: safe-area-inset-bottom` 又可能造成布局抖动。

WebHome 底部栏建议采用固定操作层 + 延伸背景层：

```text
bottom-bleed-background
  top: auto
  bottom: 0
  height: actionBarHeight + safeBottomMax

bottom-actions
  bottom: safeBottom
  height: actionBarHeight
```

在项目自定义变量里，对应为：

- `--fm-safe-bottom`：当前底部安全距离。
- `--fm-safe-bottom-max`：本次窗口生命周期可观察到的最大底部安全距离，或者原生估算值。
- `--fm-bottom-action-height`：WebHome 模板固定操作区高度。

如果首期不实现 `safeBottomMax`，也应先把按钮层放在 `safeBottom` 上方，背景层单独铺到底部，不能把按钮直接放到设备底边。

### 11.4 顶部状态栏定案

顶部不需要像底部一样复杂，但必须保证：

- 状态栏背后由 WebHome 背景覆盖。
- 搜索框、设置、返回等点击控件从 `safeTop + 8dp/12dp` 开始。
- 状态栏图标明暗必须可控，不能只固定 dark/light。
- 对复杂背景图，WebHome 应能请求原生顶部 scrim，例如 `top: "#66000000"`。

系统栏图标明暗推荐 API：

```js
fm.ui.setChrome({
  mode: "edge",
  statusBarStyle: "light" | "dark" | "auto",
  navigationBarStyle: "light" | "dark" | "auto"
})
```

`auto` 首期可以只跟随 App 深浅色主题，不要做 WebView 像素采样。

命名和版本边界：

- `statusBarStyle/navigationBarStyle` 建议按**图标颜色**定义：`light` = 浅色图标，适合深色背景；`dark` = 深色图标，适合浅色背景；`auto` = 首期跟随 App 主题。
- 状态栏深色图标能力依赖 Android M/API 23+；导航栏深色图标能力依赖 Android O/API 26+。低版本无法可靠切换时，App 应退回浅色图标并增加 scrim/保护层，而不是承诺完全可控。
- 当前 `BaseActivity` 用 `SystemBarStyle.dark(TRANSPARENT)` 固定浅色图标；落地 `setChrome` 时应改为 `WindowInsetsControllerCompat.setAppearanceLightStatusBars/NavigationBars`，并在低版本分支里明确 fallback。

### 11.5 恢复入口定案

恢复不能完全交给 WebHome。最终推荐：

- `edge` 模式：默认不显示原生悬浮恢复按钮，但返回键可以恢复到 `normal` 或按既有页面返回逻辑处理。
- `immersive` 模式：必须显示原生微型恢复入口，位置使用原生 insets 放在安全区内。
- WebHome 可声明 `restoreAffordance: "web"` 表示自己画了恢复按钮，但原生仍需保留返回键恢复。
- WebHome JS 异常、页面空白、加载超时后，原生自动露出恢复入口。

恢复优先级：

1. 原生返回键退出 `immersive`。
2. 原生悬浮恢复入口退出 `immersive`。
3. Android 边缘滑动临时显示系统栏。
4. WebHome 自绘按钮调用 `fm.ui.restoreChrome()`。

### 11.6 Native API 开放定案

不是把所有原生按钮能力一次性开放给 WebHome，而是拆成两类：

基础导航/展示能力，允许 WebHome 直接调用：

- 搜索。
- 历史。
- 收藏/收藏页。
- 直播。
- 设置页。
- 切换主 Tab。
- 恢复 chrome。
- 查询 viewport/safe area。

高风险能力，必须原生确认或白名单：

- 文件管理。
- 登录态路径管理和迁移。
- 配置删除。
- 缓存清理。
- Cookie/账号相关操作。
- 本地服务开关。

这样既能让 WebHome 自己做漂亮的融合操作区，又不会让任意页面拥有过大的本地控制权。

### 11.7 最小优雅实现路径

如果只做一版最小但不返工的实现，推荐顺序是：

1. `InsetsState`：先把真实安全区和手势区打通。
2. `fmviewport v2`：注入 CSS 变量和事件，WebHome 侧可立即适配。
3. `ChromeMode.edge`：让 WebHome 视觉铺满，但系统栏仍显示。
4. Native 默认模式预应用：从站点 `chromeMode` / `webHomeChrome` 或 App 设置读取默认模式，在 WebView 首帧前应用。
5. `setChrome/restoreChrome`：替代新模板里的 `setToolbar`，用于运行时切换。
6. `immersive` 恢复兜底：补原生小入口和返回键优先恢复。

不要首期做：

- WebView 像素采样自动配色。
- 全量原生功能开放。
- 把所有 Activity 一次性改 edge-to-edge。
- 大规模重构首页导航结构。

这条路径能在当前代码基础上增量推进，同时和 Android 15 之后的平台方向保持一致。

## 12. 结合当前代码的落地复核

### 12.1 手机端底部按钮

手机端首页底部导航定义在：

- `app/src/mobile/res/menu/menu_nav.xml`

第一个按钮是：

- id：`R.id.vod`
- 标题：`@string/nav_vod`
- 简体中文：`点播`
- 语义：回到点播首页，也就是 `HomeActivity.change(0)` / `mManager.change(0)`。

当前手机端底部导航实际只有三个入口：

| 顺序 | id | 文案 | 当前行为 |
| --- | --- | --- | --- |
| 1 | `R.id.vod` | 点播 | 切回 `VodFragment` |
| 2 | `R.id.live` | 直播 | 打开 `LiveActivity`，仅有直播源时可见 |
| 3 | `R.id.setting` | 设置 | 切到 `SettingFragment` |

因此 WebHome 不需要拿到“全部原生按钮”的控制权。最小必要 API 应围绕这些常用入口和 WebHome 已有能力展开：

- 回到点播首页：建议新增 `fongmi.app.openVod()`（`fm.openVod`），语义是退出 WebHome chrome 并显示原生点播首页，对齐已有 `openSetting`。
- 打开直播：已有 `fongmi.app.openLive()`（`fm.openLive`）。
- 打开设置：已有 `fongmi.app.openSetting()`（`fm.openSetting`）。
- 打开收藏：已有 `fongmi.app.openKeep()`（`fm.openKeep`）。
- 打开最近观看：已有 `fongmi.app.history()`（`fm.history`）。
- 搜索：已有 `fongmi.app.search()`（`fm.search`）。
- 页面后退/刷新：已有 `fongmi.navigation.back()` / `fongmi.navigation.reload()`（短别名 `fm.back` / `fm.reload`）。

不建议为了自绘 UI 一次性开放所有原生功能。文件管理、登录态、配置删除、缓存清理、Cookie 等仍应保留原生确认或白名单。

### 12.2 手机端当前实现的关键约束

手机端已有一部分能力，但不能直接拿现有 `setToolbar(false)` 当作 `edge` 模式：

- `BaseActivity` 已经 `EdgeToEdge.enable(...)`，系统栏颜色是透明的。
- `activity_home.xml` 根节点仍是 `fitsSystemWindows="true"`。
- `HomeActivity.setNavigationVisible(false)` 会调用 `setWebHomeFullscreen(true)`。
- `setWebHomeFullscreen(true)` 会把 root `fitsSystemWindows(false)`，然后调用 `Util.hideSystemUI(this)`。
- `VodFragment.setWebFullscreen(true)` 会隐藏 AppBar、去掉 WebView 顶部 margin、隐藏底部导航。
- `HomeWebController.injectViewport()` 目前只注入宽高和硬编码 `--fm-safe-bottom: 0px`。

这意味着当前代码把三件事绑在一起了：

1. 隐藏 AppBar。
2. 隐藏底部导航。
3. 隐藏系统状态栏/导航栏。

而推荐的 `edge` 模式只需要前两件，不应该默认隐藏系统栏。首期实现必须先拆开：

```text
hideNativeChrome: 隐藏 AppBar / BottomNavigation / top margin
drawBehindSystemBars: WebView/root 允许画到系统栏后面
hideSystemBars: 仅 immersive 模式启用
```

否则会把“视觉融合模式”做成“系统沉浸模式”，导致顶部/底部按钮更容易和系统保护区域冲突。

### 12.3 手机端更贴合项目的实现建议

更贴合当前代码的最小改造路径：

1. 保留现有 `setToolbar(false)` 语义，继续表示旧的全屏/沉浸隐藏。
2. 新增 `setChrome({ mode: "edge" })`，不要复用 `setNavigationVisible(false)` 的现有实现。
3. 新增 Native 默认模式预应用：站点 `chromeMode` / `webHomeChrome` 或 App 设置决定初始 mode，必须早于 WebView 首次显示。
4. 在 `HomeActivity` 增加独立方法控制底部导航可见性，但不触发 `Util.hideSystemUI()`。
5. 在 `VodFragment` 增加“WebHome edge”状态：隐藏 AppBar、WebView 顶部 margin 归零、原生列表/FAB 隐藏，但系统栏保留。
6. `HomeWebController` 接收原生 Insets，注入真实 `--fm-safe-*`。
7. WebHome 用自绘操作区替代原生 AppBar/BottomNavigation 的常用入口。

WebHome 自绘操作区不需要复刻原 App 顶部/底部位置。更好的体验可以更个性化：

- 搜索可以做成居中悬浮搜索胶囊。
- 点播/直播/收藏/历史/设置可以做成右下角浮动工具盘。
- 低频功能可以收进“更多”按钮、底部抽屉或侧边操作栏。
- 播放类入口可以贴近内容卡片，而不是固定在屏幕边缘。
- 恢复原生 UI 可以是小型浮动按钮、长按背景、返回键优先恢复，而不是占用底栏。

底线是：可点击控件必须在安全区内，背景和装饰可以延伸到屏幕边缘。

### 12.4 电视端当前实现

电视端 WebHome 和手机端不是同一个 UI 问题。

电视端相关代码：

- `app/src/leanback/java/com/fongmi/android/tv/ui/base/BaseActivity.java`
- `app/src/leanback/java/com/fongmi/android/tv/ui/activity/HomeActivity.java`
- `app/src/leanback/res/layout/activity_home.xml`

当前行为：

- `BaseActivity.onCreate()` 默认调用 `Util.hideSystemUI(this)`，电视端长期隐藏系统栏。
- `activity_home.xml` 是纵向布局：顶部 `toolbar`，下面 `typeRecycler`，再下面是 `progressLayout`。
- WebHome 的 `WebView` 是运行时创建并加到 `progressLayout` 里，不在顶部 `toolbar` 内。
- WebHome 加载成功后会隐藏 `typeRecycler` 和 `recycler`。
- `setToolbar(false)` 只影响电视端顶部 `toolbar`，不会额外隐藏系统栏。
- 顶部 `toolbar` 包含 logo、站点标题和时钟，padding top 是 24dp。
- 电视端没有手机端 BottomNavigationView，也没有底部虚拟按键遮挡问题。

因此，用户观察到“电视端 WebHome 主页只有顶部区域不是 HTML 范围”是符合代码结构的：WebView 默认只占 `progressLayout`，顶部 toolbar 仍由原生绘制。

### 12.5 电视端推荐方案

电视端不建议照搬手机端 `edge/immersive` 的底部安全区方案。更合适的是 `tv-overlay` 或 `tv-full`，但必须区分“隐藏 toolbar”和“WebView 真全屏覆盖”：

| 模式 | 用途 | 原生 toolbar | WebHome 范围 | 重点 |
| --- | --- | --- | --- | --- |
| `tv-normal` | 当前兼容 | 显示 | toolbar 下方 | 保持现状 |
| `tv-overlay` | 推荐默认增强 | 半透明/自动隐藏 | 近似全屏，可保留顶部信息层 | 原生只做轻量状态/时钟 |
| `tv-full` | WebHome 完全接管 | 隐藏 | 全屏 | WebHome 必须处理遥控器焦点和常用入口 |

当前代码下的临时兼容模式：

1. 电视端可保留现有 `setToolbar(false)`，让 WebHome 主动隐藏顶部 toolbar。
2. 这个能力只能命名为 legacy toolbar hidden 或 `tv-toolbar-hidden`，不能宣称为 `tv-full`。因为 WebView 仍是加到 `progressLayout` 内，父布局不是覆盖全屏的容器。
3. 若首期不改布局，`setChrome({ mode: "tv-full" })` 不应映射到 `setToolbar(false)`；可以先不开放 `tv-full`，或让它返回不支持/降级到 `tv-toolbar-hidden` 并在 `fmviewport.chromeMode` 中如实反馈。

真正 `tv-overlay` / `tv-full` 的落地要求：

1. 将 `activity_home.xml` 根结构改为 `FrameLayout`，或新增覆盖全屏的 `webOverlay` 容器。
2. WebView 放入全屏 overlay 容器，toolbar/typeRecycler/recycler 作为可隐藏或半透明的原生层。
3. `tv-overlay` 保留轻量原生状态层；`tv-full` 隐藏原生层，让 WebHome 完全接管首屏。
4. 注入 TV 安全边距变量，例如：
   - `--fm-tv-safe-left: 48dp`
   - `--fm-tv-safe-right: 48dp`
   - `--fm-tv-safe-top: 28dp`
   - `--fm-tv-safe-bottom: 28dp`
5. WebHome 背景允许全屏铺满，但主要文字、焦点卡片、按钮不要贴边。
6. WebHome 电视模板必须提供 D-pad 可达的焦点顺序和明显焦点态。
7. 注意 WebView 内 HTML 的空间导航（spatial navigation）默认不可用：`tv-full` 下焦点管理要靠页面 JS 自行实现按键路由（监听方向键/确认键并维护焦点状态），这是 TV 模板的隐性工作量，排期时单独计入。

电视端常用入口可以对应现有首页功能行：

- 直播：`home_live`，已有 `fongmi.app.openLive()`（`fm.openLive`）。
- 搜索：`home_search`，已有 `fongmi.app.search()`（`fm.search`）；长按搜索限定当前站点的能力暂不必首期开放。
- 收藏：`home_keep`，已有 `fongmi.app.openKeep()`（`fm.openKeep`）。
- 设置：`home_setting`，已有 `fongmi.app.openSetting()`（`fm.openSetting`）。
- 推送：`home_push`，电视端有 `PushActivity`，但 WebBridge 目前没有 API；可选新增 `fongmi.app.openPush()`（`fm.openPush`），不属于首期必需。
- 站点切换/刷新/重新加载配置：当前在 `CustomTitleView` 上通过点击、方向键和长按实现。WebHome 若要接管顶部，后续可单独开放 `fongmi.site.select()`、`fongmi.site.next()`、`fongmi.site.prev()`、`fongmi.config.reload()`。注意短别名不能写成 `fm.site.*` / `fm.config.reload`（`fm.site`、`fm.config` 已是可调用函数，`fm.reload` 已被页面重载占用，见 §6.0）；如需短别名建议用 `fm.selectSite` / `fm.reloadConfig` 等独立名字。这些不应和手机端底部导航 API 混在一起。

### 12.6 电视端体验设计借鉴

Android TV 官方设计强调：

- 背景可以填满全屏，不要把背景裁到 overscan safe area 内。
- 关键内容和可交互元素建议保留约 5% 安全边距。
- 现代 TV 可按 960x540 设计基准换算，左右约 48dp，顶部/底部约 24dp-28dp。
- 电视主要通过 D-pad 操作，所有可交互元素必须能被焦点访问，方向移动要可预测。
- 焦点态应清晰，可使用缩放、描边、发光、颜色变化。
- TV 是 10-foot UI，文本和按钮要更大、更少、更直接。
- 影视首页适合使用沉浸式列表：聚焦卡片时背景、标题、描述渐进展示，并用 cinematic scrim 保证可读性。

参考：

- https://developer.android.com/design/ui/tv/guides/styles/layouts
- https://developer.android.com/design/ui/tv/guides/foundations/navigation-on-tv
- https://developer.android.com/design/ui/tv/guides/styles/focus-system
- https://developer.android.com/design/ui/tv/guides/foundations/design-for-tv
- https://developer.android.com/design/ui/tv/guides/components/immersive-list

对本项目的启发：

- 电视端 WebHome 不必做手机式底部 Dock，更适合“全屏影视背景 + 顶部轻信息 + 焦点卡片/功能行”。
- 原生时钟/站点标题可以变成 WebHome 自绘的一部分，也可以做成自动淡出的原生 overlay。
- WebHome 的入口可以放在左侧 rail、顶部 command strip、首屏功能卡片行，或者聚焦内容旁边的操作按钮，不必放回原来的顶部/底部位置。
- 返回键应优先从 WebHome 子页面回退；Menu 键可保留打开站点选择，这是电视遥控器上比较自然的兜底入口。

### 12.7 修订后的最终判断

手机端和电视端应共享底层能力，但不共享同一个视觉布局：

- 共享：`setChrome` API、`fmviewport`、常用 App action、错误恢复、权限边界。
- 手机端重点：系统栏/手势区安全区、底部虚拟键避让、触控恢复入口。
- 电视端重点：顶部 toolbar 接管、overscan 安全边距、D-pad 焦点、10-foot UI。

最现实的版本规划：

1. 先做 `fmviewport v2`，同时给手机端和电视端注入不同安全区。
2. 手机端新增 `edge`，拆开隐藏原生 UI 和隐藏系统栏。
3. 电视端首期若不改布局，只保留 legacy `setToolbar(false)` / `tv-toolbar-hidden`；真正 `tv-full` 必须随 TD-8 的 overlay 化一起做。
4. 补常用 action：手机端新增 `openVod`（对齐已有 `openSetting`），电视端可选 `openPush`。
5. WebHome 模板分别做 mobile/tv 两套布局策略，而不是一套 CSS 硬适配。

## 13. 技术债梳理与重构建议

下列条目均以当前代码为依据，是落地本方案前需要正视的技术债。每条给出现状（含 `file:line`）、问题、最佳实践改造方向。优先级 P0 = 不重构会直接做歪 edge 模式，P1 = 会造成体验/正确性问题，P2 = 长期可维护性。

### TD-1（P0）Chrome 状态分散 + 双布尔真相源 + 回调环路

- 现状：`HomeActivity.webHomeFullscreen`（`HomeActivity.java:59`）与 `VodFragment.mWebFullscreen`（`VodFragment.java:68`）两个布尔各存一份重叠状态；调用链 `setToolbar(false)` → `VodFragment.setWebFullscreen(true)`（`:475`）→ `HomeActivity.setNavigationVisible(false)`（`:498`）→ `setWebHomeFullscreen(true)`（`:181`）→ `Util.hideSystemUI`，一条 JS 调用穿透 3 个类、触发 5 个副作用，且 `setWebFullscreen` 与 `setNavigationVisible` 互相回调。
- 问题：没有单一真相源，容易出现"WebHome 以为 edge，Activity 已隐藏系统栏，Fragment 又把导航加回来"的撕裂（方案 §11.1 已预警）。
- 改造：落地方案 §11.1 的 `WebHomeChromeController`，持有唯一 `mode` 枚举与 `InsetsState`；Activity/Fragment 退化为"被动视图操作者"，不再各自存模式布尔，不再互相回调。此外 chrome runtime 的 `mode` 必须可在 Activity 重建（旋转、低内存回收）后恢复：写入 `savedInstanceState` 或从站点配置 / Native 默认模式重推导，不能只存内存字段——这与 Q11 讨论的用户偏好持久化是两件事，重建恢复是硬要求。

### TD-2（P0）`Util.hideSystemUI` 混用已废弃的 `FLAG_FULLSCREEN`

- 现状：`Util.hideSystemUI(Window)` 既用 `WindowInsetsControllerCompat.hide(systemBars())`，又叠加 `window.addFlags(FLAG_FULLSCREEN)`（`Util.java` hideSystemUI 体）。
- 问题：`FLAG_FULLSCREEN` 自 API 30 起废弃，且会把窗口拉回 legacy 全屏行为，与 edge-to-edge 的 inset 派发相冲突。edge 模式绝不能走这条；immersive 也应改纯 `WindowInsetsController` 实现。
- 改造：移除 `FLAG_FULLSCREEN`，immersive 仅用 `controller.hide(systemBars())` + `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`；edge 模式根本不调用 `hideSystemUI`。注意该方法被多处复用（见 TD 关联与 §14 Q10），需回归其它调用方。

### TD-3（P0）`activity_home` 根 `fitsSystemWindows="true"` 与 EdgeToEdge 并存，靠二值翻转

- 现状：mobile `activity_home.xml:6` 根 `fitsSystemWindows="true"`，使根 view 把 systemBars 消费成 padding（实际非 edge-to-edge）；只有 `setWebHomeFullscreen` 才动态翻成 `false`（`HomeActivity.java:197`）。
- 问题：没有统一的 insets 分发点，edge/normal 只能整窗二值切换，无法"背景铺满 + 控件避让"。
- 改造：移除根 `fitsSystemWindows`，改用 `ViewCompat.setOnApplyWindowInsetsListener` 在一处计算 insets，按 mode 分发：背景层 0 padding、底部导航/控件按需消费。这是 edge 模式的地基。

### TD-4（P1）insets 信息存在多套并行来源

- 现状：`BaseActivity.setPadding()`（mobile `BaseActivity.java:82-92`）单独读 `DisplayCutout`（`ResUtil.getDisplay`）；另有 EdgeToEdge；方案还要再加 `InsetsState`。
- 问题：三套来源并存，正是方案自己反对的"状态分散"，cutout 值容易与新 InsetsState 不一致。
- 改造：新 `InsetsState` 作为唯一来源，`setPadding()` 的 cutout 逻辑并入或废弃。

### TD-5（P1）`SystemBarStyle.dark()` 硬编码，系统栏图标明暗不可控

- 现状：`BaseActivity.java:108` 写死 `SystemBarStyle.dark(TRANSPARENT)`（深色样式 = 浅色图标）。
- 问题：浅色 WebHome 背景下状态栏图标看不清，直接阻碍 edge 可读性（方案 §11.4 的动机）。
- 改造：用 `WindowInsetsControllerCompat.setAppearanceLightStatusBars/NavigationBars` 动态控制，`setChrome` 的 `statusBarStyle/navigationBarStyle` 落到这里；`auto` 首期跟随 App 深浅主题。

### TD-6（P1）`injectViewport` 抖动 + 硬编码 `safeBottom:0` + 仅监听 layout

- 现状：`HomeWebController.injectViewport()`（`:636-647`）挂在 `addOnLayoutChangeListener`（`:109`），每次 layout 变化都 `evaluateJavascript`；`--fm-safe-bottom` 硬编码 `0`；无 diff；不监听 WindowInsets。
- 问题：JS 反复执行易触发 layout thrashing；安全区始终为 0。
- 改造：以 `OnApplyWindowInsetsListener` 作为安全区数据源；注入前 diff（值未变不发）；宽高 + 安全区合并一次注入；扩展 `fmviewport v2` 字段（方案 §6.2）。

### TD-7（P1）`BottomNavigationView` 固定 56dp，无 navigationBar/手势区适配

- 现状：`activity_home.xml:14-23` 导航栏固定 `56dp`、背景透明；normal 模式靠根 `fitsSystemWindows` 整体上移规避手势条。
- 问题：一旦进入 edge（根不再 fit），导航栏会与系统手势条/三键栏重叠。
- 改造：edge 模式给 `navigation` 加底部 `systemBars`/`systemGestures` inset padding，或将其改为浮于内容之上的 overlay。

### TD-8（P0，TV 端工作量最大）TV `activity_home` 为垂直 `LinearLayout`，WebView 无法覆盖顶部 toolbar

- 现状：leanback `activity_home.xml` 根是垂直 `LinearLayoutCompat`（toolbar → typeRecycler → progressLayout），WebView 运行时被 `addView` 进 `progressLayout`（`HomeActivity.java:239`）。
- 问题：结构上 WebHome 永远盖不住顶部 toolbar——这正是用户观察到"TV 端 WebHome 顶部区域不是 HTML 范围"的根因（方案 §12.4 已点出现象）。`tv-overlay`/`tv-full` 要让 WebHome 接管顶部，必须改层级。
- 改造：根改为 `FrameLayout`（或新增覆盖全屏的 `webOverlay` 容器）承载 WebView，toolbar 作为可隐藏/半透明的 overlay 浮在其上。这是 TV 端唯一的结构性改造，回归风险集中在焦点顺序，需重点测 D-pad。

### TD-9（P1）`onWindowFocusChanged` 无 mode 感知

- 现状：mobile `HomeActivity.java:251-254` `hasFocus && webHomeFullscreen` 即 `hideSystemUI`。
- 问题：引入 edge 后，edge 不应隐藏系统栏；失焦再获焦会误隐藏。
- 改造：按 chrome mode 分支，仅 immersive 才在重新获焦时重隐藏。

### TD-10（P1）返回链没有 chrome 状态接入点

- 现状：`BaseActivity` 的 `OnBackPressedCallback` → `HomeActivity.onBackInvoked()`（`:264`）→ `VodFragment.canBack()`（`:397`）→ `HomeWebController.handleBack()`。
- 问题：immersive "第一次返回先恢复 chrome" 没有可判断的状态位。
- 改造：`WebHomeChromeController` 暴露 `isImmersive()`，返回链最前面优先消费"退出沉浸"，再走既有 WebView/页面返回逻辑。

### TD-11（P0，外部资料佐证）WebView ghost padding / 双重内边距风险

- 背景：Android 官方"understand window insets in WebView"指出，M136+ WebView 在与系统 UI 重叠时会把 `displayCutout()`/`systemBars()` 自动转成内部 padding；若原生再用 `WindowInsetsCompat.CONSUMED` 消费 insets，会残留 ghost padding。
- 问题：edge 模式 WebView 铺满压在系统栏下，可能"WebView 自己 pad 一遍 + 原生注入 `--fm-safe-*` 再缩一遍"造成双重内缩。CSS 侧 `max(--fm-safe-*, env())` 只能去重变量，挡不住 WebView 物理 padding。
- 改造（硬约束）：edge 下 WebView 容器不再被原生 padding；安全区只走 JS 注入驱动布局；若必须消费 insets，用 `WindowInsetsCompat.Builder` 把已处理类型置零下发，而非 `CONSUMED`。落地前需在目标 WebView 版本实测是否存在自动 padding。

### TD-12（P0）`layoutInDisplayCutoutMode` 未确认，刘海区延伸没有保障

- 现状：全工程没有显式设置 `layoutInDisplayCutoutMode`；mobile `BaseActivity` 仅调用 `EdgeToEdge.enable(...)`。窗口能否把内容画进刘海/挖孔区域由 `layoutInDisplayCutoutMode`（需 `shortEdges` 或 `always`）决定，与 `fitsSystemWindows`/insets 分发是两套独立机制。
- 问题：androidx `EdgeToEdge.enable()` 是否自动设置 `shortEdges` 取决于 androidx.activity 版本，不能凭文档假设。若未设置，刘海机上 edge 模式的状态栏区域会留黑边/留白，§9 验收的“有刘海/挖孔设备”场景直接失败——这是 edge 模式“背景画进状态栏后面”的前提条件。
- 改造：真机实测当前依赖版本的实际行为（与 Q5 的 WebView 自动 padding 实测同一轮做，见 Q14）；若未生效，在 edge/immersive 应用时由 chrome runtime 显式设置 `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`，回到 `normal` 时恢复默认。

### TD-13（P1）IME 双重避让：WebView 自动 viewport 缩放与 `--fm-keyboard-bottom` 注入叠加

- 背景：Android 官方 WebView insets 文档说明，M139+ 的 WebView 会自行通过 visual viewport resizing 做键盘避让（仅对 WebView 底部生效）。
- 问题：§6.2 若注入 `--fm-keyboard-bottom` 让页面再避让一次，会出现与 TD-11 同构的双重内缩——WebView 已缩 viewport，页面又按变量垫一层 padding。TD-11 只覆盖 `displayCutout/systemBars`，IME 是独立缺口。
- 改造：首期不注入 `--fm-keyboard-bottom`（字段名保留占位，§6.2 已注明），键盘避让交给 WebView M139 行为并统一各 Activity 的 `windowSoftInputMode`；是否需要原生注入按 Q15 真机实测结论定。低于 M139 的 WebView 上键盘行为如何兜底，一并纳入实测范围。

### 重构落地顺序建议

P0（地基，先做）：TD-3 统一 insets 分发 → TD-1 chrome runtime 收口（含重建恢复） → TD-2 拆 `FLAG_FULLSCREEN` → TD-11 容器不重复 padding → TD-12 cutout mode 实测/显式设置。
P1（edge 体验）：TD-6 注入 v2 + 防抖 → TD-5 图标明暗 → TD-7 导航栏避让 → TD-9/TD-10 mode 感知的返回与焦点 → TD-13 IME 避让路径定型。
TV 专项：TD-8 根容器 overlay 化（独立排期，回归面大）。
P2（清理）：TD-4 合并 cutout 来源。

## 14. 待澄清 / 待决策问题汇总

以下问题需要统一拍板后再进入实现，按影响面排序。括号内为方案当前倾向。

| # | 问题 | 方案当前倾向 | 影响 |
| --- | --- | --- | --- |
| Q1 | 旧 `fm.ui.setToolbar(false)` 语义是否保留为 legacy（= immersive）？ | 保留不改语义 | 存量 WebHome 脚本兼容性 |
| Q2 | edge 是否作为新模板默认？默认模式从哪里预读？ | 定案：新版模板默认 edge；由站点 `chromeMode` / `webHomeChrome` 或 App 设置在 WebView 首帧前预应用；HTML 内 `setChrome()` 只做运行时切换 | 首帧稳定性、配置字段落地 |
| Q3 | 首期是否实现 `safe-area-max-inset` / `--fm-safe-bottom-max`？ | 首期仅"按钮停 `safeBottom` 上方 + 背景 bleed"，max 留后续 | 底栏随手势条滑动的顺滑度 |
| Q4 | env() vs `--fm-*` 主次，是否强制 WebHome 模板写 `viewport-fit=cover`？ | `--fm-*` 为主、env() 为辅；模板应声明 cover | 第三方/存量页面在 Android 上 env 常为 0，决定能否退守自注入 |
| Q5 | 目标 WebView 版本是否实测存在自动 padding（TD-11）？据此决定 edge 容器是否完全不 pad | 默认"完全不 pad，只靠 JS 注入" | 双重内缩 bug |
| Q6 | 系统栏图标 `auto` 首期实现：跟随 App 深浅主题，不做像素采样？ | 是 | 实现复杂度 vs 复杂背景可读性 |
| Q7 | 恢复入口策略：edge 是否显示原生悬浮恢复？是否需要 JS 异常/加载超时自动露出入口？阈值多少？ | edge 仅返回键恢复；immersive 必显原生入口；超时自动露出（阈值待定） | 防止用户被困 |
| Q8 | TV 端首期范围：是否立刻把 `activity_home` 改 `FrameLayout` overlay（真 tv-overlay/tv-full）？ | 定案：隐藏 toolbar 只能叫 legacy / `tv-toolbar-hidden`；真正 `tv-full` 必须随 overlay 结构改造一起做，不把 `setToolbar(false)` 冒充 `tv-full` | TD-8 工作量与回归风险 |
| Q9 | 高风险 action 白名单判定来源：站点 `key` 白名单 / 配置声明 / 可信标志从何而来？ | 待定 | 安全边界可落地性 |
| Q10 | 移除 `FLAG_FULLSCREEN`（TD-2）影响面：`hideSystemUI` 还被 `ScanActivity`/`LiveActivity`/`VideoActivity`/leanback `BaseActivity` 等复用，是否有调用方依赖其 legacy 全屏行为？ | 需逐一回归 | 播放/扫码等全屏场景 |
| Q11 | `setChrome` 是否需要持久化（如用户"首页默认沉浸"偏好）？存 `Setting` 还是 `fm.cache`？ | 待定；注意这只指用户偏好——Activity 重建后的 mode 恢复是 TD-1 的硬要求，不在待定范围 | 偏好记忆 |
| Q12 | 横屏 / 折叠屏 / 平板：首期是否纳入，还是竖屏先行？ | 竖屏先行 | 验收矩阵范围 |
| Q13 | `--fm-safe-bottom` 从硬编码 `0` 改为注入真实值是**注入契约的破坏性变更**（见 §15）。是否接受破坏旧 HTML？ | 定案：接受。新版 App 直接注入真实 `--fm-safe-*`；旧 HTML 迁移到 `max(var(--fm-safe-*), env(...))` 去重写法 | 所有存量 WebHome 的迁移成本 |
| Q14 | 当前 androidx.activity 版本的 `EdgeToEdge.enable()` 是否已设置 `layoutInDisplayCutoutMode = shortEdges`（TD-12）？ | 与 Q5 同一轮真机实测；未生效则在 edge/immersive 由 chrome runtime 显式设置 | 刘海机上 edge 是否留黑边，§9 验收前提 |
| Q15 | IME 避让走哪条路（TD-13）：依赖 WebView M139+ 的 visual viewport resize，还是原生注入 `--fm-keyboard-bottom`？低于 M139 如何兜底？ | 首期依赖 WebView 行为、不注入该变量；真机实测后再定 | 键盘场景双重内缩 / 输入框遮挡 |

> 备注：Q3/Q4 互相关联——选择以 `--fm-*` 为主能保证第三方页面在 Android 上拿到非零安全区（env 在 Android 常为 0 且需 `viewport-fit=cover`），但会放弃 Chrome 仅对 `calc(env(safe-area-inset-bottom, …) ± …)` 字面量启用的 chin-slide fast-path。两者取舍需在 Q3 一并定。

## 15. 对现有 WebHome 页面的影响评估（实施前审计快照）

`demo/nostr.html`（约 1.35 万行）是一个完整的真实 WebHome 页面，能代表存量页面的写法。以下 15.1-15.4 保留 2026-06-11 实施前审计快照，用于解释为什么真实 `--fm-safe-*` 注入会破坏旧页面。

2026-06-13 实施回填：当前 `demo/nostr.html` 已完成迁移，包含 `viewport-fit=cover`、`setChrome({ mode: "edge" | "immersive" })`、`fm.ui.getViewport()` 兜底，以及 `max(var(--fm-safe-*), env(...))` 去重写法。15.4 的迁移清单对当前 demo 已完成；后续新增 WebHome 示例仍需按同一清单审查。

### 15.1 现状（为什么今天能正常工作）

- 已声明 `<meta name="viewport" content="... viewport-fit=cover">`（`:5`），env() 在支持的 WebView 上有效。
- App 动作走**完整命名空间**：`window.fongmi.app.openLive()`（`:10004`）、`window.fongmi.app.openSetting()`（`:10023`），符合规范，**不受重命名影响**。
- 沉浸切换走 `fm.ui.setToolbar(visible)`（`:4654`），并有 `ui:{setToolbar:async()=>{}}` 兜底（`:4610`）。
- `fmviewport` 监听只读 `--fm-web-height` 设置 body 最小高（`:12641`）。
- 安全区只用到 `--fm-safe-bottom`（11 处 `var()` 引用 + 1 处 `:root` 定义），**没有用 `--fm-safe-top/left/right`**。
- 之所以今天不出问题：App 当前把 `--fm-safe-bottom` 硬编码为 `0px`（`HomeWebController.java:643`），覆盖了页面 `:root` 里的默认 `20px`（`:241`）。所以底部实际只由 env() 贡献。

### 15.2 破坏性影响（必须先改，否则上线即坏）

**B1（高）底部安全区会被双算。** 页面在 **10 处**用的是**相加**写法：

```css
padding: ... calc(18px + var(--fm-safe-bottom) + env(safe-area-inset-bottom));
```

涉及行：`:314`、`:1175`、`:1523`、`:2134`、`:2626`、`:2966`、`:3011`、`:3562`，以及两处 `bottom: max(Npx, calc(... + var(--fm-safe-bottom) + env(...)))`（`:3125`、`:3149`）。全页**没有任何一处**用 `max(var(--fm-safe-bottom), env(...))` 去重。

- 今天无害：注入值是 `0`，等于 `base + 0 + env`。
- 阶段1 一旦注入**真实** `--fm-safe-bottom`（如手势区 24px），而 edge 模式系统栏可见、`env(safe-area-inset-bottom)` 也≈24px → 每个底部元素被垫 **≈2 倍**，按钮浮高、留白过大。
- 修复：按方案 §6.2 改为去重写法
  ```css
  calc(18px + max(var(--fm-safe-bottom, 0px), env(safe-area-inset-bottom, 0px)))
  ```
- 另：`:3592` 的 `max-height: calc(var(--fm-web-height) - 86px - var(--fm-safe-bottom))` 是**单减**注入值（无 env），注入真实值后高度会少 24px——通常是想要的（预留底栏），确认即可，非必改。

> B1 直接印证 §14 Q13：把 `--fm-safe-bottom` 从恒 0 改成真实值，对所有"假设它是 0 而做加法"的存量页面都是破坏性变更。当前定案是接受该变化，并把 HTML 迁移作为新版 App 的同步工作项；不要再为 legacy 页面保留 `--fm-safe-bottom=0` 的分支契约。

### 15.3 建议改造（不改不坏，但拿不到 edge 收益或在 Android 上不稳）

- **R1（顶部安全区）** 顶部固定栏只用了 `env(safe-area-inset-top)`（`:337`、`:2182`、`:2641`、`:2980`、`:3059`），没有 `--fm-safe-top` 兜底。Android 上 env-top 可能为 0，edge 模式下顶栏可能压到状态栏。建议改 `max(var(--fm-safe-top, 0px), env(safe-area-inset-top, 0px))`，并要求阶段1 真的注入 `--fm-safe-top`（当前只注入 `--fm-safe-bottom` 且为 0）。
- **R2（启用 edge）** 页面要享受"背景铺到系统栏后"的融合，首选在站点配置或 App 设置里声明 Native 可预读的 `chromeMode: "edge"` / `webHomeChrome`，让 App 在 WebView 首帧前应用。HTML 内调用 `fm.ui.setChrome({ mode: "edge" })` 只能作为运行时兜底，不应作为默认启用 edge 的唯一机制。
- **R3（左右/手势区）** 仅 `:3148` 用了 `env(safe-area-inset-right)`。横向轮播/抽屉如需避让返回手势，可接入 §6.2 的 `--fm-gesture-*`，非必需。
- **R4（fmviewport v2）** 现有监听对新增字段无害（自动忽略）。可选增强：监听 `chromeMode`/安全区变化做重排。

### 15.4 该页迁移清单

1.（必须）把 10 处 `var(--fm-safe-bottom) + env(safe-area-inset-bottom)` 改为 `max(...)` 去重。
2.（建议）顶部固定栏补 `--fm-safe-top` 的 `max()` 兜底。
3.（必须）在站点配置、模板元数据或 App 设置中声明默认 `edge`，确保 Native 可预读；页面内 `setChrome({ mode: "edge" })` 只作兜底。
4.（可选）扩展 `fmviewport` 处理安全区/chrome 变化。
5.（回归）App 注入真实 insets 后，在手势导航与三键导航下复测所有底部栏。

### 15.5 当前 demo 范围

当前 `demo/` 目录只保留 `demo/nostr.html`，且该页面已按新版 safe area 契约迁移。后续如新增 WebHome 示例页面，也必须按 B1 排查安全区写法，不要只依赖当前样本页已经完成迁移。

## 16. 对《应用完整开发文档》的影响清单（2026-06-13 已回写）

本方案落地后，`docs/应用完整开发文档.md` 已同步回写 WebHome chrome mode、viewport/safe area、SDK API、命名规范和迁移说明。下表保留原评估项，作为后续审计索引。

| 位置 | 现状 | 实施后需要补/改 |
| --- | --- | --- |
| §5 点播站点配置（`homePage` / `webHome` 字段附近） | 已有 `chromeMode` / `webHomeChrome`，并说明启动期快照预应用 | 后续若新增用户级默认模式设置，再补设置优先级 |
| §16 SDK 总览 | 已新增 `fm.ui.setChrome`、`fm.ui.restoreChrome`、`fm.ui.getViewport`、`fm.openVod` | TV 端 `tv-overlay/tv-full` 真正实现前仍按当前降级说明 |
| `fm.ui.setToolbar` 语义说明 | 已标注 legacy chrome API，新 WebHome 使用 `setChrome` | 旧脚本兼容路径保留 |
| Chrome 模式章节 | 已新增 `normal/edge/immersive` 及 TV 降级模式说明 | 沉浸偏好持久化仍属 Q11 |
| 视口/安全区章节 | 已列 `fmviewport` 字段、`--fm-*` CSS 变量、`max(var(--fm-safe-*), env(...))` 去重规则 | IME 仍按 TD-13/Q15 后续真机结论调整 |
| 命名规范和迁移说明 | 已补短别名规则和存量 WebHome 迁移清单 | 若新增 `site/config` 写操作 API，继续按 §6.0 规则审查 |
| 全屏相关行为 | 已通过 chrome runtime 收口 WebHome 场景 | `Util.hideSystemUI` 其他 Activity 调用仍需按 Q10 独立回归 |
