# 发现页重设计 · 视觉规格（Stage 3 · Material 3）

> 阶段：**Stage 3（视觉设计）**。输入为已批准的线框 `wireframes.md`（W-01..W-11）。
> 分支：`feat/discover-redesign`。本文件只定义视觉与组件规格，**不含实现代码**。
> 规范：`custom-ui-spec` → Google Material 3（Android / Compose）。
> 所有数值为最终值；实现时不得用近似值替代。

---

## 1. 设计系统基线

| 项 | 取值 |
|---|---|
| 平台 | Android（Compose Material 3） |
| 主断点 | Compact（<600dp），本规格只覆盖此断点 |
| 宽屏 | ≥600dp 沿用现有 `NavigationRail`，网格列数由 `GridCells.Adaptive` 自适应，不新增布局 |
| 颜色 | 复用 `ClientColors`（`app/src/main/java/com/shixi/client/ClientColors.kt`） |
| 字体 | 复用 `ClientTypography`（同上） |
| 图标 | 复用 `AppIcon` 矢量（24dp viewport），不引入图标库 |
| 主题 | 跟随系统深色模式，无动态取色 |

**新增 token：0 个。** 所有新增视觉元素都映射到现有 M3 角色；新增语义只新增「用法规则」，不新增色值。

**不使用的 token**：`tertiary`（未在 `ClientColors` 定义，会回落 M3 默认紫色，破坏品牌一致性）。

---

## 2. 颜色

### 2.1 角色映射

| 用途 | Token | Light | Dark | 对比度（实测） |
|---|---|---|---|---|
| 页面背景 | `surface` | `#FCFCFB` | `#171B18` | — |
| 主文字 | `onSurface` | `#252925` | `#E8ECE6` | 14.38 / 14.57 |
| 次要文字 | `onSurfaceVariant` | `#6B716C` | `#AAB3A8` | 4.87 / 8.06（on `surface`） |
| 次要文字（容器上） | `onSurfaceVariant` | 同上 | 同上 | 4.53 / 6.63（on `surfaceContainer`） |
| 播放/选中 | `primary` | `#BC451D` | `#F29466` | 5.10 / 7.63 |
| 播放/选中底 | `primaryContainer` | `#FBECE5` | `#3A2A22` | — |
| 选中底上的文字 | `onPrimaryContainer` / `primary` | `#BC451D` | `#F29466` | 4.55 / — |
| 卡片/骨架底 | `surfaceContainer` | `#F3F4F2` | `#252C26` | — |
| 骨架高光 | `surfaceContainerHigh` | `#EDEFEA` | `#2C342D` | — |
| 分隔线 | `outlineVariant` | `#E8EAE5` | `#353E36` | — |
| 描边/边框 | `outline` | `#858C84` | `#82907F` | 3.36 / 5.18（≥3:1） |
| 错误 | `error` | `#B3261E` | `#FFB4AB` | 6.37 / — |
| 悬浮标签底 | `inverseSurface` | `#171B18` | `#FCFCFB` | — |
| 悬浮标签文字 | `inverseOnSurface` | `#E8ECE6` | `#252925` | 14.57 |
| 悬浮标签点缀 | `inversePrimary` | `#F29466` | `#BC451D` | 7.63 / 5.10 |

### 2.2 颜色规则（硬约束）

| ID | 规则 | 依据 |
|---|---|---|
| C-1 | 橙色（`primary`）**只用于播放与选中**，不用于装饰、标题、图标底色 | 现有 `ClientColors` 注释约定 |
| C-2 | 禁止把 `onSurfaceVariant` 放在 `surfaceContainerHighest` 上 | 实测 4.12 < 4.5，小字不合格 |
| C-3 | 状态不得只靠颜色表达：VIP / 付费 / 完结 / LIVE 必须带文字 | WCAG 1.4.1 |
| C-4 | `error` 只用于失败/错误，不用于 LIVE 或强调 | 语义正确性 |
| C-5 | 封面上的悬浮标签统一用 `inverseSurface` + `onInverseSurface`，点缀用 `inversePrimary` | 封面亮度不可控，需要稳定对比 |
| C-6 | 深色模式只切换 token，不写第二套色值 | 单一来源，避免漂移 |

