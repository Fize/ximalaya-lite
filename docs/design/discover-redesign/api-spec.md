# 发现页接口探测报告

> 目的：为「推荐 / 分类 / 广播 / 榜单 / 主播」五项功能提供可复现的接口与调用方式。
> 原则：**只记录实测结果**；未验证、不可用、服务端异常一律显式标注，不补全、不推测。
> 时间：2026-11-26（UTC+8）。全部请求来自本机公网直连，未登录、无 Cookie。
> UA：移动端接口 `ting_9.4.74.3(Android)`；M 站接口 `Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 ...)`；Web 接口 `Mozilla/5.0 (Macintosh ... Chrome/120)`。

---

## 1. 总览

| 域 | 端点 | 实测结果 | 可播放 |
|---|---|---|---|
| 推荐（移动） | `GET mobile.ximalaya.com/mobile/discovery/v1/recommend/album` | 200；11 个模块槽位，其中 4 个为空 | 专辑可播 |
| 推荐（Web 富数据） | `GET www.ximalaya.com/revision/explore/v2/getRecommend` | 200；5 个分类卡片，每卡 23–31 张专辑 | 专辑可播 |
| 推荐（Web 猜你喜欢） | `GET www.ximalaya.com/revision/explore/guessYouLike` | 200；固定 52 条，无分页 | 专辑可播 |
| 分类树 | `GET www.ximalaya.com/revision/category/allCategoryInfo` | 200；5 组 / 28 个分类 | — |
| 分类导航（含 pinyin） | `GET www.ximalaya.com/revision/category/breadcrumbCategoryInfo?categoryId=9` | 200；29 个分类，含 `pinyin` / `link` | — |
| 分类首页 | `GET m.ximalaya.com/m-revision/page/category/queryCategoryPage` | 200；子类 + 首屏 30 条 + 筛选维度 | 可播 |
| 分类分页 | `GET m.ximalaya.com/m-revision/page/category/queryCategoryAlbumsByPage` | 200；`page` 生效 | 可播 |
| 榜单分组 | `GET m.ximalaya.com/m-revision/page/rank/queryRankCluster` | 200；6 个榜单 | — |
| 榜单分组明细 | `GET m.ximalaya.com/m-revision/page/rank/queryRankClusterDetail` | 200；分组 → 类目 → `rankId` | — |
| 榜单内容 | `GET m.ximalaya.com/m-revision/page/rank/queryRank` | 200；`hotplay` 热门 97 条 | 可播 |
| 主播榜 | 同上，`clusterCode=anchorInfluence` | 200；6 个子榜，每榜最多 100 位 | 可播 |
| 主播资料 | `GET www.ximalaya.com/revision/user/basic?uid=` | 200 | — |
| 主播专辑 | `GET www.ximalaya.com/revision/user?uid=` | 200；每次 15 张（总数 120），**无分页** | 可播 |
| 电台首页 | `GET live.ximalaya.com/radio-first-page-app/homePage` | 200；3 个模块 / 27 个电台，含 `playUrl` | **HLS 可播** |
| 直播间详情 | `GET live.ximalaya.com/lamia/v12/live/room?roomId=` | 200；无推流字段 | **未找到推流** |
| 听单详情 | — | **未找到** | 不可用 |
| 专辑详情 | `GET www.ximalaya.com/revision/album?albumId=` | 200；简介 / 主播 / 播放量 / 首屏 30 集 | 可播 |
| 播放地址 | `GET mobile.ximalaya.com/v1/track/baseInfo?trackId=` | 200；`playUrl64/32` | 已验证 |

---

## 2. 通用约定

### 2.1 主机

| 主机 | 用途 |
|---|---|
| `mobile.ximalaya.com` | 移动端主接口（推荐流、节目列表、`baseInfo`） |
| `www.ximalaya.com` | Web 接口（分类树、专辑详情、用户/主播、推荐卡片） |
| `m.ximalaya.com` | M 站接口（分类页、榜单） |
| `live.ximalaya.com` | 电台 / 直播（`radio-first-page-app`、`lamia`） |
| `search.ximalaya.com` | 搜索（现有实现） |

### 2.2 返回形态（三种，不能混用）

