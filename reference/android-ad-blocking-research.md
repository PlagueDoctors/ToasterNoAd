# Android 广告拦截方案参考

> 目录：`reference/`（参考资料，非项目代码）
> 用途：为 NoAd 的广告拦截能力实现提供技术选型依据
> 整理日期：2026-09-19
> 信息来源：公开网络资料与开源项目文档，已在各节标注出处

---

## 1. 结论速览

| 方案 | 拦截对象 | 免 Root | 核心机制 | 适配 NoAd |
|---|---|---|---|---|
| **无障碍服务模拟点击** | 开屏广告、弹窗广告 | ✅ | `AccessibilityService` 读取视图树 + 模拟点击 | ⭐ **最匹配** |
| DNS / Private DNS 过滤 | 网页广告、追踪域名 | ✅ | 域名解析层拦截 | ⚠️ 对 App 内广告无效 |
| 本地 VPN 流量过滤 | 网页广告 + 部分 App 广告 | ✅ | `VpnService` 建本地代理 | ⚠️ 与真实 VPN 冲突 |
| Hosts 修改 | 网页广告 | ❌ 需 Root | 改 `/etc/hosts` 重定向 | ❌ 门槛过高 |
| 应用层 Hook / Xposed | 任意 | ❌ 需 Root/框架 | 运行时修改方法 | ❌ 门槛过高 |

**对本项目的判断**：「自动关闭**弹窗**或**启动**广告」这一目标，在免 Root 前提下**只有无障碍服务方案可行**。其余方案解决的是「网页/网络请求层广告」，与 UI 弹窗拦截是不同问题域。

---

## 2. 主流产品技术剖析

### 2.1 李跳跳（MissLee）—— 标杆级参考

**定位**：单功能、极简、完全离线的开屏广告跳过工具。

**技术要点**（来源：百度百科词条、蓝点网、新绿资源网等公开报道）：

| 维度 | 实现 |
|---|---|
| 核心机制 | 基于 Android 无障碍服务，检测屏幕上的「跳过」按钮并模拟点击 |
| 权限需求 | 仅无障碍权限，**不联网**、不申请其他权限 |
| 体积 | 约 2.7–3 MB |
| 规则形式 | 内置规则 + 支持用户自定义 / 导入外部规则库（号称 2 万+ 条） |
| 识别策略 | 关键字匹配、控件 ID 匹配、位置记忆、倒计时识别（多层） |
| 定位特征 | **完全本地化运行，规则不上云** |
| 状态 | 2023-08 因律师函宣布无限期停止更新；2024-03 被 360 应用商店下架 |

**可直接借鉴的设计**：
1. **纯本地、零联网** —— 从根本上规避隐私质疑与云端封杀，是这类工具最重要的信任基础
2. **规则可扩展** —— 内置规则 + 用户导入，让社区贡献成为能力护城河
3. **极简权限面** —— 只申请无障碍，不碰其他敏感权限，降低用户心理门槛

**需要警惕的教训**：
- 触碰了商业利益，面临**不正当竞争**指控（被指影响其他 App 的广告变现）
- 被主流应用商店下架 → **无法通过正规渠道分发**
- 因此该类产品的分发与合规风险远高于技术风险

### 2.2 GKD —— 工程化最强的开源参考

**定位**：通用「自定义屏幕点击」框架，广告跳过只是其应用场景之一。

**开源协议**：GPL-3.0-only。**项目仅供学习交流，禁止商业或非法用途**。

**三要素架构**（来源：GitHub `gkd-kit/gkd`）：

```
高级选择器 + 订阅规则 + 快照审查
```

**1）高级选择器**：类 CSS 的选择器语法，可依据以下维度定位节点：

- `id`（resource-id）
- `text` / `desc`（contentDescription）
- `class`
- 属性值与**正则表达式**
- 层级关系（父子、兄弟）

相比「文本 contains '跳过'」这种朴素匹配，选择器的表达能力与稳定性显著更高。

**2）订阅规则**：项目**默认不携带任何规则**，需用户自行添加本地规则或通过订阅链接获取远程规则。规则以 JSON5 格式分发，社区已有多个第三方订阅（如 `AIsouler/GKD_subscription`、`Adpro-Team/GKD_subscription`），通过 npm registry 或 CDN 分发 JSON 文件。

