# 发现页改版 · 实施计划

> 输入：`wireframes.md`（已批准）、`visual-spec.md`、`api-spec.md`（实测接口）。
> 分支：`feat/discover-redesign`（worktree `~/github.com/shixi-discover-redesign`）。
> 估算：单人、不含评审等待；单位为“人日”。

---

## 0. 范围

### 做

1. 发现页改为 5 个 Tab：推荐 / 分类 / 广播 / 榜单 / 主播。
2. 全部入口可在 App 内播放（专辑、电台、直播间）。
3. 设置新增「个性化推荐」开关（默认开，登录后生效）。
4. 零广告：不渲染 `ad` 模块，不新增任何广告位。
5. 专辑页补主播放按钮与主播/简介/播放量。

### 不做

- 听单（`playlist`）：详情接口未解决，整块不渲染。
- 直播送礼/打赏/会员促销。
- 搜索页改造（沿用现有）。
- 离线下载、评论体系改造。
- `收听` / `我的` 其他页面改版。

---

## 1. 现状约束（代码事实）

| 位置 | 现状 | 影响 |
|---|---|---|
| `MainActivity.kt` | 单 Activity + 每个底部 Tab 一个 `mutableStateListOf<String>` 返回栈 | 新页面沿用栈 key，不引入导航库 |
| `UiComponents.kt:206` | `DiscoveryScreen` 只有 6 个关键词 chip + 搜索结果网格 | 整体替换 |
| `DiscoveryStore.kt` | 单例 + 6 个 topic + 磁盘缓存 | 重写为按域的数据仓库 |
| `PlaybackResolver.kt:20` | 只接受 `shixi://track/{id}/{album}/{page}` | 直播/电台需新增 URI 分支 |
| `PlaybackService.kt:206` | 专辑队列、历史、进度逻辑绑定 `Item` | 直播需“直播模式”旁路 |
| `AccountContentApi.kt:60` | 请求 URL 白名单只允许 `mobile.ximalaya.com` 播放相关路径 | 发现接口不能复用该客户端，需独立客户端 |
| `app/src/androidTest` | 只有 instrumentation 测试；CI 只 build + lint | 新测试按现有方式写 |
| `app/build.gradle.kts` | Compose BOM / media3 1.10.1 / OkHttp / Coil3 | 不新增依赖 |

---

## 2. 目标结构

```
protocol/
  DiscoveryApi.kt         # HTTP + JSON → 模型（纯网络层，无 Android UI）
  DiscoveryModels.kt      # AlbumBrief / Category / Rank / Anchor / Radio / LiveRoom / AlbumDetail
app/
  DiscoveryRepository.kt  # 缓存、并发、代际取消、个性化身份、广告过滤
  DiscoverRecommend.kt    # 推荐 Tab
  DiscoverCategory.kt     # 分类 Tab + 分类结果页
  DiscoverRank.kt         # 榜单 Tab
  DiscoverAnchor.kt       # 主播 Tab + 主播详情
  DiscoverBroadcast.kt    # 广播 Tab + 电台/直播间
  DiscoverComponents.kt   # 卡片、Chip 组、骨架、空态、筛选 Sheet
  ContentPreferenceStore.kt
```

数据流：

```
Composable → DiscoveryRepository.state (StateFlow)
           → DiscoveryApi (OkHttp, 按域 UA)
           → 磁盘/内存缓存
           → UI
播放：AlbumBrief → 专辑页 → Item → 现有 playback
      Radio/LiveRoom → MediaItem(mime=HLS) → PlaybackResolver 新分支
```

---

## 3. 文件级改动清单

### 新增