| 形态 | 示例 | 成功判定 |
|---|---|---|
| 移动 | `{"ret":0,"msg":"成功","list":[...]}` | `ret == 0` |
| M 站 | `{"ret":0,"data":{...}}` | `ret == 0` |
| Web | `{"ret":200,"data":{...}}` | `ret == 200` |
| 电台 | `{"ret":0,"msg":null,"data":{...}}`；节目单为 `{"ret":"0000","result":{...}}` | `ret == 0` / `"0000"` |

### 2.3 必填与忽略参数（实测）

- 移动推荐流缺少 `includeActivity` 或 `includeSpecial` 会直接 400（Tomcat 报 `Required Boolean parameter ... is not present`）。
- 移动推荐流的 `pageSize` **被忽略**：固定 `guessYouLike=6`、`playlist=5`、`live=3`、`categoriesForExplore=3`、`cityCategory=3`。
- Web `getRecommend` 的 `tab` / `categoryId` / `page` **被忽略**：始终返回同样的 5 个分类，条目数每次随机 23–31。
- Web `guessYouLike` 的 `page` / `pageSize` **被忽略**：始终返回同一批 52 条。
- M 站 `queryCategoryPage` 的 `subCategoryId`、`metaDataId`、`metaDataIds`、`metaData` **均被忽略**；有效参数是 `subCategoryCode` 与 `metas`（见 §4.5）。

### 2.4 图片

- 返回值常见 `http://`、`//` 前缀；统一升级为 `https://`。
- 封面后缀形如 `!op_type=5&upload_type=cover&device_type=ios&name=large`，可原样拼接。

---

## 3. 推荐

### 3.1 移动端推荐流（与 App 同源）

```
GET https://mobile.ximalaya.com/mobile/discovery/v1/recommend/album
  ?device=android
  &pageId=1
  &pageSize=6
  &includeActivity=false
  &includeSpecial=false
```

模块顺序与实测条数（匿名）：

| # | moduleType | 条数 | 说明 |
|---|---|---|---|
| 1 | `focus` | 0 | 焦点图槽位，匿名返回空 |
| 2 | `square` | 0 | 运营方块槽位，匿名返回空 |
| 3 | `guessYouLike` | 6 | 猜你喜欢 |
| 4 | `ad` | 0 | **广告槽位** |
| 5 | `playlist` | 5 | 精品听单 |
| 6 | `ad` | 0 | 广告槽位 |
| 7 | `live` | 3 | 直播间 |
| 8 | `categoriesForExplore` | 3 | 分类模块（如「相声评书」） |
| 9 | `cityCategory` | 3 | 本地模块（如「听北京」） |
| 10 | `ad` | 0 | 广告槽位 |
| 11 | `recommendAlbum` | 0 | 为你推荐槽位，匿名返回空 |

**`pageId` 只轮换，不是分页**：连续取 1–5 页，`guessYouLike` 共 30 条、去重后 **仅 10 张专辑**；相邻两页重叠 2–5 条。因此不能做无限流。

字段：

- `guessYouLike[]`：`albumId` `title` `pic` `playsCount` `tracksCount` `isPaid` `isVipFree` `isFinished` `subtitle` `recSrc` `recTrack` `lastUptrackAt`
  - **没有 `nickname` / 主播名**，卡片副行不能强制显示主播。
- `categoriesForExplore[]` / `cityCategory[]`：`albumId` `title` `pic` `playsCount` `tracksCount` `isPaid` `isVipFree` `isFinished` `subtitle`
- `playlist[]`：`specialId` `title` `subtitle` `coverPath` `footnote` `columnType` `contentType`
- `live[]`：`roomId` `id` `name` `nickname` `uid` `chatId` `playCount` `status` `categoryId` `categoryName` `coverSmall/Middle/Large` `tingUrl` `startAt` `endAt`
- `ad` / `focus` / `square` / `recommendAlbum`：当前均为空数组，字段结构无法观测。

### 3.2 Web 推荐卡片（建议作为推荐主数据源）

```
GET https://www.ximalaya.com/revision/explore/v2/getRecommend
```

返回 `data.cards[5]`，固定 5 个分类：

| title | name | albumList 条数（3 次采样） | rankTitle |
|---|---|---|---|
| 有声书 | `youshengshu` | 27 / 29 / 23 | 有声书新品榜 |
| 相声评书 | `xiangsheng` | 25 / 29 / 23 | 相声评书新品榜 |
| 儿童 | `ertong` | 31 / 30 / 31 | 儿童新品榜 |
| 头条 | `toutiao` | 28 / 26 / 29 | 头条新品榜 |
| 娱乐 | `yule` | 27 / 29 / 28 | 娱乐新品榜 |

