# 开屏广告跳过规则 —— 主流方案调研与规则设计依据

> 本文是 `builtin_skip_rules.json` 的**设计依据文档**。
> 规则不是凭空写的，每一条都应有出处（社区规则库、SDK 文档或真机抓包）。
>
> 调研时间：2026-09-19
> 触发原因：内置规则全部落空，S1 实机完全无效，追查发现规则是我凭常识捏造的。

---

## 0. 结论速览

| 发现 | 影响 |
|---|---|
| **开屏「跳过」按钮几乎都带倒计时**（`跳过 1`、`跳过 3`、`跳过广告 5s`） | **`TEXT + EXACT` 是错的匹配方式**，必然落空。这是本次失效的直接原因 |
| **`tt_splash_skip_btn` 是穿山甲 SDK 的跨应用统一 id** | 可一次性覆盖大量接入穿山甲的应用，是收益最高的通用规则 |
| 穿山甲按钮的 id 前缀是 **SDK 包名 `com.byted.pangle`**，不是宿主包名 | 官方规则库里的写法也印证了这一点 |
| 社区通用写法是 **`[text*="跳过"][text.length<=10][visibleToUser=true]`** | 包含 + 长度上限 + 可见性，三者缺一不可 |
| 各方案**都保留文本兜底**，不依赖 viewId 单打 | viewId 改版即失效，文本规则是韧性来源 |
| `activityIds` 在社区规则中**普遍不写或写宽松** | 我此前给 B站 等写的 activity 限定过窄也会导致漏拦（本次 `activity_name` 全为 null，未触发该问题） |

---

## 1. 调研对象与要点

### 1.1 GKD（开源，最活跃）

- 规则语法：CSS-like 选择器 + JSON5。
- **全局开屏规则**（社区广为流传的默认配置）：

```json5
{
  key: 0, name: '开屏广告',
  fastQuery: true, matchTime: 10000,
  actionMaximum: 1, resetMatch: 'app',
  rules: [
    { key: 0, matches: '[text*="跳过"][text.length<10][visibleToUser=true]' },
  ],
}
```

  注意三个条件：
  - `text*="跳过"` —— **包含**，不是等于
  - `text.length<=10` —— 长度上限，排除长正文
  - `visibleToUser=true` —— 必须用户可见

- 社区订阅（AIsouler / Adpro）中，同一个 `开屏广告` 规则组通常有**多条子规则**，从精确到宽松依次尝试：

```json5
// AppShare 的实例（摘自 AIsouler 订阅）
{ key: 0, anyMatches: [...] },                                  // 语义/文案
{ key: 1, matches: '[text*="跳过"][visibleToUser=true][text.length<=10]' },
{ key: 2, position: {left:'width * 0.5', top:'width * 0.6984'},  // 坐标兜底
  matches: '@ViewGroup > [text="跳过"][visibleToUser=true]' },

// OMOFUN 的实例
{ key: 0, matches: '[text*="跳过"][text.length<=10]' },
{ key: 1, matches: '[id="com.cyl.musiccy.ou:id/ksad_splash_root_container"] [childCount=3] > @ImageView[clickable=true] - [text="|"]' },
```

  **重要结构性启示**：真实规则库是**「精确规则 + 通用兜底」并存**，
  不是"要么全精确、要么全宽松"。

- 其他有用写法：
  - `id$="tt_splash_skip_btn"` —— id **后缀**匹配
  - `[clickable=true]` —— 可点击性约束
  - `actionMaximum` / `resetMatch: 'app'` —— 每次应用启动最多点一次（防重复）

- 社区规模：单一订阅 684–776 个应用、1400–1800 个规则组。

### 1.2 李跳跳（已停更，但规则思想经典）

- 默认规则核心就一条：`{"keywords":["跳过"]}`
- **「规则里面的文字默认情况下是模糊匹配的」** —— 官方文档明确说明。
- 支持首尾/全匹配修饰符：`+` 前缀匹配、`-` 后缀匹配、`=` 全匹配、`&` 且
  - 例：`{"popup_rules":[{"id":"=天天神券","action":"GLOBAL_ACTION_BACK"}]}`
- **它的默认「模糊匹配」正是能工作的关键** —— 而我写成了 EXACT。

### 1.3 SKIP（另一个开源方案，规则以 YAML 记录）

给出的真实配置片段（**与我们的目标应用直接相关**）：

```yaml
# B站
- id: tv.danmaku.bili:id/count_down
  activityName: tv.danmaku.bili.MainActivityV2

# 菜鸟（穿山甲）
- id: com.cainiao.wireless:id/homesplash_close_fullscreen
- id: com.cainiao.wireless:id/tt_splash_skip_btn

# CSDN（穿山甲）
- id: com.byted.pangle.m:id/tt_splash_skip_btn
- id: net.csdn.csdnplus:id/vlion_ad_closed
```

> ⚠️ 这三条直接推翻了我原先写的 `tv.danmaku.bili:id/skip` ——
> B站 真实 id 是 **`count_down`**，与我真机抓包一致。

### 1.4 穿山甲 / 优量汇 SDK（广告投放方视角）

- 穿山甲（Pangle / 巨量）开屏广告的跳过按钮 id 为 **`tt_splash_skip_btn`**。
  - 开发者文档提到：`getSplashView()` 后 `findViewById` 找跳过按钮，**「ID 通常是 `tt_splash_skip_btn`」**。
  - 在宿主应用内，该 id 前缀有两种形态：
    - `com.byted.pangle:id/tt_splash_skip_btn`（SDK 自有资源）
    - `<宿主包名>:id/tt_splash_skip_btn`（宿主覆写资源）