| 文件 | 职责 |
|---|---|
| `protocol/.../DiscoveryModels.kt` | 数据模型，全部字段可空，不加默认假值 |
| `protocol/.../DiscoveryApi.kt` | 9 个接口方法 + 解析 + 错误分类 |
| `app/.../DiscoveryRepository.kt` | 按域缓存、TTL、并发去重、代际取消、广告过滤 |
| `app/.../DiscoverRecommend.kt` | 推荐 Tab（猜你喜欢网格 + 分类卡片 + 热词） |
| `app/.../DiscoverCategory.kt` | 29 分类网格 + 结果页（子类/排序/筛选/分页） |
| `app/.../DiscoverRank.kt` | 6 榜单 + 类目切换 + 榜单列表 |
| `app/.../DiscoverAnchor.kt` | 6 主播榜 + 主播详情（资料 + 前 15 张专辑） |
| `app/.../DiscoverBroadcast.kt` | 电台模块 + 直播间列表/详情 |
| `app/.../DiscoverComponents.kt` | AlbumCard 扩展、RankRow、AnchorRow、RadioCard、LiveCard、ChipGroup、Skeleton、EmptyState、FilterSheet |
| `app/.../ContentPreferenceStore.kt` | 个性化开关（SharedPreferences） |
| `app/src/androidTest/.../DiscoveryParsingVerification.kt` | 固定 JSON fixture 的解析/过滤测试 |
| `app/src/androidTest/.../DiscoveryUiVerification.kt` | 五 Tab、状态、返回栈（重写） |
| `app/src/androidTest/assets/discovery/*.json` | 实际响应脱敏后的 fixture |

### 修改

| 文件 | 改动 |
|---|---|
| `MainActivity.kt` | 注入 Repository；新增栈 key 与页面分发；设置入口传开关状态 |
| `UiComponents.kt` | 删除旧 `DiscoveryScreen`；`SettingsScreen` 增加「内容偏好」组；新增 `SettingSwitchRow` |
| `PlaybackState.kt` | `PlaybackItems.live(...)`：`shixi://live/...`、`setMimeType(APPLICATION_M3U8)` |
| `PlaybackResolver.kt` | 新增 `liveAddress: (Long) -> String` 可选参数（默认空实现，保持现有调用兼容）；`host=="live"` 分支；直播 URL 校验 https + 60s 缓存 |
| `PlaybackService.kt` | 直播模式 extras：禁用队列/seek/历史写入；状态文案「正在直播」 |
| `DiscoveryStore.kt` | 删除（能力迁移到 Repository） |
| `DiscoveryVerification.kt` / `DiscoveryPagingVerification.kt` / `RedesignUiVerification.kt` | 移除或改写为新仓库/UI 测试 |

---

## 4. 里程碑

### M0 · 数据层与契约（3 天）

**任务**

1. `DiscoveryModels.kt`：按 `api-spec.md` 定义字段；`AlbumBrief` 不继承 `Item`。
2. `DiscoveryApi.kt`：
   - `recommendSections()` → `www /revision/explore/v2/getRecommend`
   - `guessYouLike()` → `www /revision/explore/guessYouLike`
   - `mobileRecommend()` → `mobile /mobile/discovery/v1/recommend/album`（兜底，含 `includeActivity/includeSpecial`）
   - `categoryGroups()` → `www /revision/category/breadcrumbCategoryInfo?categoryId=9`
   - `categoryPage(code, sub, sort, metas, page)` → `m /m-revision/page/category/queryCategoryPage` + `queryCategoryAlbumsByPage`
   - `rankGroups()` / `rankCategories(cluster)` / `rank(cluster, code)`
   - `anchorBoards()` / `anchors(cluster, code)` / `anchorProfile(uid)` / `anchorAlbums(uid)`
   - `radioHome()` / `liveRoom(roomId)` / `liveStream(liveId)`
   - `albumDetail(albumId)` → `www /revision/album`
3. 每域 UA 与必需参数按 `api-spec.md` 写死并注释来源。
4. 广告过滤：`moduleType == "ad"` 直接丢弃；`focus/square` 仅保留内容型条目。
5. 错误分类：网络 / HTTP / `ret != 0` / 空数据，映射到用户文案。
6. fixture：从实测响应截取并脱敏，存入 `androidTest/assets/discovery/`。

**完成标准**

- 每个方法有 fixture 解析测试；`ad` 过滤有断言。
- 不改任何 UI，`assembleDebug` 通过。

---

### M1 · 推荐 + 专辑页（3.5 天）

**任务**