---

## 3. 字体

| 元素 | Token | 字号/行高/字重 | 行数 | 颜色 |
|---|---|---|---|---|
| 页面标题「发现」 | `headlineLarge` | 26/34 Bold | 1 | `onSurface` |
| 分区标题（猜你喜欢/相声评书） | `titleLarge` | 19/28 SemiBold | 1 | `onSurface` |
| 分类分组标题 | `titleMedium` | 16/24 SemiBold | 1 | `onSurface` |
| 专辑卡标题 | `titleSmall` | 15/23 Medium | 2 | `onSurface` |
| 专辑卡副行 | `bodySmall` | 12/18 Regular | 1 | `onSurfaceVariant` |
| 列表行标题（分类/榜单/听单/主播） | `titleSmall` | 15/23 Medium | 2（主播 1） | `onSurface` |
| 列表行副行 | `bodySmall` | 12/18 Regular | 1 | `onSurfaceVariant` |
| 榜单名次 1–3 | `titleMedium` | 16/24 SemiBold | 1 | `onSurface` |
| 榜单名次 4+ | `bodyMedium` | 14/22 Regular | 1 | `onSurfaceVariant` |
| Tab 标签 | `labelLarge` | 13/20 Medium | 1 | 选中 `primary` / 未选中 `onSurfaceVariant` |
| Chip 标签 | `labelLarge` | 13/20 Medium | 1 | 选中 `primary` / 未选中 `onSurface` |
| 主按钮 | `labelLarge` | 13/20 Medium | 1 | `onPrimary` |
| 徽标（VIP/付费/完结） | `labelSmall` | 10/15 Medium | 1 | 见 §5.9 |
| 空态标题 | `titleMedium` | 16/24 SemiBold | 2 | `onSurface` |
| 空态说明 | `bodyMedium` | 14/22 Regular | 3 | `onSurfaceVariant` |
| 错误提示 | `bodyMedium` | 14/22 Regular | 3 | `onSurface` |

**规则**

- T-1：正文最小 12sp；10sp 仅用于徽标，不承载正文信息。
- T-2：所有文本容器使用 `heightIn(min=…)`，不写死高度，保证 200% 字体缩放不裁切。
- T-3：标题统一 `maxLines + TextOverflow.Ellipsis`，不做自动缩小字号。
- T-4：不使用自定义字体；Compose 默认即 Roboto / Noto（含 CJK）。

---

## 4. 尺寸、形状、间距

| 项 | 值 |
|---|---|
| 基础栅格 | 4dp（所有间距为 4 的倍数） |
| 页面水平边距 | 16dp |
| 标题栏 | `heightIn(min=64dp)`，左右 16dp |
| 顶级 Tab | `heightIn(min=48dp)`，item 水平 padding 16dp |
| Tab 指示器 | 高 3dp，`primary`，宽度 = 标签宽度 |
| 分区头 | 高 28dp，区块间距 24dp |
| 专辑网格 | `GridCells.Adaptive(148dp)`，水平 16dp，垂直 24dp |
| 横滑模块 | 卡片宽 132dp，间距 12dp，左右内容边距 16dp |
| 主按钮 | `heightIn(min=48dp)`，圆角 24dp，水平 padding 24dp |
| 文本按钮 | `heightIn(min=48dp)`，水平 padding 12dp |
| 图标按钮 | 48×48dp，图标 20dp |
| 分类/排序 chip | `heightIn(min=36dp)`，圆角 18dp，水平 padding 12dp，间距 8dp |
| 列表行（分类/榜单） | `heightIn(min=88dp)`，水平 16dp，垂直 12dp |
| 主播行 | `heightIn(min=72dp)`，头像 56dp 圆形 |
| 封面圆角 | 12dp（卡片、列表、直播间） |
| 直播间封面 | 宽 = 屏宽 − 32dp，1:1，圆角 16dp |
| 底部 Sheet | 顶部圆角 28dp，最大高 80% 屏高，左内边距 24dp |
| 徽标 | 高 18dp，圆角 4dp，水平 padding 4dp |
| 骨架圆角 | 12dp（封面）/ 4dp（文本条） |
| 触控目标 | 全部 ≥ 48×48dp |