> `name` 即分类 pinyin，可直接拼到 `moreUrl`（`/youshengshu`、`/xiangsheng` …）；代码中按返回值透传，不自行映射。

卡片字段：

- `categoryId` `title` `name` `moreUrl`（如 `/youshengshu`）`hotWord[]` `rankTitle` `soar[]` `hotWordSearchType` `subCategoryList[]`
- `albumList[]`：`albumId` `albumTitle` `albumCoverPath` `albumUserNickName`（**主播名**）`albumPlayCount` `albumTrackCount` `anchorId` `anchorGrade` `mvpGrade` `isPaid` `isFinished` `anchorUrl` `albumUrl` `intro` `vipType` `albumSubscript`
- `soar[]`：新书/飙升条目，字段含 `albumTitle` `albumUrl` `cover` `anchorUrl` `playCount` `trackCount`

### 3.3 Web 猜你喜欢

```
GET https://www.ximalaya.com/revision/explore/guessYouLike
```

- 返回 `data.count = 52`，`recommendInfoList[52]`，**无分页**（page 1/2/3 内容完全一致）。
- 字段与 §3.2 的 `albumList` 基本一致，**含 `albumUserNickName`（主播名）**，可直接用于卡片副行。

### 3.4 广告处理

- 移动流中 `moduleType == "ad"` 出现 3 次，匿名响应均为空数组；仍必须按 `moduleType` 白名单过滤，不能依赖“内容为空”。
- `focus` / `square` 为运营位，匿名返回空；实现时只渲染可播放内容类型（专辑/听单/直播），其余丢弃。

---

## 4. 分类

### 4.1 分类树

```
GET https://www.ximalaya.com/revision/category/allCategoryInfo
GET https://www.ximalaya.com/revision/category/v2/allCategory
```

- 5 个分组：有声小说 / 娱乐 / 知识 / 生活 / 特色
- 28 个内容分类；`name` 字段是 Web slug（如 `culture`），**不是**分类接口用的 code。

### 4.2 分类导航（pinyin，实际可用）

```
GET https://www.ximalaya.com/revision/category/breadcrumbCategoryInfo?categoryId=9
```

返回 29 个分类，含 `id` `displayName` `pinyin` `link` `name`。`pinyin` 才是分类页接口接受的 `categoryCode`：

| id | displayName | pinyin | link |
|---|---|---|---|
| 3 | 有声书 | `youshengshu` | `/youshengshu/` |
| 2 | 音乐 | `yinyue` | `/yinyue/` |
| 12 | 相声评书 | `xiangsheng` | `/xiangsheng/` |
| 48 | 段子 | `duanzi` | `/duanzi/` |
| 10 | 情感生活 | `qinggan` | `/qinggan/` |
| 4 | 娱乐 | `yule` | `/yule/` |
| 23 | 影视 | `yingshi` | `/yingshi/` |
| 6 | 儿童 | `ertong` | `/ertong/` |
| 9 | 历史 | `lishi` | `/lishi/` |
| 8 | 商业财经 | `shangye` | `/shangye/` |
| 39 | 人文 | `renwen` | `/renwen/` |
| 13 | 个人成长 | `gerenchengzhang` | `/gerenchengzhang/` |
| 18 | IT科技 | `keji` | `/keji/` |
| 5 | 外语 | `waiyu` | `/waiyu/` |
| 92 | 少儿素养 | `ertongjiaoyu` | `/ertongjiaoyu/` |
| 1 | 头条 | `toutiao` | `/toutiao/` |
| 24 | 二次元 | `erciyuan` | `/erciyuan/` |
| 16 | 戏曲 | `xiqu` | `/xiqu/` |
| 22 | 旅游 | `lvyou` | `/lvyou/` |
| 7 | 健康养生 | `jiankang` | `/jiankang/` |
| 31 | 时尚生活 | `shishang` | `/shishang/` |
| 15 | 广播剧 | `guangbojv` | `/guangbojv/` |
| 44 | 粤语 | `yueyu` | `/yueyu/` |
| 50 | 法律课堂 | `falvketang` | `/falvketang/` |
| 17 | 教育培训 | `jiaoyupeixun` | `/jiaoyupeixun/` |
| 41 | 党员学习园地 | `jiandangbainian` | `/jiandangbainian/` |
| 42 | 品牌之声 | `pinpai` | `/pinpai/` |
| 21 | 汽车 | `qiche` | `/qiche/` |
| 84 | 闽南语 | `minnanyu` | `/minnanyu/` |