1. `DiscoveryRepository`：推荐状态（缓存优先、刷新、失败保留旧数据）。
2. 推荐 Tab：猜你喜欢网格（2 列，148dp）、分类卡片（横滑/网格）、热词 chips。
3. 卡片副行：`主播 · 播放量 · 集数`，字段缺失自动跳过。
4. 「换一换」：重拉 Web 卡片，滚动回顶（不做无限流）。
5. 专辑页：异步加载 `/revision/album`，显示主播、简介、播放量；新增 48dp 主播放按钮（续播点优先）。
6. 状态：骨架 / 空 / 模块级错误 / 下拉刷新。

**完成标准**

- 推荐首屏 2 个真实模块；`ad` 渲染数为 0。
- 卡片 → 专辑 → 播放全链路可走通。

---

### M2 · 分类（3.5 天）

**任务**

1. 分类网格：5 分组 + 29 个分类 chips（数据来自 breadcrumb，不写死 code）。
2. 分类结果页：子类 chips（`subCategoryCode`）、排序（`sort=0/1/2`）、筛选 Sheet（`metas`）。
3. 分页：`queryCategoryAlbumsByPage`，20/页，触底预取 3 条。
4. 切换分类/子类：回顶、重置页码；筛选后保留筛选条。
5. 空态文案按筛选条件区分。

**完成标准**

- 29 个分类全部可进入；任一分类可翻到第 2 页。
- 筛选「已完本 + 免费」结果全部符合（用 fixture 断言）。

---

### M3 · 榜单（1.5 天）

1. 榜单 Tab：6 个榜单 chips + 类目 chips（来自 `queryRankClusterDetail`）。
2. 列表：名次 + 专辑卡；无 `anchorInfo` 时副行不显示主播。
3. 空/错/加载；不做分页（接口一次返回 97–100 条）。

**完成标准**

- 6 个榜单均可切换；类目切换后标题与数据一致（如「历史热播榜」）。

---

### M4 · 主播（2 天）

1. 主播榜 6 个子榜；列表项：头像 56dp / 昵称 / 认证 / 粉丝数。
2. 主播详情：`/revision/user/basic` + `/revision/user`；展示前 15 张专辑。
3. 详情页注明「共 N 张，展示前 15 张」——避免用户以为是全部。
4. 进入专辑 → 播放。

**完成标准**

- 主播 → 专辑 → 播放可走通；空字段整行不渲染。

---

### M5 · 个性化设置（1.5 天，含验证门禁）

**前置验证（0.5 天，需真实账号）**

用登录态 Cookie 分别请求：

- `www /revision/explore/v2/getRecommend`
- `mobile /mobile/discovery/v1/recommend/album`

对比匿名响应是否变化。**若两者都不变，则不发布“假开关”**，改为：

- 设置项保留但标注「实验」，或
- 直接不提供开关，并在文档记录原因。

**实现（1 天）**

1. `ContentPreferenceStore`（SharedPreferences，单布尔）。
2. 设置页「内容偏好」组 + `Switch`；未登录禁用并显示说明。
3. 推荐请求根据开关决定是否附带身份；缓存键 `recommend:anon` / `recommend:{uid}`。
4. 切换后清推荐缓存并重拉；Snackbar 提示。

**完成标准**

- 关闭时请求无 `uid`/Cookie（测试断言）。
- 文案只承诺「通用热门内容」。

---

### M6 · 广播 + 播放层（3 天）

1. 电台：`radioHome` 3 个模块（头部调频 / 找电台 / 本地电台）。
2. 直播间：来源为移动推荐流 `live` 模块（固定 3 条）——若产品要求更多，需另找列表接口（当前无）。
3. 直播间详情：`lamia/v12/live/room`；取 `recordInfo.id` 作为 `liveId`。
4. 播放：
   - `PlaybackItems.live(roomId, liveId, title, cover)`，`setMimeType(MimeTypes.APPLICATION_M3U8)`。
   - `PlaybackResolver` 新增 `live` 分支：`liveStream(liveId)` → 校验 https。
   - 直播模式：禁用队列、seek、倍速、略过头尾；不写收听历史；状态「正在直播」。
   - 电台复用同一分支（`shixi://live/radio/{radioId}` + 已取到的 `playUrl`）。
5. 失败处理：`ret 2011` / 404 / 空地址 → 可重试；直播结束 → 提示并退出。

**完成标准**

- 电台点击 ≤2 次开始播放；直播间点击 ≤2 次开始播放。
- 播放器在直播态隐藏进度条与倍速。
- 退出直播后原专辑队列不被破坏。