---

## 5. 组件视觉规格

### 5.1 顶级 Tab

```
[ 推荐 ]  [ 分类 ]  [ 广播 ]  [ 榜单 ]  [ 主播 ]
 ──────
```

| 项 | 值 |
|---|---|
| 容器 | `surface`，高 `heightIn(min=48dp)`，可横向滚动 |
| 标签 | `labelLarge` 13/20 Medium，水平 padding 16dp |
| 选中 | 文字 `primary` + 底部指示器 3dp `primary` |
| 未选中 | 文字 `onSurfaceVariant` |
| 按下 | M3 ripple（`onSurface` 12%），不改变文字色 |
| 切换动画 | 内容交叉淡入 150ms；指示器位移 150ms `FastOutSlowIn` |
| 键盘焦点 | 2dp `primary` 内描边（仅 D-pad/键盘可见） |

### 5.2 Chip（分类 / 子类 / 榜单组 / 直播分类）

| 状态 | 背景 | 文字 | 边框 |
|---|---|---|---|
| 未选中 | `surfaceContainer` | `onSurface` | 无 |
| 选中 | `primaryContainer` | `primary` | 无 |
| 按下 | ripple 覆盖 | 不变 | 无 |
| 禁用 | `surfaceContainer` 38% | `onSurface` 38% | 无 |

尺寸：`heightIn(min=36dp)`、圆角 18dp、水平 padding 12dp、chip 间距 8dp。文本 1 行省略。选中态不以加粗表达（避免宽度跳动）。

### 5.3 专辑卡

```
┌──────────────────┐
│                  │  ← 封面 1:1，圆角 12dp，底 surfaceContainer
│                  │
└──────────────────┘
标题第一行
标题第二行（超出省略）
主播 · 12.3万播放 · 128集      ← bodySmall，onSurfaceVariant
```

| 项 | 值 |
|---|---|
| 宽 | 网格自适应，最小 148dp |
| 封面 | 1:1，圆角 12dp，`surfaceContainer` 占位，失败显示 24dp 耳机图标（`onSurfaceVariant`） |
| 封面→标题 | 9dp |
| 标题 | `titleSmall`，2 行，`onSurface` |
| 副行 | `bodySmall`，1 行，`onSurfaceVariant`；字段缺失时整行不渲染 |
| 卡片底 | 无背景、无边框、无阴影（与现有页面一致） |
| 按下 | ripple 裁切 12dp |
| 禁用 | 不适用 |

**副行拼装顺序**：主播 · 播放量 · 集数 · 徽标（VIP/付费/完结）。空字段跳过，分隔符只在两侧都有值时出现。

### 5.4 直播卡

```
┌──────────────────┐
│ ● LIVE           │  ← 徽标距封面左上 8dp
│                  │
│      封面 1:1    │
└──────────────────┘
直播间名（2 行）
主播名 · 6,995人在听
```

| 项 | 值 |
|---|---|
| 卡片 | 132dp 宽，封面 1:1 圆角 12dp |
| LIVE 徽标 | 高 20dp，圆角 4dp，背景 `inverseSurface`，文字 `inverseOnSurface` `labelSmall` Medium，圆点 4dp `inversePrimary`，间距 4dp，水平 padding 6dp |
| 静音/结束 | 不渲染（列表只含直播中） |
| 按下 | ripple 裁切 12dp |

LIVE 用 `inverse*` 而非 `error`：直播是正常播放状态，不是错误（规则 C-4）。

### 5.5 榜单行

| 区域 | 规格 |
|---|---|
| 行 | `heightIn(min=88dp)`，水平 16dp，垂直 12dp，整行可点 |
| 名次列 | 宽 32dp，右对齐；1–3 `titleMedium` `onSurface`，4+ `bodyMedium` `onSurfaceVariant` |
| 封面 | 64dp × 64dp，圆角 12dp |
| 标题 | `titleSmall` 2 行 |
| 副行 | `bodySmall` 1 行：主播 · 播放量 · 徽标 |
| 尾部 | 「订阅」`TextButton`（`heightIn(min=48dp)`，文字 `primary`） |
| 分隔 | 行间 `HorizontalDivider`，1dp `outlineVariant` |