**3）快照审查**：可视化查看当前界面的视图树与节点属性，用于调试和编写规则。这是规则编写效率的关键工具。

**实际模块划分**（从仓库结构观察）：

```
gkd/
├── gkd-app/          # 应用主体
├── gkd-selector/     # 选择器引擎（已重写）
├── gkd-db/           # 数据持久化
├── gkd-hidden-api/   # 隐藏 API 访问集中封装
└── buildSrc/
```

**值得直接学习的架构决策**：
- **选择器引擎独立成模块** —— 规则匹配是核心复杂度所在，值得隔离
- **隐藏 API 集中封装**（`gkd-hidden-api`）—— 把访问非公开 API 的代码收拢在一处，便于适配不同 Android 版本
- **规则与主体分离** —— 应用不带规则，规则通过订阅分发，规避了内置规则的法律与维护负担
- **明确的免责声明与 GPL 协议** —— 用协议与声明划清责任边界

### 2.3 SKIP —— 规则格式的实用范例

**定位**：专注开屏广告跳过的开源项目，规则以 YAML 配置。

**规则配置示例**（来源：项目文档）：

```yaml
- appName: 京东读书
  packageName: com.jd.app.reader
  skipBounds:
    - bound: 1243,176,1383,316
      activityName: com.jingdong.app.reader.logo.JdLogoActivity
      desc: 1440x3200分辨率下圆形开屏广告按钮
  skipIds:
    - id: com.jd.app.reader:id/mJumpBtn
      activityName: com.jingdong.app.reader.logo.JdLogoActivity
      desc: 开屏广告跳过按钮
```

**支持的三种匹配方式**：
1. `skipIds` —— 按控件 resource-id 精确匹配
2. `skipBounds` —— 按屏幕坐标区域匹配（需标注分辨率）
3. `skipTexts` —— 按按钮文本匹配

**关键设计特点**：规则按 **packageName + activityName** 绑定。

> 这一点很重要：开屏广告只出现在**特定的 Activity** 上（通常是 `SplashActivity` 或类似命名）。
> 用 `activityName` 限定规则生效范围，可以大幅降低误点风险，也减少无关事件的处理开销。

**其他借鉴点**：
- 调用 `getCurrentRootNode()` 拿根节点，匹配成功后调用 `performClick(rect)`
- 规划了前台服务（保活）、悬浮窗服务（布局检查）等辅助能力

### 2.4 TouchHelper（Android-Touch-Helper）

**定位**：轻量开源跳过工具，三重识别机制。

**实现**（来源：项目技术文档）：

```kotlin
override fun onAccessibilityEvent(event: AccessibilityEvent) {
    if (serviceImpl != null) {
        serviceImpl.onAccessibilityEvent(event)
    }
}
```

识别策略：
1. **关键词匹配** —— 扫描控件文本（「跳过」「Skip」）
2. **控件特征匹配** —— 预设控件 ID 或 description
3. **坐标位置匹配** —— 固定位置直接点击

点击动作带**降级回退**：

```kotlin
val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
if (!clicked) {
    click(rect.centerX(), rect.centerY(), 0, 20)   // 回退到坐标手势点击
}
```

> **这个降级模式值得直接采用**：很多广告的「跳过」按钮并非真正可点击的节点
> （可能外层套了容器、或设置了 `clickable=false`），
> `performAction(ACTION_CLICK)` 会返回 `false`。此时回退到 `dispatchGesture` 坐标点击成功率更高。

配置项：应用白名单 + 关键词自定义。

---

## 3. 技术核心：AccessibilityService 能力与限制

### 3.1 能力边界

无障碍服务作为「为残障用户设计的辅助接口」，实际提供的能力远超其设计初衷：

**读能力**：
- 接收所有应用的 UI 事件流：`TYPE_WINDOW_STATE_CHANGED`、`TYPE_WINDOW_CONTENT_CHANGED`、`TYPE_VIEW_CLICKED`、`TYPE_VIEW_TEXT_CHANGED` 等
- 获取任意应用的活动窗口根节点：`getRootInActiveWindow()`
- 遍历视图树，读取每个节点的 `text`、`contentDescription`、`viewIdResourceName`、`boundsInScreen`、`className`、`clickable` 等属性