**风险**：`PlaybackService` 改动可能影响现有播放；先跑 `PlaybackVerification` / `MemberVerification` / `PausedPlaybackVerification` 回归。

---

### M7 · 收尾与验收（2.5 天）

1. 无障碍：48dp 热区、`contentDescription`、`heading()`、200% 字体。
2. 深色模式对照 `visual-spec.md`。
3. 减少动效。
4. 性能：冷启动缓存优先；图片 `columns=290`；切 Tab 不重复请求。
5. 广告审计：确认无 `ad` 渲染路径。
6. 全量回归 + lint。

---

## 5. 数据层设计要点

### 5.1 缓存

| 数据 | 位置 | TTL |
|---|---|---|
| 推荐卡片 / 猜你喜欢 | 磁盘 + 内存 | 10 min（按身份分键） |
| 分类树（29 项） | 磁盘 | 24 h |
| 分类结果 | 内存 | 30 min（键含 code/sub/sort/metas/page） |
| 榜单分组 / 内容 | 磁盘 + 内存 | 30 min |
| 主播榜 | 内存 | 30 min |
| 主播资料 / 专辑 | 内存 | 1 h |
| 电台首页 | 内存 | 5 min |
| 直播流地址 | 内存 | 60 s |

旧文件 `discovery-cache-v1.json` 在首次启动时删除，避免残留。

### 5.2 并发

- 每个 key 一个 `Job`；新请求取消旧请求并递增 generation；迟到结果丢弃（沿用现有 `DiscoveryStore` 的做法）。
- 推荐页首屏并行请求 `getRecommend` + `guessYouLike`，任一失败不阻塞另一块。

### 5.3 错误

| 类型 | 文案 | 行为 |
|---|---|---|
| 网络失败 | 连接失败，请检查网络后重试 | 保留旧数据 |
| HTTP 5xx | 内容服务暂时不可用 | 模块级重试 |
| `ret != 0` | 暂时无法加载 | 模块级重试 |
| 空数据 | 暂无内容 | 不显示空占位，模块整体隐藏（推荐） |

---

## 6. UI 与导航

### 6.1 路由（沿用 per-tab 栈）

| key | 页面 | 需要保存的状态 |
|---|---|---|
| `discover-category-$tab` | 分类结果 | `categoryCode`、sub、sort、metas |
| `discover-rank-$tab` | 榜单详情 | 无（Tab 内切换） |
| `discover-anchor-$tab` | 主播详情 | `uid` |
| `discover-live-$tab` | 直播间 | `roomId`、`liveId` |
| `discover-radio-$tab` | 电台详情 | `radioId` |

- `navigateBack()` 增加上述 key 的清理分支。
- `switchTab()` 保留各 Tab 栈与滚动位置。

### 6.2 组件复用

- 复用：`CoverArtwork`、`SectionHeading`、`RefreshContent`、`InlineNotice`、`FloatingNotice`、`CompactAction`。
- 扩展：`AlbumCard` 增加 `supporting` 组合（现有参数已支持，只需传值）。
- 新增：`RankRow`、`AnchorRow`、`RadioCard`、`LiveCard`、`ChipGroup`、`SkeletonCard`、`EmptyState`、`FilterSheet`、`SettingSwitchRow`。

### 6.3 状态矩阵

按 `wireframes.md` §8 实现，每个新组件必须有 Loading / Empty / Error / Edge 四态实现与测试入口。

---

## 7. 播放层

### 7.1 直播 URI

```
shixi://live/room/{roomId}/{liveId}
shixi://live/radio/{radioId}/{bitrate}
```

- `MediaItem.Builder().setMimeType(MimeTypes.APPLICATION_M3U8)` 必须在建 MediaItem 时设置，否则 `DefaultMediaSourceFactory` 会按未知后缀选择错误的数据源。
- `PlaybackResolver` 解析后返回 https 的 m3u8；复用 `ResolvingDataSource`。
- 直播 session extras：`liveMode=true`、`liveTitle`、`liveKind=room|radio`。

### 7.2 行为差异