前三名不加色块/奖牌图形：名次是信息，不是装饰（规则 C-1）。

### 5.6 主播行

| 区域 | 规格 |
|---|---|
| 行 | `heightIn(min=72dp)`，水平 16dp |
| 头像 | 56dp 圆形（`CircleShape`），底 `surfaceContainer`，失败用 24dp 人像图标 |
| 昵称 | `titleSmall` 1 行 |
| 副行 | `bodySmall`：认证 · 粉丝数（缺失则只显示存在的字段） |
| 尾部 | 无；整行进详情 |
| 分隔 | 1dp `outlineVariant` |

### 5.7 听单卡

| 项 | 值 |
|---|---|
| 卡片 | 132dp 宽，封面 1:1 圆角 12dp |
| 标题 | `titleSmall` 2 行 |
| 副行 | `bodySmall`：`N 张专辑`（来自 `footnote`） |

### 5.8 按钮

**主按钮（专辑页「播放」/ 直播间「播放直播」/ 听单「播放全部」）**

| 状态 | 背景 | 文字 |
|---|---|---|
| 默认 | `primary` | `onPrimary` |
| 按下 | `primary` + ripple `onPrimary` 12% | `onPrimary` |
| 禁用 | `onSurface` 12% | `onSurface` 38% |
| 加载中 | `primary`，左侧 16dp 进度环（2dp 描边） | `onPrimary` |

尺寸：`heightIn(min=48dp)`，圆角 24dp，水平 padding 24dp，图标（可选）20dp + 8dp 间距。

**文本按钮**：文字 `primary`，`heightIn(min=48dp)`，水平 padding 12dp；按下 ripple；禁用文字 `onSurface` 38%。

**图标按钮**：48×48dp，图标 20dp，tint `onSurface`；按下 ripple 圆形 40dp。

### 5.9 徽标

| 徽标 | 背景 | 文字 | 高度 |
|---|---|---|---|
| VIP | `primaryContainer` | `onPrimaryContainer` | 18dp |
| 付费 | 透明 + 1dp `outline` | `onSurfaceVariant` | 18dp |
| 完结 | 无背景 | `onSurfaceVariant` | 18dp |
| LIVE | `inverseSurface` | `inverseOnSurface` | 20dp |

规则：每张卡最多 2 个徽标，优先级 VIP → 付费 → 完结；徽标 10sp，不参与语义标题层级。

### 5.10 骨架

| 元素 | 规格 |
|---|---|
| 封面 | 1:1，圆角 12dp，`surfaceContainer` |
| 标题 | 高 14dp × 2 行，圆角 4dp，间距 6dp |
| 副行 | 高 10dp × 1 行，圆角 4dp，间距 8dp |
| 闪动 | `surfaceContainer` ↔ `surfaceContainerHigh` 线性渐变，1200ms 循环 |
| 减少动效 | 静态 `surfaceContainer`，不闪动 |

骨架不显示文字、不显示广告位占位。

### 5.11 空态 / 错误

**空态**

```
        [图标 32dp, onSurfaceVariant]
              标题（titleMedium）
        说明最多 3 行（bodyMedium）
              [ 重试 / 去设置 ]
```

容器：最小高 240dp，垂直居中，水平 padding 24dp，按钮为文本按钮。

**内联错误（模块级 / 分页级）**

- 背景 `surfaceContainer`，圆角 8dp，padding 12dp
- 文字 `bodyMedium` `onSurface`，`liveRegion = Polite`
- 动作：文本按钮「重试」（`primary`）
- 不遮挡已加载内容；不整页替换

**浮动提示（Snackbar）**：沿用现有 `FloatingNotice`，不新增样式。

### 5.12 筛选 Sheet