**关键实测**：`categoryCode=culture`（slug）返回 `categoryId=0`、空列表；`categoryCode=lishi`（pinyin）返回 `categoryId=9`、24 个子类。**必须用 pinyin。**

### 4.3 分类首页

```
GET https://m.ximalaya.com/m-revision/page/category/queryCategoryPage
  ?categoryCode=lishi
  &page=1
  &pageSize=30        # 实测 20/30 均可；M 站默认 30
  &sort=0
  [&subCategoryCode=zhengshi]
```

响应 `data`：

- `categoryId` `categoryCode` `categoryDisplayName`
- `subCategories[]`：`subCategoryId` `code` `displayValue` `metaDataId` `metaDataValue` `position` `isKeyword`
  - 历史分类 24 个：正史 / 野史 / 军事 / 战争 / 名人 / 纪实 / 传记 / 故事 …
- `firstPageCategoryAlbums`：`page` `pageSize` `totalCount`（历史固定 1000）`sort` `albumBriefDetailInfos[]`
- `categoryMetaDatas[]`：5 个筛选维度（见 §4.5）

### 4.4 分页

```
GET https://m.ximalaya.com/m-revision/page/category/queryCategoryAlbumsByPage
  ?categoryCode=lishi&page=2&pageSize=30&sort=0
```

- `page` 生效：page 1 与 page 2 首条分别为「明朝那些事儿…」「东方巨人毛泽东…」。
- `totalCount` 固定 1000（上限值，不等于真实总数）。

### 4.5 排序

| sort | 实测行为 | 证据 |
|---|---|---|
| 0 | 综合排序 | M 站 UI 文案「综合排序」，默认值 |
| 1 | 最近更新 | 点击「最近更新」后请求 `sort=1`；返回条目多为连载中 |
| 2 | 最多播放 | `sort=2` 的 `playCount` 严格降序（1572801785、1304342498、1201834638…）；M 站链接 `/lishi/mostplays/` |

### 4.6 筛选

格式：`metas={维度id}_{值id}`，多个用 `-` 连接。

```
GET .../queryCategoryAlbumsByPage?categoryCode=lishi&page=1&pageSize=30&sort=1
  &metas=131_2560-132_2722
```

实测结果全部为「已完本 + 免费」（`albumFinishTag=2`、`albumPayType=0`）。

历史分类的 5 个维度与可选值：

| 维度 id | 名称 | 值示例（id/显示名） |
|---|---|---|
| 10 | 节目形式 | 623 读书、624 朗诵、626 赏析 … |
| 3 | 主播 | 1285 易中天、1287 王立群、1288 纪连海 … |
| 26 | 年代 | 1174 史前、1165 夏商周、333 春秋战国 … |
| 131 | 是否完本 | 2559 连载中、2560 已完本 |
| 132 | 是否付费 | 2721 付费、2722 免费 |

> 维度与取值随分类变化，必须从 `categoryMetaDatas` 动态渲染，不能写死。

### 4.7 分类专辑字段

`albumBriefDetailInfos[]`：

- `albumInfo`：`id` `title` `cover` `customTitle` `shortIntro` `price` `discountedPrice` `salePoint` `recommendReason` `albumFinishTag`（1 连载 / 2 完结）`albumPayType`（0 免费 / 1 付费）`albumVipPayType` `isVipFirst` `status` `categoryId`
- `anchorInfo`：`id` `nickname` `logo` `verifyStatus` `verifyType` `anchorGrade` `personDescribe` `personalSignature`
- `statCountInfo`：`playCount` `trackCount`
- `pageUriInfo`：`categoryCode`（pinyin）`url`

---

## 5. 榜单

### 5.1 榜单分组

```
GET https://m.ximalaya.com/m-revision/page/rank/queryRankCluster
```