**写能力**：
- `node.performAction(ACTION_CLICK)` —— 在节点上触发点击
- `dispatchGesture()` —— 派发任意手势（点击、滑动、长按、多指）
- `performGlobalAction()` —— 系统级动作（返回、主页、最近任务、通知栏等 30+ 种）

### 3.2 关键配置项

**Manifest 声明**：

```xml
<service
    android:name=".service.NoAdAccessibilityService"
    android:exported="false"
    android:label="@string/accessibility_service_label"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data
        android:name="android.accessibilityservice"
        android:resource="@xml/accessibility_service_config" />
</service>
```

**配置文件（`res/xml/accessibility_service_config.xml`）关键属性**：

| 属性 | 说明 |
|---|---|
| `accessibilityEventTypes` | 监听的事件类型，建议只订阅必要类型 |
| `packageNames` | **限定监听的应用包名**，性能与隐私的关键 |
| `accessibilityFeedbackType` | 反馈类型，一般 `feedbackGeneric` |
| `canRetrieveWindowContent` | 必须 `true`，否则无法读视图树 |
| `canPerformGestures` | 需要坐标点击时设为 `true` |
| `notificationTimeout` | 事件节流间隔，避免高频事件压垮服务 |
| `isAccessibilityTool` | Android 12+ 用于声明「真实无障碍工具」身份 |
| `canRequestFilterKeyEvents` | 过滤按键事件，**本项目不需要，应保持 false** |

**运行时通过 `setServiceInfo()` 动态调整**：

```kotlin
override fun onServiceConnected() {
    super.onServiceConnected()
    serviceInfo = serviceInfo.apply {
        eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                     AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        packageNames = targets.toTypedArray()   // 只监听白名单应用
        flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        notificationTimeout = 100
    }
}
```

### 3.3 重要限制与风险

**性能限制**：
- 事件回调频率极高，`onAccessibilityEvent` **在主线程**执行 → **禁止**在其中做耗时操作，须快速返回或转交后台
- `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` 可访问系统弹窗与其他应用悬浮窗，但**开销明显更大**，非必要不启用

**系统限制**（Android 版本演进）：

| 版本 | 变化 |
|---|---|
| Android 12 (API 31) | 引入 `isAccessibilityTool`，Google Play 对非无障碍用途应用要求显著披露 |
| Android 13 (API 33) | **侧载应用的无障碍开关被 `Restricted settings` 拦截**，用户需手动「允许受限设置」 |
| Android 14 (API 34) | 应用可用 `ACCESSIBILITY_DATA_PRIVATE_YES` 阻止非无障碍工具读取特定 View |
| Android 15 | 部分机型上「允许受限设置」入口消失，侧载应用可能完全无法开启无障碍 |

> **对 NoAd 的直接影响**：`minSdk 30` 及以上覆盖了 Android 11–15。在 Android 13+ 设备上，
> 若通过侧载安装，用户需额外操作「允许受限设置」才能开启服务。
> **应用内必须提供清晰的引导文案**，否则用户会卡在这一步。

**安全审查压力**：
- 无障碍权限被恶意软件大量滥用（读取银行 App、窃取 2FA 验证码、静默授权）
- Google Play 政策要求使用无障碍 API 的应用**必须能说明真实的无障碍需求**，否则不予上架
- 直接后果：**纯广告拦截类应用极难通过 Google Play 审核**，国内应用商店同样受限

---

## 4. 规则引擎设计对比

| 维度 | 李跳跳 | GKD | SKIP | TouchHelper |
|---|---|---|---|---|
| 规则格式 | 私有（含外部规则库） | JSON5 | YAML | 代码内置 + 关键词配置 |
| 匹配维度 | 文本 / ID / 坐标 / 倒计时 | 完整选择器（id/text/desc/class/正则/层级） | ID / 坐标 / 文本 | 文本 / ID / 坐标 |
| 规则分发 | 本地导入 | 远程订阅（URL） | 配置文件 | 白名单 + 关键词 |
| 作用域限定 | 应用级 | 应用级 + 活动级 | **packageName + activityName** | 应用级 |
| 调试工具 | 无公开说明 | **快照审查（可视化）** | InspectActivity | 无 |
| 联网 | **完全不联网** | 订阅需联网 | 可选 | 否 |