| 项 | 值 |
|---|---|
| 背景 | `surfaceContainerLow`，顶部圆角 28dp |
| 遮罩 | 黑色 32%（M3 scrim） |
| 拖拽条 | 32×4dp，圆角 2dp，`surfaceVariant`，顶部居中 8dp |
| 标题 | `titleLarge`，左 24dp |
| 分组标题 | `titleMedium`，分组间距 24dp |
| 选项 | chip（§5.2），换行布局，间距 8dp |
| 底部按钮 | 左「重置」文本按钮，右「应用」主按钮，固定底部，padding 24dp |

---

## 6. 页面视觉标注

| 页面 | 背景 | 关键规格 |
|---|---|---|
| W-01 推荐 | `surface` | 标题 26sp；Tab 行 48dp；网格 148dp；横滑卡 132dp；分区间距 24dp |
| W-02a 分类网格 | `surface` | 分组标题 `titleMedium`；chip 换行，行间距 8dp；分组间距 24dp |
| W-02b 分类结果 | `surface` | 子类 chip 单行横滑；排序行 44dp；行高 88dp；分割线 `outlineVariant` |
| W-03 筛选 | `surfaceContainerLow` | Sheet 顶部 28dp；选项 chip；底部固定按钮区 |
| W-04 广播 | `surface` | 2 列网格 148dp；LIVE 徽标 20dp；副行 1 行 |
| W-05 直播间 | `surface` | 封面 1:1 圆角 16dp；主按钮 48dp；「正在直播」用 `primary` 文字+圆点 |
| W-06 榜单 | `surface` | 行高 88dp；名次列 32dp；前三 `titleMedium` |
| W-07 主播 | `surface` | 行高 72dp；头像 56dp 圆形 |
| W-08 主播详情 | `surface` | 头像 88dp；主按钮 48dp；专辑行 88dp |
| W-09 听单 | `surface` | 封面 88dp；行高 88dp；「播放全部」主按钮 |
| W-10 设置 | `surface` | 分组标题 `titleMedium`；Switch 默认 M3（52×32dp） |
| W-11 专辑页 | `surface` | 封面 112dp；新增主按钮 48dp；行高 76dp（现有） |

---

## 7. 深色模式

- 只使用 token，不写第二套色值；深色对全部关键组合已实测：`onSurface` 14.57、`onSurfaceVariant` 8.06、`primary` 7.63、`onPrimary` 6.84、`outline` 5.18。
- 封面图不做压暗处理（内容优先）；封面上的 LIVE 徽标在深色模式自动变为浅底深字（`inverseSurface` 切换）。
- 骨架在深色使用 `surfaceContainer` ↔ `surfaceContainerHigh`，#252C26 ↔ #2C342D。
- 分割线在深色使用 `outlineVariant` #353E36（不提高对比，避免噪点）。

---

## 8. 动效

| 场景 | 规格 |
|---|---|
| Tab 切换 | 150ms 交叉淡入；指示器 150ms `FastOutSlowIn` |
| 「换一换」 | 150ms 透明度过渡；列表滚回顶部 |
| 骨架 shimmer | 1200ms 线性循环 |
| Sheet 弹出 | 沿用 M3 默认（250ms） |
| 列表项 | 无入场动画 |
| 减少动效 | 关闭淡入/shimmer，仅保留 ≤100ms 透明度变化 |

---

## 9. 无障碍

| 项 | 要求 |
|---|---|
| 对比度 | 正文 ≥4.5:1；大字（≥18sp 或 ≥14sp Bold）≥3:1；描边 ≥3:1（已实测，见 §2.1） |
| 触控 | ≥48×48dp；chip 视觉 36dp 但热区 48dp |
| 语义 | 页面标题 `heading()`；分区标题 `heading()`；卡片/行 `Role.Button` |
| 描述 | 专辑卡「查看专辑 标题」；播放按钮「播放 标题」；LIVE「正在直播 直播间名，N 人在听」 |
| 状态 | 加载中 `contentDescription="正在加载"`；错误 `liveRegion=Polite` |
| 字体缩放 | 支持 200%：所有高度 `heightIn`，标题 2 行省略，徽标可隐藏 |
| 键盘/遥控 | 可聚焦 + 2dp `primary` 焦点描边；不劫持系统返回手势 |