| clusterType | clusterCode | clusterName |
|---|---|---|
| 12 | `hotplay` | 热播榜 |
| 14 | `newRanking` | 新品榜 |
| 13 | `hotSale` | 畅销榜 |
| 15 | `publicPraise` | 口碑榜 |
| 17 | `anchorInfluence` | 主播势力榜 |
| 19 | `traditionalChineseCulture` | 国学榜 |

### 5.2 分组 → 类目 → rankId

```
GET https://m.ximalaya.com/m-revision/page/rank/queryRankClusterDetail
```

每个分组返回 `categoryInfos[]`：`rankClusterId` `rankId` `clusterType` `title` `categoryId` `categoryCode` `position`。

例（热播榜 27 个类目）：

| title | categoryCode | rankId |
|---|---|---|
| 热门 | `all` | 47 |
| 有声书 | `youshengshu` | 48 |
| 相声评书 | `xiangsheng` | 49 |
| 儿童 | `ertong` | 50 |
| 人文 | `renwen` | 51 |
| 历史 | `lishi` | 52 |

主播势力榜 6 个子榜：

| title | categoryCode | rankId |
|---|---|---|
| 有声书主播 | `voiceActor74` | 95 |
| 畅销书主播 | `hallOfFame576` | 346 |
| 直播红人 | `livePopularPerson75` | 97 |
| 人气电台 | `entertainment76` | 98 |
| 新锐主播 | `newFineQuality77` | 99 |
| Ximi主播榜 | `voiceActor544` | 383 |

### 5.3 榜单内容

```
GET https://m.ximalaya.com/m-revision/page/rank/queryRank
  ?clusterCode=hotplay
  &categoryCode=all
```

- `categoryCode` **必填且必须是有效值**；传空字符串返回空模块（`rankId=0`）。
- 响应：`data.title`（如「热播榜」「历史热播榜」）、`data.rankModuleInfoList[]`（热门 97 条、历史 85 条），无分页字段。
- 专辑榜条目字段：`id` `albumInfo` `statCountInfo` `pageUriInfo`
  - **没有 `anchorInfo`**：榜单卡片无法直接显示主播名。
- 主播榜（`anchorInfluence`）条目字段：`id` `userInfo` `statCountInfo` `url`
  - `userInfo`：`nickname` `logo` `verifyStatus` `verifyType` `anchorGrade` `personDescribe` `ptitle`
  - `statCountInfo`：`trackCount` `followerCount` `albumCount`
  - `url`：`/zhubo/{uid}`

---

## 6. 主播

### 6.1 主播榜（列表数据源）

见 §5.3：`clusterCode=anchorInfluence&categoryCode=voiceActor74` 等，每榜最多 100 条。

### 6.2 主播资料

```
GET https://www.ximalaya.com/revision/user/basic?uid=1266964
```

返回 `data`：`uid` `nickName` `cover` `background` `isVip` `constellationStr` 等（817 字节样本）。

### 6.3 主播的专辑

```
GET https://www.ximalaya.com/revision/user?uid=1266964
```

- `pubPageInfo`：`totalCount=120`，`pubInfoList` **固定 15 条**
- `trackPageInfo` / `subscriptionPageInfo` / `followingPageInfo` / `fansPageInfo`
- **分页无效**：`page` / `pageNum` / `pubPage` / `pageSize=30` 均返回同一批 15 条。只能展示前 15 张。
- `pubInfoList[]`：`id` `title` `subTitle` `coverPath` `isFinished` `isPaid` `anchorUrl` `anchorNickName` `anchorUid` `playCount` `trackCount` `albumUrl` `description` `vipType` `albumSubscript`

---

## 7. 广播

> 结论：**电台（radio）可播**，直播间（liveroom）推流未找到。

### 7.1 电台首页（含播放地址）

```
GET https://live.ximalaya.com/radio-first-page-app/homePage
```

响应 `data`：

- `modules[]`：
  | type | name | 实测条数 |
  |---|---|---|
  | `FOCUS` | 头部调频 | 14 |
  | `RECOMMEND` | 精品节目推荐 | 0（`albums` 字段存在，空） |
  | `SEARCH` | 找电台 | 10 + `categories[]` + `locations[]` |
  | `LOCAL` | 本地电台 | 3 |
- `location`：`"北京"`（服务端按 IP 判定，**无需定位权限**）
- `backgroundImg`

电台字段（每个模块的 `radios[]`）：