**综合建议**：采用 **GKD 的选择器表达力 + SKIP 的活动级作用域限定**，规则格式用 JSON，初期只支持本地规则（保持离线），后期再考虑订阅。

---

## 5. 法律与合规要点

**这部分必须重视，它决定了产品能否长期存在。**

### 5.1 法律依据

《互联网广告管理办法》第十条：**弹窗广告必须提供有效的关闭按钮**，且禁止设置虚假关闭按钮、要求多次点击等行为。

这构成了「自动点击关闭按钮」的**正当性基础**——工具只是代替用户完成了法规已要求提供、且用户本应能一键完成的操作。

### 5.2 已知风险

1. **不正当竞争风险** —— 李跳跳即因此收到律师函并停止更新。
   广告是内容平台的收入来源，绕过广告可能被视为干扰他人经营活动。
2. **分发渠道风险** —— 该类应用通常无法上架主流商店（李跳跳被 360 商店下架）。
3. **无障碍权限的政策风险** —— Google Play 明确限制非无障碍用途使用该 API。
4. **GKD 的做法** —— 采用 GPL-3.0 协议 + 明确免责声明「仅供学习交流，禁止商业或非法用途」。

### 5.3 建议的合规策略

- **明确定位为个人辅助工具**，不做商业化，不诱导用户
- **坚持纯本地运行**（与李跳跳一致），不采集、不上传任何用户数据
- 应用内提供**显著免责声明**与使用说明
- **优先自用/开源分发**，不将上架应用商店作为前提
- 拦截规则聚焦于「跳过按钮」本身，**不主动屏蔽或篡改广告内容**

---

## 6. 对本项目的选型结论

结合 NoAd 现状（纯 Compose UI 骨架，无任何服务与数据层）：

| 决策点 | 建议 | 理由 |
|---|---|---|
| 拦截机制 | **AccessibilityService 模拟点击** | 免 Root 前提下唯一可行路径 |
| 规则格式 | **JSON（内置资源 + 后续支持导入）** | 比 YAML 更易用 Kotlin 序列化，比私有格式更可维护 |
| 作用域限定 | **packageName + activityName 双限定** | 借鉴 SKIP，显著降低误点 |
| 匹配策略 | **四层递进：ID → 文本 → 描述 → 坐标（降级）** | 借鉴 TouchHelper 的降级回退思路 |
| 数据存储 | **Room（规则 + 拦截日志）+ DataStore（偏好）** | 符合 Google 官方推荐 |
| 联网 | **初期完全不联网** | 借鉴李跳跳，建立信任基础 |
| 分发 | **个人使用 / 开源** | 规避上架审核与法律风险 |

详细实现方案见同目录 `IMPLEMENTATION_PLAN.md`。

---

## 7. 参考来源

| 来源 | 内容 |
|---|---|
| 百度百科「MissLee (李跳跳)」 | 李跳跳技术原理与运营历史 |
| 蓝点网 | 李跳跳 2.2 版本说明与功能列表 |
| 新绿资源网 / 阿里西西 | 李跳跳工作原理详解、跳过策略分层 |
| GitHub `gkd-kit/gkd` | GKD 三要素架构、模块划分、选择器与订阅机制 |
| GKD 官方文档 `gkd.li` | 选择器语法、订阅规则说明 |
| GitHub `Android-Touch-Helper` | 三重识别机制、点击降级回退代码 |
| CSDN「SKIP 项目全解析」 | SKIP 的 YAML 规则格式与匹配方式 |
| Android 官方文档 | `AccessibilityService` 生命周期、`dispatchGesture`、`performGlobalAction` |
| Kaspersky / Alphanso Labs | Android 13–15 `Restricted settings` 机制 |
| shoonya.io | Android 14 `ACCESSIBILITY_DATA_PRIVATE_YES` |
| yinkoshield.com | 无障碍服务滥用面与平台缓解措施 |

> **说明**：以上资料均来自公开网络检索，部分为第三方媒体对产品的技术分析，
> 非官方技术文档。涉及具体实现细节时，应以 Android 官方文档为最终依据。