---

## 10. 校验报告

> `scripts/validate_ui.py` 针对代码，本阶段尚无 UI 代码，未运行；实现完成后补跑。以下为对照 `references/checklist.md` 的人工核对。

### 通过

- [x] 颜色使用 M3 角色 + `on-*` 配对，正文对比度全部 ≥4.5:1（§2.1 实测）
- [x] 深色模式只切换 token
- [x] 触控目标 ≥48dp
- [x] 圆角使用 shape 约定：按钮胶囊 24dp、卡片 12dp、Sheet 28dp
- [x] 底部导航 80dp（现有）
- [x] 按钮水平 padding 24dp，页面边距 16dp
- [x] 支持减少动效
- [x] 支持 200% 字体缩放（`heightIn` 策略）
- [x] 全部交互元素具备焦点态与按下态
- [x] Switch 使用 M3 默认 52×32dp

### 警告（有意偏离，已记录理由）

| 项 | 偏离 | 理由 |
|---|---|---|
| 卡片 1dp 阴影 | 不使用阴影 | 现有页面用 `surfaceContainer` 色调分层；加阴影会与收听/我的两页不一致（设计系统优先于通用清单） |
| 动态取色 | 不支持 | 会破坏「橙色只用于播放/选中」的品牌规则 |
| 列表行 48dp 单行 | 分类/榜单 88dp、主播 72dp | 双行 + 64dp 封面的列表变体，非单行列表 |
| 10sp 徽标 | 使用 10sp | 仅徽标，不承载正文；200% 缩放时可隐藏 |
| 字体家族 | 未显式声明 | Compose 默认即 Roboto/Noto，无需覆盖 |

### 不适用

- 悬停态（Hover）：触屏为唯一输入，无鼠标悬停。
- 输入框规格：本页无新增输入框（搜索页沿用现有）。

---

## 11. 视觉决策记录

| ID | 决策 | 理由 | 被否方案 |
|---|---|---|---|
| V-01 | 不新增颜色 token | 现有品牌色 + M3 角色已覆盖全部语义；新增色会破坏单一来源 | 新增「直播红」「榜单金」：与 orange-only 规则冲突 |
| V-02 | LIVE 用 `inverseSurface` + `inversePrimary` | 封面亮度不可控，反色底保证 7.63:1；且不误用 `error` | 红色徽标：语义错误；`primary` 徽标：与选中态混淆 |
| V-03 | 卡片无阴影 | 与现有页面一致，用色调分层 | 1dp 阴影：跨页不一致 |
| V-04 | Tab 可横向滚动 | 5 项在 360dp 下接近满宽，放大字体后必然溢出 | 固定等分：200% 字体下截断 |
| V-05 | 名次不用奖牌/色块 | 名次是信息；色块会与播放强调争抢注意力 | 前三名金/银/铜：引入未定义色 |
| V-06 | 徽标先文字后颜色 | 10sp 放大后可能被隐藏，颜色不可作为唯一信息 | 纯色点标识：色盲不可辨 |
| V-07 | 骨架用低对比双色 | 1200ms 低频闪动，减少视觉噪音 | 高对比 shimmer：列表刷新时刺眼 |
| V-08 | 分类 chip 未选中用 `onSurface` 而非 `onSurfaceVariant` | chip 是主要操作入口，需要最高可读性（14.38:1） | `onSurfaceVariant`：4.87:1，可读但偏弱 |

---

## 12. 移交清单（实现前确认）

1. 颜色只从 `MaterialTheme.colorScheme` 取，禁止硬编码 hex。
2. 所有高度用 `heightIn(min=…)`，禁止 `height()` 固定文本容器。
3. 骨架、空态、错误、禁用四态必须实现并可通过测试触发。
4. 新增图标若 `AppIcon` 无对应矢量，按 24dp viewport 补 path，不引第三方图标库。
5. 直播封面使用 `ContentScale.Crop`，LIVE 徽标距封面左上 8dp。
6. 实现完成后执行 `custom-ui-spec/scripts/validate_ui.py` 与 `references/checklist.md` 复核，并更新本文件的校验报告。