```
id, name, coverSmall, coverLarge, programScheduleId, programId, programName,
start, end, nextStart, nextEnd, nextProgramName, playCount, playUrl,
fmUid, type, sort, isFavorite, categoryId, categoryName, categoryRank
```

`playUrl` 实测值（沈阳之声 328）：

```json
{
  "aac24": "http://live.ximalaya.com/radio-first-page-app/live/328/24.m3u8",
  "ts24":  "http://live.ximalaya.com/radio-first-page-app/live/328/24.m3u8?transcode=ts",
  "aac64": "http://live.ximalaya.com/radio-first-page-app/live/328/64.m3u8",
  "ts64":  "http://live.ximalaya.com/radio-first-page-app/live/328/64.m3u8?transcode=ts"
}
```

### 7.2 播放验证

- `https://live.ximalaya.com/radio-first-page-app/live/328/64.m3u8` → `200 application/vnd.apple.mpegurl`
- 内容为 media playlist：`#EXT-X-VERSION:3` / `#EXT-X-TARGETDURATION:7` / 分片 `https://broadcast.tx.xmcdn.com/live/328_64_261126_000015_*.aac`
- 抽查 328 / 1011 / 1789 / 966 / 534 五个电台，全部返回有效 `#EXTM3U`。
- 注意：并非所有 id 都有流。`radioId=267` 返回 `{"msg":"电台流获取失败，请稍后再试","ret":2011}`，因此播放失败必须可恢复。

### 7.3 节目单

```
GET https://live.ximalaya.com/live-web/v1/getProgramDetail?device=ios&radioId=328
```

返回 `{"ret":"0000","result":{announcerList, startTime, endTime, playBackgroundPic, ...}}`（注意 `ret` 是字符串）。

### 7.4 电台搜索 / 分类列表

- `GET .../radio-first-page-app/search?keyword=…` → **服务端异常**：`{"ret":-1,"msg":"内部错误"}`（换 `kw` / `name` / `searchKey` / `categoryId` 均同样返回）。
- `categoryId` / `locationId` 传给 `homePage` **被忽略**，返回同一份内容。
- 未找到“按分类/地区列电台”的有效接口。

### 7.5 直播间

列表：移动推荐流 `live` 模块（§3.1），固定 3 条，`pageId` 不变。

详情：

```
GET https://live.ximalaya.com/lamia/v12/live/room?roomId=382985
```

响应 `data` 关键字段：

- `recordInfo`：`roomId` `id` `name` `description` `cover*` `alive` `status` `mediaType` `bizType` `onlineCount` `playCount` `startAt` `endAt` `fmId` `h5Url` `itingUrl`
- `userInfo`：`uid` `nickname` `avatar` `description` `isFollowed`
- `chatRoomVo`、`fansClubVo`、`rankInfos` 等

**没有推流地址字段**。直播间 H5（`https://m.ximalaya.com/gatekeeper/live-room-h5?roomId=…`）只请求该详情接口，随后跳转 `iting://open?msg_type=52&live_type=0&liveroom_id=…`（App 内协议）。

已尝试且全部失败（404 / 空）：

- `live.ximalaya.com/live-web/v1/getLiveInfo|getLiveStream|getLiveStreamUrl|getLiveUrl|room|getRoomInfo|...`
- `live.ximalaya.com/lamia/v12/live/room/list`、`/lamia/v1/live/recommend/room/list`
- `mobile.ximalaya.com/mobile/discovery/v1/live/list`
- `live.ximalaya.com/radio-first-page-app/live/{roomId}/64.m3u8`（返回 `ret 2011`）

结论：**直播间推流协议无法从公开 Web / 接口侧确认**；H5 不提供播放器，只做 App 唤起。需要官方 APK 进一步分析（见 §9）。

---

## 8. 播放链路

### 8.1 专辑 → 节目 → 播放地址（移动端，现有实现）

1. 节目列表：`GET mobile.ximalaya.com/mobile/v1/album/track/...&albumId=&pageId=&pageSize=20`
2. 单集元数据 + 播放地址：`GET mobile.ximalaya.com/v1/track/baseInfo?trackId=&device=android`
3. 实测（专辑 70781065 / 单集 566751993）：`ret=0`、`isPublic=true`、`playUrl64=http://aod.cos.tx.xmcdn.com/...mp3`
4. 另一例（榜单专辑 9723091 / 单集 45982355）：`playUrl64=http://aod.cos.tx.xmcdn.com/...mp3`