- 快手联盟：`...:id/ksad_splash_circle_skip_view`、`...:id/ksad_splash_skip_view`
- 腾讯优量汇 / 其他：`vlion_ad_closed`（CSDN 使用）等

**这是收益最高的发现**：一条 `tt_splash_skip_btn` 后缀规则
可覆盖**所有接入穿山甲的应用**，而不必逐应用维护。

---

## 2. 对我们此前实现的诊断

### 2.1 直接错误：EXACT 匹配动态文案

真机抓包（B站，2026-09-19）：

```
rid  = tv.danmaku.bili:id/count_down
text = "跳过 1"          ← 带倒计时
clickable = true
```

而原规则是：

| 规则 | 定位 | 值 | 结果 |
|---|---|---|---|
| B站开屏-跳过按钮 | VIEW_ID | `tv.danmaku.bili:id/skip` | ❌ id 不存在 |
| B站开屏-跳过广告 | TEXT EXACT | `跳过广告` | ❌ 文案不符 |
| B站开屏-跳过文本 | TEXT EXACT | `跳过` | ❌ `"跳过 1" ≠ "跳过"` |

**三条全部落空 → `NO_NODE_MATCH` → 无点击、无日志、统计恒 0。**

### 2.2 设计错误：把「宁可漏拦」执行成了「必然漏拦」

原 `meta.criteria` 写着「宁可漏拦，绝不错点」，方向没错，
但我把它落地成了「只用 EXACT」—— 结果是**几乎必然漏拦**，
因为开屏跳过的文案**本质就是动态的**。

正确的口径应是李跳跳与 GKD 共同验证过的：
**「包含 + 长度上限 + 可见性」**，用长度上限而不是精确相等来防误点。

### 2.3 结构错误：缺少「通用规则」层

我只写了逐应用规则，没有任何跨应用通用规则。
而真实方案（GKD 全局组、SKIP 的 SDK id 复用）都**必然包含一个通用层**。

---

## 3. 据此确立的规则设计原则

### 3.1 匹配模式的使用边界（修订）

| 模式 | 适用 | 约束 |
|---|---|---|
| `VIEW_ID` | 已确证存在的 id | 支持**后缀匹配**（`tt_splash_skip_btn` 跨包名复用） |
| `PREFIX`（新增） | `跳过 1`、`跳过广告 3s` 等动态前缀文案 | 目标串必须 ≥ 2 字符且以跳过类词开头 |
| `CONTAINS` | 稳定的专有词组 | 目标串 ≥ 4 字符，且不在禁止词表内 |
| `EXACT` | 文案完全固定的场景 | 用于「关闭广告」这类无倒计时的按钮 |

**新增 `PREFIX` 的理由**：`跳过 1` 这类文案的正确刻画是
「**以『跳过』开头**且总长度很短」，而不是「包含『跳过』」。
`CONTAINS` 会命中正文；`PREFIX` 不会。
它恰好对应李跳跳的 `+跳过` 语法。

### 3.2 三层规则结构

```
第 1 层：通用 SDK 规则（跨应用，priority 最低）
  └ tt_splash_skip_btn / ksad_splash_skip / 跳过文案前缀
第 2 层：应用专属精确规则（priority 高）
  └ 已确证的 viewId（如 tv.danmaku.bili:id/count_down）
第 3 层：应用专属文本兜底（priority 中）
  └ PREFIX「跳过」等
```

### 3.3 仍不放宽的部分（防误点底线）

无论怎么放宽，以下**必须**继续成立：

- `CONTAINS` 目标串 ≥ 4 字符
- 不含诱导性词汇（立即 / 领取 / 查看 / 下载 / 打开 / 安装 / 购买 / 下单 /
  抽奖 / 红包 / 优惠 / 开通 / 授权 / 同意 / 允许）
- `PREFIX` 的目标串必须是**跳过类词**（跳过 / 关闭广告 / 跳过广告），
  且必须是**前缀**而非包含
- 无坐标规则
- 对「正常界面」样本零假阳性

---

## 4. 本项目的规则策略（最终）

1. **优先补充已确证的 viewId**（有真机抓包或社区规则库出处）。
2. **文本规则改用 `PREFIX`**（`跳过`），覆盖带倒计时的文案。
3. **新增跨应用通用规则**，以 `tt_splash_skip_btn` 等 SDK id 后缀为主。
4. **保留 EXACT** 用于「关闭广告」这类固定文案。
5. 每条规则的 `note` **必须写明依据**（抓包 / 社区规则库 / SDK 文档），
   便于日后审计与失效判定。

---

## 5. 出处清单

| 来源 | 用途 |
|---|---|
| GKD 全局开屏规则 `[text*="跳过"][text.length<10][visibleToUser=true]` | 通用文本规则设计 |
| GKD 社区订阅 AIsouler / Adpro | 规则结构（精确+兜底并存） |
| 李跳跳官方文档（`keywords` 模糊匹配、`+`/`-`/`=` 修饰符） | PREFIX 模式的理论依据 |
| SKIP 项目配置（`skip.guoxicheng.top`） | B站 `count_down`、穿山甲 id 实证 |
| Magisk 订阅仓库（`magisk317/subscription`） | 穿山甲 / 快手 SDK id 实证 |
| 穿山甲 SDK 文档（`tt_splash_skip_btn`） | 跨应用通用 id 的依据 |
| 本机真机抓包 2026-09-19 | B站 `count_down` + `跳過 1` 文案 |

> 规则失效是**常态**而非异常 —— 广告 SDK 与宿主应用都在持续改版。
> 因此规则库需要「通用兜底层」来吸收单条规则的失效，
> 并需要规则管理页让用户自行补充。这一点三个方案都做了同样的选择。