| 行为 | 点播 | 直播 |
|---|---|---|
| 进度条 | 显示 | 隐藏 |
| 倍速 | 支持 | 禁用 |
| 略过头尾 | 支持 | 禁用 |
| 上一集/下一集 | 支持 | 禁用 |
| 收听历史 | 写入 | 不写 |
| 断线恢复 | 续播 | 重新拉流 |

### 7.3 兼容性

`PlaybackResolver` 新参数带默认值，现有 5 处测试调用不改签名；`PlaybackService` 仅在 `liveMode` 下走新分支。

---

## 8. 测试计划

### 8.1 Instrumentation（沿用仓库方式）

| 用例 | 覆盖 |
|---|---|
| `DiscoveryParsingVerification` | 9 个接口 fixture 解析；`ad` 过滤；空字段；`metas` / `sort` 映射 |
| `DiscoveryUiVerification` | 5 Tab 切换；缓存不重复请求；返回栈；滚动位置；四态 |
| `DiscoveryPlaybackVerification` | 推荐/分类/榜单/主播 → 专辑 → 播放 |
| `LivePlaybackVerification` | `liveId` → https m3u8；直播态禁用控件 |
| 现有播放回归 | `PlaybackVerification`、`MemberVerification`、`PausedPlaybackVerification` |

### 8.2 手工矩阵

- 360dp / 411dp / 600dp
- 字体 1.0 / 1.3 / 2.0
- 浅色 / 深色
- 飞行模式冷启动、弱网重试
- 直播结束 / 主播下播 / 电台流 2011
- 后台播放切前台（直播重连）

### 8.3 CI

```
./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest \
  :app:assembleRelease :app:lintRelease
```

instrumentation 需真机，按现有 `-Pshixi.testRunner=...` 逐个跑。

---

## 9. 风险登记

| 风险 | 概率 | 影响 | 对策 |
|---|---|---|---|
| 接口变更 / 匿名限额 | 中 | 高 | 缓存 + 模块级降级；契约测试单独跑 |
| 个性化无效果 | 中 | 中 | M5 前置验证；无效果则不发布假开关 |
| 直播播放改动影响点播 | 中 | 高 | 直播模式旁路；先跑现有播放回归 |
| HLS mime 推断错误 | 低 | 高 | 建 MediaItem 时显式 `APPLICATION_M3U8`；真机验证 |
| 主播专辑只有 15 张 | 确定 | 低 | 文案注明「展示前 15 张」 |
| 听单不可用 | 确定 | 低 | 隐藏模块 |
| 5 Tab 在 360dp 溢出 | 中 | 低 | 可滚动 Tab；200% 字体验证 |
| 广告模块形态变化 | 低 | 中 | 白名单渲染，未知类型一律不渲染 |

---

## 10. 验收标准

1. 5 个 Tab 可用，默认推荐；切换后滚动位置保持。
2. 任一专辑 ≤2 次点击进入播放；电台/直播间 ≤2 次点击起播。
3. 分类 29 项可进入，支持子类/排序/筛选/分页。
4. 榜单 6 项、主播 6 榜可切换并进入播放。
5. 所有页面具备加载/空/错/边界四态，错误可恢复。
6. `ad` 模块渲染数为 0；无开屏/插屏/横幅/促销位。
7. 关闭个性化后请求不含身份（测试断言）。
8. 断网冷启动显示缓存；刷新失败保留旧内容。
9. 48dp 热区、TalkBack 可读、200% 字体不裁切。
10. CI 全绿；现有播放测试无回归。

---

## 11. 提交策略

- 每个里程碑至少一个独立 commit；M0 的数据层与 UI 不混提交。
- 删除旧 `DiscoveryStore` 与其测试单独提交，便于回滚。
- 每次提交前跑：`assembleDebug` + 相关 instrumentation。
- 文档与代码同 commit 更新（`api-spec.md` 若有新发现）。

---

## 12. 估时汇总

| 里程碑 | 人日 |
|---|---|
| M0 数据层与契约 | 3 |
| M1 推荐 + 专辑页 | 3.5 |
| M2 分类 | 3.5 |
| M3 榜单 | 1.5 |
| M4 主播 | 2 |
| M5 个性化（含验证） | 1.5 |
| M6 广播 + 播放层 | 3 |
| M7 收尾与验收 | 2.5 |
| **合计** | **20.5** |