### 8.2 专辑详情（Web，可补主播/简介/播放量）

```
GET https://www.ximalaya.com/revision/album?albumId=70781065
```

- `mainInfo`：`albumTitle` `cover` `playCount` `isPaid` `isFinished` `updateDate` `createDate` `richIntro` `shortIntro` `crumbs{categoryId, categoryPinyin, categoryTitle, subcategoryCode,...}`
- `anchorInfo`：`anchorId` `anchorName` `anchorCover` `anchorFansCount` `anchorAlbumsCount` `anchorTracksCount` `personalIntroduction` `anchorAlbumList[]`
- `tracksInfo`：`trackTotalCount=120` `pageNum=1` `pageSize=30` `tracks[30]`
  - 单集：`trackId` `title` `duration` `isPaid` `playCount` `url`

### 8.3 电台

- 直接播放 `playUrl.aac64`（HLS，AAC 分片）；`https` 可用。
- 直播流不支持进度/倍速；电台为线性流，同样不可拖动。

---

## 9. 未解决 / 未验证

| 项 | 状态 | 已尝试 | 影响 |
|---|---|---|---|
| 直播间推流 | **未解决** | §7.5 列出的路径、H5 抓包、Mac 客户端代码 | 广播 tab 若含直播间，无法播放 |
| 听单详情 | **未解决** | `specialId=1283` 在 `mobile/v1/special/album/list`、`/revision/topic/detailSpecial`、`/m-revision/page/column/*` 均失败/无数据 | 推荐页「精品听单」模块只能隐藏 |
| 个性化效果 | **未验证** | 匿名请求无法验证；无账号 Cookie | 「个性化开关」只能按“是否携带身份”实现并标注未验证 |
| `focus` / `square` / `recommendAlbum` / `ad` 内容 | **未验证** | 匿名 + `includeActivity/includeSpecial` 四种组合均为空 | 只能按白名单渲染，不能假设字段 |
| 主播专辑分页 | **不可用** | `page`/`pageNum`/`pubPage`/`pageSize` 均无效 | 主播页最多展示 15 张专辑 |
| 电台搜索/分类筛选 | **服务端异常** | 多参数组合均 `ret=-1 内部错误` | 广播 tab 只能展示 `homePage` 的 3 个模块 |
| 广告内容结构 | **未观测** | 匿名 ad 模块为空 | 过滤按 `moduleType`，不解析内容 |

---

## 10. 对现有设计的修订建议（基于实测）

1. **推荐主数据源改用 Web 富数据**：`explore/v2/getRecommend`（5 分类 × 20+ 专辑，含主播名/播放量）+ `explore/guessYouLike`（52 条，含主播名）。移动端推荐流作为兜底，且不做无限流（仅 10 张唯一专辑）。
2. **分类导航用 29 个 pinyin 分类**（含「党员学习园地」），不要用 `allCategoryInfo.name`（slug 无效）。
3. **分类排序只有 3 个**：综合 / 最近更新 / 最多播放；筛选用 `metas` 动态渲染。
4. **榜单不做分页**（一次 97–100 条），类目切换用 `queryRankClusterDetail` 的 `categoryCode`。
5. **主播**：列表用 `anchorInfluence` 的 6 个子榜；详情用 `/revision/user/basic` + `/revision/user`（最多 15 张专辑）。
6. **广播改为“电台”优先**：`homePage` + `playUrl.aac64` 可播；直播间只能展示，不能播。若必须播放，需继续逆向 APK。
7. **听单模块隐藏**（`playlist`）；不要给不可用入口。
8. **专辑页可补全元数据**：`/revision/album` 提供主播、简介、播放量、首屏 30 集。

---

## 11. 证据记录

| 时间 (UTC+8) | 请求 | 结果 |
|---|---|---|
| 16:38 | `GET live.ximalaya.com/live-web/v1/getLiveList` | 404，`path=/radio-first-page-app/v1/getLiveList` |
| 16:38 | `GET live.ximalaya.com/live-web/v1/radio?radioId=382985` | 200，字段全 null |
| 16:39 | `GET live.ximalaya.com/radio-first-page-app/live/2629/64.m3u8` | 200，`application/vnd.apple.mpegurl`，AAC 分片 |
| 16:39 | `GET live.ximalaya.com/radio-first-page-app/live/267/64.m3u8` | 200，`ret=2011 电台流获取失败` |
| 16:44 | Playwright 打开 `www.ximalaya.com/zhubo/1266964` | 请求 `/revision/user/basic?uid=`、`/revision/user?uid=` |
| 16:45 | `GET www.ximalaya.com/revision/user?uid=1266964` | 200，`pubPageInfo.totalCount=120`，列表 15 条 |
| 16:46 | Playwright 打开直播间 H5 | 请求 `m.ximalaya.com/lamia/v12/live/room?roomId=`，随后 `iting://` |
| 16:47 | `GET www.ximalaya.com/revision/explore/v2/getRecommend` | 200，5 卡片，23–31 条/卡 |
| 16:47 | `GET m.ximalaya.com/m-revision/page/rank/queryRankCluster` | 200，6 个榜单 |
| 16:48 | `GET m.ximalaya.com/m-revision/page/rank/queryRankClusterDetail` | 200，热播榜 27 类目 |
| 16:48 | `GET m.ximalaya.com/m-revision/page/rank/queryRank?clusterCode=hotplay&categoryCode=all` | 200，97 条 |
| 16:49 | Playwright 点击「最近更新」「筛选」 | 捕获 `sort=1`、`metas=131_2560-132_2722` |
| 16:50 | `GET m.ximalaya.com/m-revision/page/category/queryCategoryAlbumsByPage?...&metas=131_2560-132_2722` | 200，全部 `albumFinishTag=2, albumPayType=0` |
| 16:52 | `GET live.ximalaya.com/radio-first-page-app/homePage` | 200，3 模块 27 电台，含 `playUrl` |
| 16:52 | `GET .../live/328/64.m3u8`（https） | 200，有效 HLS |
| 16:53 | `GET www.ximalaya.com/revision/album?albumId=70781065` | 200，`mainInfo`/`anchorInfo`/`tracksInfo` |
| 16:53 | `GET mobile.ximalaya.com/v1/track/baseInfo?trackId=566751993` | 200，`playUrl64` 存在 |

---

## 12. 复现命令

```bash
# 推荐（移动）
curl -sS --compressed -A 'ting_9.4.74.3(Android)' \
  'https://mobile.ximalaya.com/mobile/discovery/v1/recommend/album?device=android&pageId=1&pageSize=6&includeActivity=false&includeSpecial=false'

# 推荐（Web）
curl -sS --compressed -A 'Mozilla/5.0' \
  'https://www.ximalaya.com/revision/explore/v2/getRecommend'

# 分类导航
curl -sS --compressed -A 'Mozilla/5.0' \
  'https://www.ximalaya.com/revision/category/breadcrumbCategoryInfo?categoryId=9'

# 分类筛选
curl -sS --compressed -A 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)' \
  'https://m.ximalaya.com/m-revision/page/category/queryCategoryAlbumsByPage?categoryCode=lishi&page=1&pageSize=30&sort=1&metas=131_2560-132_2722'

# 榜单
curl -sS --compressed -A 'Mozilla/5.0' \
  'https://m.ximalaya.com/m-revision/page/rank/queryRank?clusterCode=hotplay&categoryCode=all'

# 主播榜
curl -sS --compressed -A 'Mozilla/5.0' \
  'https://m.ximalaya.com/m-revision/page/rank/queryRank?clusterCode=anchorInfluence&categoryCode=voiceActor74'

# 电台首页 + 推流
curl -sS --compressed -A 'ting_9.4.74.3(Android)' \
  'https://live.ximalaya.com/radio-first-page-app/homePage'
curl -sS -A 'ting_9.4.74.3(Android)' \
  'https://live.ximalaya.com/radio-first-page-app/live/328/64.m3u8'

# 直播间详情（无推流）
curl -sS --compressed -A 'ting_9.4.74.3(Android)' \
  'https://live.ximalaya.com/lamia/v12/live/room?roomId=382985'

# 专辑详情 + 播放地址
curl -sS --compressed -A 'Mozilla/5.0' \
  'https://www.ximalaya.com/revision/album?albumId=70781065'
curl -sS --compressed -A 'ting_9.4.74.3(Android)' \
  'https://mobile.ximalaya.com/v1/track/baseInfo?trackId=566751993&device=android'
```