可并行：M0 完成后，M1/M2 与 M3/M4 可交替；M6 的接口部分可与 M3/M4 并行，播放层改动需单独窗口。

---

## 13. 开始前需要确认

1. **个性化验证**：是否有可用测试账号（仅用于本地验证 Cookie 效果，不写入日志）。没有账号则 M5 只做“开关 + 匿名/身份请求”，并在文档标注效果未验证。
2. **主播专辑上限**：接受「展示前 15 张」，还是需要另找「主播全部专辑」接口（当前未找到）。
3. **听单**：确认隐藏（当前无可用详情接口）。

---

## 14. 实施记录（2026-11-26）

### 14.1 状态

| 里程碑 | 状态 | 说明 |
|---|---|---|
| M0 数据层 | ✅ 完成 | `DiscoveryApi` + 解析器 + 18 个 fixture 契约测试（JVM，全部通过） |
| M1 推荐 + 专辑页 | ✅ 完成 | Web 推荐卡片 + 猜你喜欢；专辑页补主播/播放量/主播放按钮 |
| M2 分类 | ✅ 完成 | 29 分类、子类、3 种排序、`metas` 筛选、分页（20/页） |
| M3 榜单 | ✅ 完成 | 6 榜单 + 类目切换（含主播势力榜的锚点行） |
| M4 主播 | ✅ 完成 | 6 主播榜 + 详情（资料 + 前 15 张专辑，注明总数） |
| M5 个性化 | ✅ 结构完成 | 设置开关、身份 Cookie、按身份分键缓存；**效果未验证（无测试账号）** |
| M6 广播 + 播放 | ✅ 完成 | 电台 HLS + 直播间 `liveId` 取流；直播模式旁路队列/seek/历史 |
| M7 收尾 | ⚠️ 部分 | 构建/lint/契约测试通过；**无真机，instrumentation 未执行** |

### 14.2 验证结果

- `:protocol:testDebugUnitTest`：18 个 fixture 契约测试通过。
- 线上 API 全链路冒烟（临时测试，未提交）：
  - 推荐 5 个分类卡片 + 52 条猜你喜欢；
  - 分类 29 个、历史 24 子类 / 5 筛选维度；`metas=131_2560-132_2722` 返回 20 条且全部「已完本 + 免费」；
  - 榜单 6 组、热播榜 27 类目 / 97 条，主播榜 100 位；
  - 主播「有声的紫襟」资料正常、专辑 15/120；
  - 电台 3 模块 / 27 个电台，`https` m3u8 可访问；
  - 直播间 3 个，详情 `alive=true`，`liveId` 取流返回 `https` m3u8。
- `:app:assembleDebug`、`:app:assembleDebugAndroidTest`、`:app:lintDebug` 均通过。

### 14.3 与设计稿的有意偏差

| 项 | 偏差 | 原因 |
|---|---|---|
| 「换一换」位置 | 放在「猜你喜欢」分区头，而非标题栏 | 避免把推荐刷新与全局搜索并列；语义更贴近被替换的内容 |
| 页面边距 | 20dp（沿用 App 现有页面），非 16dp | 与收听/我的两页保持一致 |
| 直播间起播 | 进入页面后点「播放直播」，非自动起播 | 与全局播放器共用音频焦点，显式点击更可控（仍 ≤2 次点击） |
| 分类首屏 | 用 `queryCategoryPage` 的 30 条中取前 20，第 2 页从 21 开始 | 避免 30/20 页长混用造成重叠 |
| 完结徽标 | 仅分类接口（`albumFinishTag==2`）显示 | 推荐接口的 `isFinished` 语义未验证，不猜 |
| 听单 | 不渲染 | 无可用详情接口 |
| 主播专辑 | 只显示前 15 张并注明总数 | `/revision/user` 无分页参数 |

### 14.4 未完成 / 需要真机

- `DiscoveryUiVerification`（五 Tab、分类下钻、无广告断言）已写好但未执行。
- 直播/电台的真机播放（ExoPlayer HLS）未验证，仅验证到取流 URL 层。
- 200% 字体、TalkBack、深色模式的人工检查未执行。
- 个性化开关的账号态效果未验证（无测试账号）。
