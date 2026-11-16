# 喜马拉雅 APK 详细静态逆向分析报告

分析对象：`ximalaya_app.apk` · 版本 `9.4.74.3` · 日期：2026-11-04

本报告面向有开发经验的读者，重点回答主要功能调用哪些接口、登录认证如何完成与保持、广告如何请求和处理。按已确认范围，仅做 APK 静态分析；结论均以本地样本为边界。

> 后续更新：用户追加授权后，已补充 SO 逆向、离线 JNI 测试及精简候选模拟器验证，详见[SO 排查与候选结果](../implementation/排查与自建方案.md)。本文其余内容保留原静态分析证据边界；真实账号登录、支付与领奖仍未测试。

## 主要发现

- **核心播放是“业务元数据/授权 → 加密播放地址解析 → 音质选择 → CDN”链路。** v3/v4 播放页接口并存，另有 mpay 防盗链路径；登录、内容授权、业务 ticket 和媒体 URL 凭据是不同层。
- **登录后保存 uid/token，依靠自定义 Cookie 携带身份。** 支持短信、密码、第三方及运营商一键登录；手机号/密码转换和 signature 有 JNI 边界。重启从本地恢复，会话真实性仍由服务端判断。
- **token 刷新不是固定每24小时的后台定时任务。** 可见路径在恢复前台时检查间隔，而且每次检查先更新时间戳；失效检查、清退与主动退出另有流程。
- **广告由宿主业务层和广告聚合 SDK 共同处理。** 有自有 ADX 及 GDT/CSJ/JAD/Baidu 适配，开屏、信息流、声音贴片、激励的请求和完成条件不同；免广告是服务端规则、用户/内容条件及设置共同决定，不能推为 VIP 全位置免广告。
- **静态分析不能证明接口当前可用或账号权益。** native 加密内部、token/媒体地址的服务端有效期、线上 AB 值、真实展示/奖励到账均未验证。部分反编译方法存在警告，相关结论已限制到可互证范围。

阅读顺序：一、样本与网络基础；二、主要功能与接口；三、认证与会话保持；四、广告子系统；五、复核与验收。

**代码定位约定**：完整 `com/...` 路径相对 `analysis/jadx/sources/`；省略包名前缀的 `host/...`、`loginservice/...`、`opensdk/...`、`adsdk/...`、`main/...`、`search/...` 等相对 `analysis/jadx/sources/com/ximalaya/ting/android/`。CRM 表示 `host/manager/request/CommonRequestM.java`，URL 表示 `host/util/e/g.java`。行号对应本次保留的反编译结果，不能直接套用其他版本。接口示意中的 `…` 表示未提供实际凭据。

## 一、样本与网络基础

### 样本身份与分析边界

本次仅分析用户提供的 `ximalaya_app.apk`，分析日期为 2026-11-04；不代表喜马拉雅当前所有客户端或服务器版本。未安装或运行 APK，未登录账号、发送验证码、支付、请求业务接口或抓取线上流量。

| 项目 | 静态观察结果 |
|---|---|
| 应用名称 | 喜马拉雅 |
| 包名 | `com.ximalaya.ting.android` |
| 版本 | `9.4.74.3`，versionCode `10687` |
| 大小 | 124,973,695 字节 |
| SHA-256 | `797532b8fe94a023f64b260e3996982e0712f02fa051485d261135433d0ffac1` |
| Android | minSdk 21，targetSdk 30，compileSdk 30 |
| 代码 | 15 个 DEX；JADX 输出 46,673 个 Java 文件，包含第三方库及生成代码，不能等同业务类数量 |
| 原生 ABI | `arm64-v8a` |
| Application / 启动 Activity | `xmloader.XMApplication` / `host.activity.MainActivity`（均省略包名前缀） |
| 签名证书主体 | `CN=ximalaya.com, OU=ximalaya.com, O=ximalaya.com, L=ShangHai, ST=ShangHai, C=CN` |
| 签名证书 SHA-256 | `f762a10658f7ff2b83053a2e850edbd0111b809ca387e4cdf86768140150366b` |

证书信息来自本地 `apksigner verify --print-certs`；没有与独立可信的官方发行样本比较，因此**不能仅凭主体名称认定安装包来源官方或未被第三方重新签名**。校验工具对部分 `META-INF/services/*` 条目给出未受 JAR 签名保护的提示，详见 `evidence/signature.txt`；这本身不等于已发现篡改。

证据分级：

- **静态证实**：清单/资源、请求构造、字段读写、可见调用关系中直接观察到；不等于该分支在本次线上环境执行过。
- **推断**：依据字段和调用关系解释用途；不能替代服务器实现或运行观察。
- **未验证**：实际响应、远端配置值、账号权益、失效期限、动态加载内容及原生算法内部。

工具及覆盖：REA 6.7.0 的 `inspect-artifact` 成功取得容器证据，ID 为 `ev_67bae302482ec32f93214d6d85ff385c760a02f3a6276bd2227d6e733f4874fc`，子证据 `ev_bfd01692908fa1afb98c64ca14e72b2af1cd3d6ac9c3fe32d74d6bb0a32249f1`。完整记录在 `evidence/rea-artifact.json`，摘要在 `evidence/rea-artifact-summary.json`。其范围仅是 APK 容器和成员身份，**以下代码语义不是 REA Android Provider 的结论**。

REA Android Provider 缺少 `jadx-headless-mcp 0.7.1`，诊断记录在 `evidence/rea-package.json`。因此使用官方 JADX 1.5.3 CLI 和本机 Android SDK 36.0.0 工具继续分析。JADX 完成全部处理进度，但报告 158 个错误；扫描输出发现 40 个文件含 51 处 `Method not decompiled`。这意味着不能声称全部代码恢复成功。详见 `evidence/jadx.log`、`evidence/decompilation-coverage.json`。反编译名称来自混淆名、JADX 重命名和 Kotlin 元数据恢复，不等同原作者源代码。

下文路径均相对本报告所在的 `analysis/`，例如 `jadx/sources/...java:123` 表示本地反编译文件第 123 行。接口列出的字段是已观察到的字段，不构成完整协议或可独立调用的保证。

### 网络基础与域名分工

应用按业务分服务，通过统一请求封装附带会话、设备和来源信息。可复核的公共请求链为：

```text
业务页面/管理器
  → CommonRequestM / 专项请求模块
  → opensdk.httputil.a（GET query、POST form/JSON）
  → CommonRequestM.addHeader（Cookie、User-Agent、业务头）
  → opensdk.httputil.b（BaseCall / OkHttp）
  → 已注册的拦截器、HTTPDNS/免流处理
  → 对应业务服务；播放媒体另外访问返回的 CDN 地址
```

证据：`jadx/sources/com/ximalaya/ting/android/opensdk/httputil/a.java:37`（GET），`:82`（表单 POST），`:66`（指定媒体类型 POST）；`host/manager/request/CommonRequestM.java:2658`（以下均在 `jadx/sources/com/ximalaya/ting/android/` 下）；`opensdk/httputil/b.java:80`（OkHttp 实例）；`host/manager/application/ApplicationManager.java:338`（注册网络组件）。

| 域名/域名族 | 本次追踪到的用途 | 证据位置 |
|---|---|---|
| `mobile.ximalaya.com` | 推荐、专辑、播放页、历史、权益以及配置等业务入口 | `host/util/e/g.java:84`、`:493`；各核心章节列具体调用 |
| `search.ximalaya.com` | 搜索及联想 | 核心功能章节 |
| `passport.ximalaya.com` | 登录、nonce、短信、会话刷新 | 认证章节 |
| `mpay.ximalaya.com` | 付费音频播放/下载地址信息，不能仅因含 pay 就理解成支付下单 | `host/util/e/g.java:70`；播放章节 |
| `audio.pay.xmcdn.com`、其他 `xmcdn.com` 返回地址 | 音频媒体分发；具体 CDN 由返回数据与解密流程决定 | `host/util/e/g.java:69`；播放章节 |
| `adse.ximalaya.com` | 广告聚合、预加载、贴片与广告位请求 | 广告章节 |
| `ad-incentive.ximalaya.com` | 广告激励业务 | `host/util/e/g.java:54`；广告章节 |
| `adbehavior.ximalaya.com`、`xdcs-collector.ximalaya.com` | 广告行为及通用埋点入口 | `host/util/e/g.java:76`、`:78`、`:664`；广告章节 |
| `live.ximalaya.com` | 直播业务 | `host/util/e/g.java:40`；核心功能章节 |
| `upload.ximalaya.com` | 上传入口 | `host/util/e/g.java:94`；`CommonRequestM.java:6423` |
| `m.ximalaya.com`、`hybrid.ximalaya.com` | H5/混合页面与业务承载 | `host/util/e/g.java:86`、`:90` |

**URL 使用限制**：APK 同时保留 HTTP、HTTPS、测试/UAT 主机和兼容接口。`host/util/e/g.java:443` 有环境切换，`:692` 对 passport 明确选择生产/测试/UAT；广告 HTTPS 又受 `key_ad_use_https` 控制（`:668`）。因此不能把所有字符串都当成当前线上流量，也不能断言全部接口始终 HTTPS。报告优先列主机、路径、构造方法及分支条件；时间戳路径中的 `{ts}` 是构造时的毫秒时间，不是实际抓包记录。

`CommonRequestM.addHeader` 在 `:2702` 写入 `Cookie`、`Cookie2: $version=1`、`Accept: */*`、`user-agent`，并允许调用方附加业务头；部分场景有 `x-open-thirdUid`、`x-open-appKey`（`:2720`）。这不表示所有独立第三方 SDK 都走这条链路。会话 Cookie 的构成及域名过滤见认证章节。

### 影响核心结论的配置及安全边界

1. **服务端配置与 AB 分流是行为的一部分。** `configurecenter/i.java:9`、`:21`、`:25`、`:35` 构造 `football-portal/diff3/batch`、`abtest-portal/diff`、`abtest-portal/sync/{ts}`、`facemask-portal/profile/features/{ts}`；`configurecenter/c.java:216` 可见配置请求发起。远端配置决定部分播放版本、广告策略与网络行为，APK 本身不能给出当前每位用户最终配置。
2. **TLS 校验有受配置控制的宽松分支。** `opensdk/httputil/d.java:17` 的 TrustManager 两个校验函数为空，`:42` 根据 `b.s` 在系统信任管理器/主机名校验与宽松分支之间选择；宽松分支的 HostnameVerifier 直接返回 true（`:51`）。`opensdk/httputil/b.java:64` 中 `s` 初值 false，`:574` 的 setter 可重建客户端，并读取本地 `key_open_ssl_check` 覆盖；`host/MainApplication.java:1455`、`:1469` 读取远端 `ifHostVerifier`（默认 false）后调用 setter。**已证实存在可到达的宽松路径；当前设备最终配置、哪些请求实际经过此实例及实际网络可利用性未做动态验证，不能扩大为全应用所有 TLS 均失效。**
3. **清单允许明文请求。** `evidence/manifest.txt:437`、`:438`、`:443` 显示 `debuggable=false`、`allowBackup=false`、`usesCleartextTraffic=true`。资源 ID `0x7f15001e` 对应 `res/xml/a3.xml`（`evidence/resources.txt:123934`），解析见 `evidence/network-security-config.txt`：base-config 允许明文，用户证书写在 debug-overrides 下；不能把 debug-overrides 直接说成 release 必然信任用户证书。
4. **多种界面与执行层共存。** 存在原生 Java/Kotlin、JNI 播放/加密、React Native/Hermes 和 H5。`packaged/assets/rn/rnconfig` 记录 commonpayment 等 bundle，`rn_base_config` 记录 base.bundle 与 Hermes 文件；`reactnative/manager/RNFunctionActionImpl.java:262` 有 bundle 加载入口。这里只检查相关打包配置与可见原生调用，没有完整逆向所有 RN 字节码/远端 H5，也没有观测后续更新内容。

以上补充用于解释认证和接口分析的可信范围，不扩展为全量安全审计。


## 二、主要功能与接口

证据范围：本地 JADX 1.5.3 输出；路径按报告开头的代码定位约定。它们是 APK 中的静态请求实现及静态调用关系，**不是抓包记录**；没有向这些服务发起业务请求。JADX 有失败/不完整方法，尤其 `CommonRequestM.getAlbumInfo`、`TrackM` 的大解析器，不能把反编译伪代码的异常条件当成真实运行 bug。

### 1. 核心结论

- 产品主链是“推荐/搜索 → 专辑与声音 → 获取元数据和播放授权 → 解密并选择音质地址 → 播放/CDN”，并配有下载、订阅、云历史、会员/单购、直播等业务。
- 业务 JSON 接口与音频文件 CDN 是两层：`mobile.ximalaya.com`、`search.ximalaya.com`、`mpay.ximalaya.com` 等负责业务/授权，媒体 URL 由响应决定，不能用一条固定 MP3 URL 概括。
- 不能把“付费播放”简单理解为“登录后获得静态 URL”。代码同时处理 `isAuthorized`、试听时长、带时效参数的媒体地址、加密 `playUrlInfos`，以及播放器进程所需的 ticket。
- 样本同时有 v3/v4 BaseInfo 和旧 `mpay` 播放链；v4 受远程/本地 AB 配置影响，静态样本不能确认某用户线上会选哪条。
- URL 工厂使用 `opensdk/util/d.java` 的生产/test/uat 环境转换；以下给出包中主机常量和业务 path。最终传输还需结合统一网络层判断，HTTP 常量不等于最终线上必走明文，HTTPS 常量也不证明所有请求都 HTTPS。

### 2. 主机分工与接口总览

URL 定义主要位于 `com/ximalaya/ting/android/host/util/e/g.java`（下文记为 **URL**），请求实现主要位于 `com/ximalaya/ting/android/host/manager/request/CommonRequestM.java`（下文记为 **CRM**）。URL 的 `SERVER_SKIN` 在 L51 为 `http://mobile.ximalaya.com/`，`getServerNetAddressHost` L493-494 会再调用环境转换。

| 功能 | 请求、主机/path | 静态证据与覆盖 |
|---|---|---|
| 首页内容流 | POST JSON，`mobile.ximalaya.com/discovery-feed/v4/body/{timestamp}` | URL L6103-6104；`main/util/HomeFeedDataUtil.java` L90-113 构造 `tag` 并请求；首页 `main/fragment/find/child/staggered/RecommendFragmentStaggered.java` L2534 调用该工具。已到页面调用方。 |
| 旧推荐流 | GET，`ifm.ximalaya.com/recsys-stream-query/recsys/stream/load`、`query`、`feed` | URL L720-721、2867-2868、2887-2892；CRM L11781-11812 有实际请求封装。存在实现，未断言当前首页一定使用旧流。 |
| 搜索结果 | POST JSON，`search.ximalaya.com/[hub/]front/v1` | URL L896-905；`search/request/e.java` L224-281；`search/page/sub/SearchChosenFragment.java` L1071-1152 构参、调用。`hub/` 是否加上受 `toc/search_service_interface_new` 配置控制（默认 true）。 |
| 搜索分类 | POST JSON，同上加 `/coreList` | `search/request/e.java` L209-221；响应 `response.docs` 转为 `SearchType` 列表。 |
| 搜索联想/热词 | POST JSON，`search.ximalaya.com/[hub/]suggest/v2/2.3`、`[hub/]hotWordV3/1.0` | URL L896-937；`search/request/e.java` L38-46、68-137；联想解析为 `SuggestWordsM`，热词解析 `liveWordList`、`hotWordList`、`aiRecBookEntrance`。已看到请求封装，未穷尽全部 UI 入口。 |
| AI 搜索流 | GET + SSE，`https://xvstc.xiaoyastar.com/mobile-rag/ai/stream/search?keyword=...` | `search/request/a.java` L137-158、174-175，使用 OkHttp `EventSources`；L54-99 处理流式内容、声音列表、推荐列表和结束消息。存在实现，未核验实际向用户开放条件。 |
| 专辑首页 | GET，`mobile.ximalaya.com/mobile-album/album/page/ts-{timestamp}` | URL L820-821；CRM `getAlbumInfo` L4482-4575（该方法重构有警告）；页面调用如 `main/albumModule/album/album2/AlbumFragmentNew2.java` L1571。 |
| 专辑声音列表 | GET，`mobile.ximalaya.com/mobile/v1/album/track/ts-{timestamp}`；另有 `mobile/v1/album/track/v2ts-{timestamp}` 的字面拼接 | URL L832-837；CRM L4482-4575；`main/request/b.java` L2447-2494；`main/albumModule/album/album3/AlbumFragmentNewList3.java` L1044-1057。注意 v2 的 URL 工厂没有末尾 `/`，调用方直接追加 `ts-`；这里按样本文本记录，不擅自补斜杠或断言服务端实际路由。 |
| 声音播放元数据 | GET，`mobile.ximalaya.com/mobile-playpage/track/v3/baseInfo/{timestamp}` 或 `.../v4/baseInfo/{timestamp}` | URL L258-263、976-981；CRM L4834-4884、16323-16520；详见播放链。 |
| 付费/防盗链声音 | GET，`https://mpay.ximalaya.com/mobile/track/pay/v2/{trackId}/ts-{timestamp}` | URL L154-155、336-337；CRM `updateTrackForPlayNew` L11183-11255、`getTrackForPlayBase` 及 `getAntiLeechUrl` L11687-11766；已注册播放器请求 provider（`host/MainApplication.java` L1546）。 |
| 下载元数据 | GET，`mobile.ximalaya.com/mobile/download/v2/track/{trackId}/ts-{timestamp}` | URL L2923-2924；CRM `getDownloadTrackInfoSyn` L10026-10076；`host/manager/download/a.java` L716-730 调用。 |
| 旧付费下载授权 | GET，`https://mpay.ximalaya.com/mobile/track/pay/download/v2/{trackId}/ts-{timestamp}` | URL L359-360；CRM `getChargeDownloadUrl` L11117-11180。已看到请求实现，但本次搜索未找到该方法在该样本里的独立外部调用方，不能说它是当前下载主链。 |
| 订阅/取消订阅 | POST，`mobile.ximalaya.com/subscribe/album/subscribe/create`、`delete` | URL L1112-1129；CRM `collectAlbumAddOrDel` L5444-5452，按参数选择 create/delete。 |
| 订阅列表 | GET，`mobile.ximalaya.com/subscribe/v6/subscribe/list/ts-{timestamp}` | URL L1749-1750；`main/request/c.java` L672-685 请求并解析 `WoTingAlbumItem`；CRM L4129-4138 也有小爱入口封装。 |
| 云收听历史 | GET，`mobile.ximalaya.com/nyx/v1/history/query/android` | URL L3087-3088；CRM `getCloudHistory` L12735-12764，参数包含 `pageId`、`pageSize`、`timeStamp`、`uid`；读取 `data` 为 `CloudHistoryModel`。 |
| 历史合并/删除/清空/离线补报 | POST，`xdcs-collector.ximalaya.com/nyx/v1/history/{merge,delete,batchDelete,clear,offlineAdd}/android` | URL L3091-3112；CRM L12785-12948。查询与写入分别走不同主机；是业务历史同步，不仅是本地列表。 |
| 购买专辑支付预单 | POST，`mp.ximalaya.com/payable/order/thirdpartypay/native/prepare/album/v2` | URL L704-705、2871-2872；`main/request/b.java` L1006-1015；`main/payModule/BuyAlbumFragment.java` L1052-1110 构参和调用支付路由。 |
| 会员支付预单 | POST，`mp.ximalaya.com/membership/order/thirdpartypay/prepare/membership/v2` | URL L2875-2876；`main/request/b.java` L3296-3305；`main/payModule/BuyVipFragment.java` L391-435。 |
| 直播入房事件 | POST，`live.ximalaya.com/lamia/v5/live/user_entry` | URL L40、2454-2455；`live/common/lib/base/request/c.java` L142-143、534-535；`liveaudience/data/request/CommonRequestForLive.java` L612-631；`liveaudience/mvp/LiveAudienceRoomPresenter.java` L151-164 有实际调用。 |

#### 请求参数与返回结构示例

搜索精选页不是只有 `kw`：`SearchChosenFragment.java` L1083-1152 还发送 `needSemantic`、`plan`、`spellchecker`、`search_version`、`core=chosen3`、`page`、`rows=20`、`paidFilter=false`、`live=true`、`condition`、`recall=normal`、`voiceAsinput`、`newDevice`、`searchId`、来源与可选 `extraParam`（意图、trackId、albumId）、筛选条件 `fq`/`labelForQuery`，翻页时可带 `searchMeta`。这说明搜索结果/排序/纠错/语义与跨页上下文由服务端共同决定；不能从 URL 推断只有一个简单全文搜索。

专辑列表页面 `AlbumFragmentNewList3.java` L1044-1057 明确组装 `url_from=tracklist`、`albumId`、`pageSize`、`isAsc`、设备参数及可选 `labelId`/`newTrackCount`；CRM 会把上层 `page` 映射为 `pageId`，响应经过 `AlbumM.parseAlbumMNew`。`getAlbumInfo` 方法存在 JADX 重构警告，报告只采信可互相印证的 URL、构参、调用与解析关系，不采信伪代码中可疑反向判空分支。

### 3. 播放链详细说明

#### 3.1 元数据和播放地址不是一回事

启动注册：`host/MainApplication.java` L1546 将 `CRM.getPlayerRequestProvider()` 注入 `opensdk/datatrasfer/c`。后者 L50-71 根据 provider 的 `b()` 选择 v4 或 v3 请求实现。provider 的判断继续调用 `host/manager/request/c.a()`。

v4 开关证据是 `host/manager/request/c.java` L31-50：读取 `item_use_base_info_v4`、`item_baseinfo_v3tov4_0814`，并考虑 `debug.xmly.useV4`、beta 条件与 `key_common_ab_config_val`。因此“有 v4 代码”不能推出“样本实际每次都走 v4”。

播放器 URL 选择器 `opensdk/player/manager/ad.java`（日志名 `TrackUrlChooseManager`）L137-207 先判断缓存、直播/广播等类型和已有下载文件，再分发到普通/儿童音色等路径；L273 附近检查地址缺失、授权/更新状态和 `UnlockModeInfo.isExpiredFromAuthorized(track)` 等条件，必要时刷新信息。也就是说，不是每次点播放都无条件新发一次 BaseInfo 请求。

**已证实的主要请求参数**（CRM L16403-16456）：

- `trackId`；`device`（Android 或按配置选择 `android2`）；`trackQualityLevel`。
- 智能音质时 `isSmartQuality=1`。
- 能力参数 `canPlayVividSound=true`、`canPlayDolby=true`（取决于本机能力判断）。
- 可选 `sceneInfo`；调试构建额外 `from`、`process`。
- v4 额外 `sign`：`host/manager/request/c.java` L54-92 可见对声音 ID、device、时间戳尾部和调用场景常量组合进行对称加密，再 Base64 编码；这是本地实现证据，未在本任务中生成可用签名或请求服务端。
- BaseInfo 头中有 `x-tk`：CRM L4792-4829 通过 XuidManager 或播放器服务取 ticket。**不能把这个 ticket 当成用户登录 token 的同义词**；它是此业务请求另一个校验材料。

#### 3.2 服务器返回授权状态和加密地址列表

v4 响应在 CRM L5300-5326 被解析为两部分：`trackBaseVO`（内部 `trackInfo`、`albumInfo`、`extendInfo`）和顶层 `playUrlInfos`。v3/旧路径则读取 `trackInfo.playUrlList`。声音模型包含是否授权、试听、付费与离线权限等字段；能直接互证的字段如 `isAuthorized`、`permissionExpireTime`、`offlineVisibleType`、`isPaid`、`freeListenStatus`，见 CRM L5336-5418、`host/model/track/TrackM.java` L1241-1242、1466-1468、1588-1592。

`opensdk/model/track/PlayUrlInfo.java` L23-34 定义了 `url`、`decodeUrl`、`qualityLevel`、`type`、`uploadId`、`fileSize`、`duration`、`version`、`volumeGain`、`huaweiSound` 等。它不是只返回“一个播放地址”，而是按音质/编码/能力组织多个候选。

地址处理位于 `opensdk/util/af.java`：

1. L25-58：根据 `version`，在 V2 原生解密与 V1 路径间选择/回退。
2. L61-100：V2 调 `EncryptUtil.d(str)`；V1 从原生工具取得 `play_url_key`，对 Base64 数据解密。JNI 内部完整算法/密钥派生本次未进一步反汇编，不能声称已经完整复原。
3. L112-124：优先复用已解密 `decodeUrl`，否则解密 `url` 后回写 `decodeUrl`。
4. L141-204：按 `qualityLevel` 匹配候选，处理华为音效 `vivid=1`、Dolby `dolby=1`，输出 `PlayUrlModel`。
5. `XmPlayerService.java` L2131-2192：对防盗链/付费 HTTP URL 按需补 `is_charge=true`，处理 CDN/PCDN 域名替换，再进入播放；L2195-2220 遇到错误会构造 `PlayInfoErrorResponseInfo`，其中错误码 726 将 track 置为未授权并触发切换通知。

这里“加密 URL”“媒体防盗链参数”“付费/会员授权状态”“用户登录凭据”应当分别理解，不能混成一个 token。

#### 3.3 旧付费接口与 CDN 防盗链地址

`CRM.updateTrackForPlayNew` L11183-11255 对普通声音调用 `mpay` 的 `/mobile/track/pay/v2/{id}/ts-{timestamp}`，微课声音另走微课请求。这条封装被 `getPlayerRequestProvider` L16325-16328 引用，属于播放器已注册能力；它与 BaseInfo 路径共存，不是所有免费声音必需的一步。

响应解析 `parseChargeJsonAndGetUrl` L10870-10918 读取 `fileId`、`ep`、`duration`、`apiVersion`、`domain`、`totalLength`，并按 `isAuthorized`、试听字段更新模型。`getAntiLeechUrl` L11687-11766 执行：

- Base64 解码并通过 `EncryptUtil` 解密 `fileId` 得到媒体路径。
- 解密 `ep` 得到 4 段凭据；代码把它们映射为 `buy_key`、`sign`、`token`、`timestamp`。
- 以服务端 `domain + /download/` 为前缀（为空时才退到 URL 工厂的 `audio.pay.xmcdn.com/download/`），追加 `apiVersion` 与解密媒体路径。
- 查询参数再加入 `duration` 和当前 `uid`，最终付费解析器补 `is_charge=true`；预览地址还可能附带 `totalLength`。

概念结构是：

```text
{response.domain}/download/{apiVersion}/{decodedFileId}
    ?buy_key=…&sign=…&token=…&timestamp=…&duration=…&uid=…&is_charge=true
```

这只是代码还原出的结构，没有真实响应值或可重放凭据。`timestamp`/`token` 的服务端有效期及是否绑定设备/IP，静态样本不能定论。`parseChargeJsonAndGetUrl` 的试听分支有 JADX 警告，因此只说明其读取字段与总体构造，不推导所有分支精确执行顺序。

### 4. 下载与离线

**已追到下载管理器的当前接口**是 `mobile/download/v2/track/{id}/ts-{timestamp}`：`host/manager/download/a.java` L716-730 调 CRM `getDownloadTrackInfoSyn`。后者 L10026-10076 发送 trackId、音质、device/android2、音效能力，经过统一请求头取响应，交由 `TrackM.parseTrackByGetDownloadInfo` 更新 Track。它会检查响应 `ret` 及专辑/主播基本信息并向上抛出错误，不能把“本地显示下载任务”当作“服务器允许下载”。

下载器还受网络偏好约束：`host/manager/download/a.java` L695-701 结合网络状态、移动网络和 `is_download_enabled_without_wifi` 决定是否继续；L705-714 读取保存路径，不可用则终止。付费、VIP 优先听、防盗链声音在 L688-692 被归为需要特殊处理的类型。

旧 `mpay` 下载授权封装 `CRM.getChargeDownloadUrl` L11125-11180 解析授权响应并调用前述防盗链构造；726 对应“当前暂无下载权限”。**本次没有找到此方法在样本中的独立外部调用方，保留为兼容/备用实现证据，不当作当前默认路径。**

下载次数还与激励系统耦合：CRM L17517-17589 有 `requestDownloadConfig`、`requestDownloadInfo`、`requestRewardDownloadTimes`、`requestDecreaseDownloadTimes`；对应 `adse` 的 `incentive/ting/{downloadConfig,downloadInfo,rewardDownloadTimes,decreaseDownloadTimes}/ts-{timestamp}`（URL L5043-5056），`host/manager/download/j.java` L100、124、191、244 有调用。广告章节另有详细分析；此处仅说明离线下载额度不是全由客户端本地计数。

### 5. 订阅、历史与付费

订阅 create/delete 是服务端写接口，CRM L5444-5452 按当前收藏/订阅状态选择；订阅列表由服务端返回。云历史查询带用户 uid 和分页，合并、删除、清空、离线补报各有接口（表中所列）；播放器另在 `XmPlayerService.java` L1611 打开本地 `play_track_history_record` SharedPreferences。由此可以确认存在本地状态加云同步，但未穷尽历史冲突解决策略、所有落库字段和最终同步时机。

专辑支付的 UI 调用链是 `BuyAlbumFragment.b(int)` → `main/request/b.t` → 支付预单接口 → 路由服务 `appPay(response, callback)`。页面明确提交 `channelTypeId`、`albumId`、`amount`，可选 `couponId`、`promoCode`、`voucherId`、`context`，并加 `signature`（`BuyAlbumFragment.java` L1052-1070）；响应读取 `unifiedOrderNo`，支付回调成功后显示处理中并延迟检查（L1082-1102）。会员预单提交 `channelTypeId`、`ownerId`、`amount`，同样交给 `appPay`（`BuyVipFragment.java` L391-435）；L373-388 可见喜点余额、支付宝、微信选择。这里是客户端串联预单与支付 SDK 的证据，**没有发生实际下单，也不能仅凭客户端支付回调判定最终服务器权益已到账**。权益是否可播仍回到服务器授权和声音信息更新。

### 6. 直播与 AI 的边界

直播入房事件有 UI/presenter 调用证据：`LiveAudienceRoomPresenter.java` L151-164，在登录且房间状态符合条件时组装 roomId、lamiaToken（由 uid 与当前时间组合的输入），经过 `EncryptUtil.h` 处理后 POST `/lamia/v5/live/user_entry`。这是“入房业务/统计事件”，不能将它误写成直播音视频拉流 URL；拉流地址由其他房间/播放模型提供，本轮仅做简要覆盖，未重建完整 RTC/IM 协议。

AI 搜索 SSE 实现可确认请求 `keyword`、流式模型分发和取消连接（`search/request/a.java` L137-175），但未确认产品实际开放人群、服务端模型供应商、所有工具调用或模型提示词。不能根据 `AI` 类名认定设备离线运行大模型。

### 自检

- ✓ 推荐、搜索、专辑、播放、下载、订阅历史、付费、直播均找到至少请求实现及证据。
- ✓ 播放链给出版本选择、参数、响应、解密边界、CDN构造、授权/错误路径和实际注册点。
- ✓ 对旧/备用路径、动态配置、无运行验证、反编译警告明确标注。
- ✓ 未执行 APK、未访问业务接口、未生成订单或可重放授权材料。
- 未覆盖：全部接口枚举、原生加密内部反汇编、线上AB/服务端授权有效期、直播完整拉流/IM协议；这些不能从已检查代码直接确认。


## 三、登录认证与会话保持

本节仅依据样本的 JADX Java 重建结果与 APK 内资源；没有发起登录请求、没有真实 token，也未运行 APK。下文的“证实”均指客户端静态实现已证实，不能替代服务端实际响应验证。证据路径按报告开头的代码定位约定。

### 1. 认证体系结论

**已证实：主账号会话是服务端签发的 `uid + token`，通过自定义 Cookie 传递；并非仅靠一个 Authorization Bearer 头。** `host/manager/request/CommonRequestM.java:3001` 的 `getCoreCookie()` 把设备、版本、渠道和账号信息拼为 Cookie。其中登录态字段的结构是 `1&_token=<uid>&<token>;`；`1&_device` 包含平台、设备 ID、版本。`host/util/e/c.java:8` 继承 `opensdk/a/a.java:5` 的 `n=1`，APK 的 `assets/cookie.json` 也使用同样键名。Cookie 的数值仅为格式示意，不是实际会话。

登录、会话有效性、设备风控是不同层：

| 层 | 作用 | 证据 |
|---|---|---|
| 账号 token | 识别已登录账号 | `CommonRequestM.java:3017`，`host/manager/account/l.java:241` |
| 请求参数加密、signature、nonce | 密码/手机号等字段转换、请求签名和挑战材料 | `loginservice/LoginEncryptUtil.java:9`，`LoginRequest.java:447` |
| `x-tk` 业务 ticket | 部分下载、订阅、关注、登录等路径的额外风控凭据 | `host/manager/XTicketNetworkInterceptor.java:159`、`:270` |
| Xuid SDK 生成的头、设备状态 | 设备标识/风控上下文；隐私同意后追加 | `host/manager/XuidHeaderInterceptor.java:22`，`XuidManager.java:397` |

所以“保存了 token”不等于“所有 API 都可调用”；账号、设备、业务 ticket、验证码/风控和内容权益共同影响服务端判断。最后这句是根据多层调用结构作出的推断，具体服务器策略未验证。

### 2. 核心认证接口表

`loginservice/k.java:10` 默认 Passport 基址是 **`https://passport.ximalaya.com/`**。`loginservice/a.java:10` 默认环境标记为生产；`k.java:49` 支持 test/uat 域名改写。以下路径不是单纯字符串清单，均有 `LoginRequest` 请求方法使用：

| 功能 | 方法与相对路径 | 参数/返回与调用位置 |
|---|---|---|
| 获取登录 nonce | GET `mobile/nonce/{毫秒时间戳}` | 返回顶层 `nonce`；`LoginRequest.java:447`，`k.java:104` |
| 发验证码 | POST `mobile/sms/v3/send` | 加密后的 `mobile`（按场景可选）、`sendType`、`nonce`、可选 `fdsOtp`、`biz`、`signature`；`LoginRequest.java:526`、`:604`；`biz`常量在 `com/alipay/sdk/app/statistic/c.java:49` |
| 验证短信码 | POST `mobile/sms/v2/verify` | `mobile`（可选）、`code`、`nonce`、`signature`；返回 `bizKey`；`LoginRequest.java:703` |
| 短信登录 | POST `mobile/login/quick/v2` | `mobile`、`smsKey`（上一步 `bizKey`）、`nonce`、`signature`；返回 `LoginInfoModelNew`；`:414` |
| 密码登录 | POST `mobile/login/pwd/v3` | 加密 `account`、加密 `password`、`nonce`、可选 `fdsOtp`、`signature`；`:290`、`:362` |
| 第三方 code 换授权 key | POST `thirdparty-mobile/v1/auth/{id}/accessByCode` | 例如 `thirdpartyId`、`code`；`:260`，`loginservice/j.java:211` |
| 第三方 token 换授权 key | POST `thirdparty-mobile/v1/auth/{id}/accessByToken` | 第三方 SDK 的 accessToken/openId/expireIn 等材料；`:280`，`j.java:268` |
| 第三方最终登录 | POST `mobile/login/thirdparty/v1` | `key`，返回本 App 登录模型；`:270`，`j.java:246` |
| 一键认证 nonce | GET `authentication-server/nonce/{毫秒时间戳}` | 与登录 nonce 分开的接口；`:884` |
| 极光一键认证交换 | POST `authentication-server/mobile/get/jg/p1/v1` | `token`、认证 nonce、signature；返回 mobile/key；`:898` |
| MobTech 一键认证交换 | POST `authentication-server/mobile/access/mobtech/v1` | 传入材料 + nonce/signature，返回 mobile/key；`:1103` |
| 一键登录落地 | POST `mobile/login/lightning/v1` | `mobile`、`key`、登录 nonce/signature；`:951` |
| 登录态检查 | GET `mobile/login/check/{毫秒时间戳}` | 调用方可传触发检查的 `url`，返回 `HasLogin`（isLogin/logoutCode/logoutMsg）；`:870` |
| token 轮换 | POST `user-http-app/v1/token/refresh` | 业务 map 为 null，现有身份走通用请求/Cookie；返回 data.oldToken/newToken/refreshable；`:1056` |
| 登出 | GET `mobile/logout` | `ts`；返回可能包含 `authCode` 的 LogoutModel；`:1155` |
| 免密凭据检查 | POST `mobile/login/freePassport/check` | `authCode`、nonce、signature；`:1171` |
| 免密重新登录 | POST `mobile/login/freePassport/login` | `authCode`、nonce、signature；`:1206` |

HTTP 方法由 `LoginRequest.java:100` 的 `a(...)=post` 与 `b(...)=get` 确认。具体接入不是 Retrofit 声明：`host/manager/application/ApplicationManager.java:915` 注入 `loginservice.base.d`，GET 调 `CommonRequestM.baseGetSyncThrowXmErr`，POST 调 `basePostRequestParamsToJsonSync`，即通用网络层的 JSON 参数请求。Map 为 null 不代表不带身份，Cookie/拦截器会独立处理。

关联账号管理还存在手机号绑定/校验 `mobile/login/mobile/bind/v2`、`mobile/login/mobile/validate/v1`，密码设置/修改 `mobile/password/set`、`mobile/password/modify`，第三方绑定状态/绑定/解绑，以及 `mobile/app/auth/info`、`mobile/app/auth/code` 授权接口；定位在 `k.java:112`–`:177` 与 `LoginRequest.java:466`–`:857`、`:1012`–`:1053`。这些次要接口不展开成完整字段字典。

### 3. 短信登录完整调用链

1. 输入手机号后，`LoginRequest.a(FragmentActivity,int,d,Map,callback)` 先调用 `a(Map)`。`LoginRequest.java:506`：若已有 `encryptedMobile`，直接取作 mobile；否则普通 mobile 经 `g.c()` → `LoginEncryptUtil.a(String)` → JNI `eXYTbsvYIn()` 转换。源码还有长度大于 30 时避免再次转换的分支，不能假定所有输入一定重加密。
2. GET Passport nonce。发短信前接入 `loginservice/d/b` 的验证码/风控 SDK，回调可给出 `fdsOtp`。`ApplicationManager.java:958` 注册了其实现与远端配置开关；不在报告中复制内嵌应用标识的具体值。
3. POST `mobile/sms/v3/send`。提交前计算 signature。`sendType=2` 的实际调用例可在 `host/manager/login/b.java:55` 看到，对应界面文案是语音验证码；不能把 `sendType` 所有值都在缺少分支证据时自行命名。
4. 用户输入验证码。`loginservice/j.java:128` 组装 mobile/code 调 `LoginRequest.d()`，POST verify。
5. `VerifySmsResponse.bizKey` 非空且 ret=0 后，`j.java:139`–`:182` 把它作为 `smsKey` 调 quick 登录。因而短信验证码并不直接送 quick 接口，而先换取业务 key。
6. quick 登录重新获得 nonce，携 mobile/smsKey/signature 请求，最终得到 `LoginInfoModelNew`。模型中有 uid/token/手机号脱敏字段、昵称、VIP 状态等（`host/manager/login/model/LoginInfoModelNew.java:30`）。

### 4. 密码、第三方和一键登录

#### 4.1 密码登录

`loginservice/j.java:80` 根据账号/密码组 map；`LoginRequest.java:290` 把 account/password 通过 JNI 字段转换，再取 nonce，执行验证码/风控 SDK，最后组装 `account,password,nonce,[fdsOtp],signature` POST pwd/v3。不能从 Java 层断言它使用 RSA、AES 或某个固定哈希：算法位于 `lib/arm64-v8a/liblogin_encrypt.so`，本次未还原 native 实现。

#### 4.2 第三方 SDK 认证

`loginservice/j.java:211`–`:287` 的核心链是 **第三方 SDK 返回 code/token → Passport 换 AuthorizationInfo.key → App 的 thirdparty 登录 → App uid/token**。第三方 token 与 App token 是不同凭据。

可确认的 SDK 材料例：QQ 分支 `loginservice/c/c.java:23` 组 thirdpartyId/accessToken/expireIn/openId；微博 `c/e.java:25` 组 openId/accessToken/refreshToken/expireIn；`c/g.java:40` 的 code 分支和 wx 回调代码关联微信授权。这里只描述代码支持，未验证这些渠道在当前网络、账号或渠道包中都可用。微博 refreshToken 是微博 SDK 凭据，不能拿它解释 App 自身 token 轮换。

#### 4.3 运营商一键登录

不是“直接信任手机号”：先把运营商/聚合 SDK token 交给 authentication-server 接口，获得 mobile/key；然后 `mobile/login/lightning/v1` 交换 App uid/token。`LoginRequest.java:1076`–`:1149` 的 m→n→k 链可见 MobTech 路线；`:898`–`:949` 可见极光交换入口。SIM/权限/渠道是否满足属于运行条件，静态代码不能证明真实成功。

### 5. signature 和额外 ticket

`LoginEncryptUtil.java:30` 先对 Map 构造 `TreeMap`，按键排序，把每项拼为 `key=value&`（包括末尾 &），再交 JNI `AFlIEghJkK(context,environmentFlag,string)` 生成 signature。`LoginRequest.java:462` 选择环境 flag。字段转换则是另一个 native 方法 `eXYTbsvYIn`。已证实排序与 JNI 边界，**未验证 native 内部加密算法、密钥派生、设备绑定、服务端 nonce 有效期与重放规则**。

`XTicketNetworkInterceptor.java:159` 给命中的请求加 `x-tk`。登录分支通过 `host/manager/login/b.a(Uri)`（继承 `loginservice/g.java:57`）判定 Passport URL，但明确排除 token refresh、login/check 和 mobile/nonce；远端 `item_login_add_ticker_token` 控制是否加登录 ticket。生成时业务属性是 `b=passport&s=login&u=<uid>`，调用 `XuidManager.b(attr)`，再进入 Xuid SDK（`:397`–`:405`）。下载、订阅、关注等使用各自业务属性；存在配置下发的 path/b/s 列表，不能把一个 ticket 当作所有业务通行证。

`XuidHeaderInterceptor.java:22` 只有隐私同意键 `key_privacy_policy_agreed_new` 满足时才调用 `XuidManager.a(request)` 并追加返回的头。具体 SDK 输出与服务器校验在本次静态边界之外；Xuid 不等价于账号登录 token。

### 6. 为什么重启后仍然登录

#### 6.1 持久化与恢复

`host/manager/account/l.java:124` 创建 `MultiProcessSharedPreferences(context,"loginforesult")`。构造立即 `m()`，从 `loginforesult_new` 读 JSON → Gson → `LoginInfoModelNew`，恢复内存对象（`:152`–`:221`）。代码还迁移旧 `loginforesult` 与 MMKV `key_user_info_mmkv_file_name` 的历史数据。

`l.java:913` 的 `r()` 将当前模型经 `host/util/common/p.java:44` 的 Gson.toJson 序列化，再写 `loginforesult_new`；null 时写空字符串。`MultiProcessSharedPreferences.java:546` 最终调用 Android `getSharedPreferences(name,0)`，即 App 私有 SharedPreferences；通常对应应用私有 `shared_prefs/loginforesult.xml`（文件路径是 Android 存储映射推断，本次无设备文件证据）。这条 Java 路径中没有额外 Keystore/加密步骤；不能据此绝对排除文件系统级保护。

多进程通过偏好变化监听更新内存对象：`l.java:964` 对 `loginforesult_new` 变化反序列化；播放器另有 Cookie 包装 `CommonRequestM.java:12152`，设置 domain `.ximalaya.com`、path `/`，以同样 uid/token 结构携带身份。H5 有 `getCookieForH5(Uri)`（`:12129`），退出时清理 WebView Cookie，说明原生/播放/H5有各自接入方式，不能简单把它当浏览器自动维护的 Session Cookie。

**本地 `isLogin` 只判断 token 非空**（`l.java:245`），没有本地解析 JWT exp 或本地固定 TTL 的代码证据。token 字符串格式、真实有效期和服务器撤销机制未验证。

#### 6.2 token 轮换：有 24 小时间隔判断，但不是定时器

`MainActivity.java:5241`、`:5256` 在 `doOnResumeReal` 流程调用 `account/h.c()`。`h.java:440` 的顺序非常关键：

1. 读 `last_refresh_token_time`。
2. **立即写当前时间，之后才判断差值**。
3. 若与旧值相差大于 `86400000` 毫秒，调用 refresh POST。
4. 请求前快照当前 token。返回必须满足 refreshable=true、oldToken/newToken 均非空、响应 oldToken 与该快照相等，才调用 `l.d(newToken)`。
5. `l.java:1073` 更新内存 token，并通过 r()持久化。

因此准确描述是“恢复到前台路径触发、按上次该方法调用时间作一天门控的 token 轮换”，**不是严格每 24 小时自动续期、不是永久登录保障**。由于每次调用都更新时间，频繁前台触发可能推迟该分支；请求失败也已经更新了门控时间。该顺序是静态观察，不声称已在真机复现其后果。

响应校验比较的是请求前 token 快照，不是回调时重新读取的 token；不要把它夸大成对所有账号切换并发场景的完整保护。

### 7. 失效、被踢与退出

#### 7.1 失效检查

`CommonRequestM.java:3821` 处理业务 ret=50 时调用 `DeviceOutStrategy.a(url)`。它排除 check 接口自身，避免自调用检查链，再调 `LoginRequest.i()` → GET `mobile/login/check/{ts}`，携带触发 URL（`DeviceOutStrategy.java:37`–`:45`）。若 `HasLogin.isLogin=false`，依据 logoutMsg/logoutCode，清登录态并转登录页或展示提示（`:60`–`:109`）。这比“遇到401就刷新token”更符合代码，未看到可把全局行为概括为401自动refresh的证据。

#### 7.2 主动登出

`l.java:484`–`:526` 清理 WebView 过期/会话/全部 Cookie、旧登录字段、password 等相关偏好；付费内容正在播放时也有停止/清理分支。`l.java:529`–`:573` 在通用 Cookie 处理器中安排清内存模型，保留本次登出请求所需 Cookie，调用 Passport logout；模型置 null 后 r() 写空登录状态并通知监听器、播放器（`:775`–`:810`）。

#### 7.3 “退出后还能快捷登录”的原因

登出响应可包含单独 `authCode`。`l.java:555` 把它交给 `FreePasswordManager`，后者（`host/manager/account/FreePasswordManager.java`）把 LogoutModel JSON 保存到 MMKV 键 **`key_login_auth_data`**（`FreePasswordManager.java:49`–`:57`）。这与旧会话 token 保留是两回事。

`FreePasswordManager.java:61` 由本地数据存在性和 AB 开关 `quick_login` 决定是否展示免密入口；`:107` 起的检查读取数据并调用 freePassport/check，至少隔10秒才重新验证 authCode；失效 ret=-1 等分支删除。用户选择免密登录后 POST freePassport/login（`LoginRequest.java:1206`）换新的登录模型。**authCode 的服务器有效期、是否设备绑定、是否一次性未验证**，不能称为无条件永久免密。

### 8. 边界与复核

- 已证实：请求封装、参数组装、nonce流程、JSON POST桥接、uid/token Cookie、私有持久化、前台刷新门控、清退与登出免密逻辑。
- 未验证：API当前在线可用性、服务端 token TTL、运行时动态配置最终值、native密码算法/密钥、Xuid SDK真实输出、真机各渠道登录成功率。
- 上述核心 LoginRequest、LoginEncryptUtil、j、l、FreePasswordManager、DeviceOutStrategy 文件未发现 `Method not decompiled` stub。`account/h.java:277` 有不相关其他方法的“反编译不正确”提示，token refresh 的 c() 位于440行且没有该提示。`XTicketNetworkInterceptor.java:168` 的分支选择方法有“反编译不正确”提示，所以其路径分支描述仅作为静态重建观察；独立 intercept() 中的 x-tk 写入（159行）及生成方法（270行）明确可见。MainActivity 与部分 Map 分支有 JADX 重构重复/不完整风险；报告仅使用清晰可见的调用关系和局部已恢复表达式，不把反编译代码视为原始源码。
- 复核命令示例：`rg -n 'last_refresh_token_time|86400000|refreshTokenResult' analysis/jadx/sources/com/ximalaya/ting/android/host/manager/account/h.java`；`rg -n 'getCoreCookie|&_token|getCookieForH5' analysis/jadx/sources/com/ximalaya/ting/android/host/manager/request/CommonRequestM.java`；`rg -n 'signature|nonce|smsKey|k.a' analysis/jadx/sources/com/ximalaya/ting/android/loginservice/LoginRequest.java`。

验收：✓ 核心登录方式与请求链；✓ 参数与HTTP方法；✓ token保存与恢复；✓ 刷新/失效/登出；✓ 签名与风控边界；✓ 明确静态证据限制。运行验证不在已确认范围内。


## 四、广告子系统

本节以本次 JADX 输出为证据；路径按报告开头的代码定位约定，行号指本次生成文件。静态证实表示 APK 内可见接口构造和调用关系，不表示服务器现时可用或真机已展示。源码带 `Code decompiled incorrectly` 的方法只采纳明确调用、字段和早退条件，不据此精确还原全部分支。

### 结论

广告由两套相互衔接的客户端代码处理：主 App 的 `host.manager.ad`/`host.manager.request.a` 负责播放贴片、业务免广告和旧广告位；独立的 `adsdk` 负责 ADX 请求、第三方聚合、物料加载、开屏、激励和上报。并非单一广告 URL，也并非把广告写死在音频里：播放链单独请求广告列表、下载声音、插入播放器，再发送曝光、点击和播放状态；但不能据此排除服务端另有内容内植入。

`AggregationManager.initSDKs()` 初始化 XM，并在主进程分支调用 GDT、CSJ、JAD、Baidu 的初始化；`AdapterUtil` 将媒体类型 1/2/3/4/5 分别映射到 GDT/CSJ/XM/JAD/Baidu 实现（不是只根据包名猜测）。`WaterfallLoadManager` 根据返回物料和配置选择瀑布流或新 RTB 分支，查询缓存后调用相应 SDK。实际哪家赢得某次展示仍由服务端返回、竞价和运行状态决定。

证据：`com/ximalaya/ting/android/adsdk/aggregationsdk/AggregationManager.java:115-165,279-314`；`.../aggregationsdk/AdapterUtil.java:33-60`；`.../aggregationsdk/waterfall/WaterfallLoadManager.java:63-175`。

### 核心接口

广告域名在 `AdUrlConstants` 中默认是 `http://adse.ximalaya.com/`，配置 `ITEM_AD_USE_HTTPS` 可从持久化开关和异步配置将其切到 HTTPS，同时切换统计域名。不能把默认常量等同于实际网络一定使用明文。测试/预发布环境另有替换逻辑。

| 方法和地址 | 用途与请求/响应 | 调用证据 |
|---|---|---|
| POST `adse.ximalaya.com/adx/init` | 广告初始化；返回 `XmInitModel` | `adsdk/request/XmAdRequest.java:76-90` |
| POST `adse.ximalaya.com/adx/ad?slotId={id}` | 广告位主请求；JSON 参数包含 `slotIds`，响应解析 `AdResult → SlotAds → ads` | `XmAdRequest.java:97-158,202-235`；`adsdk/XMSDKManager.java:50-108` |
| GET `adse.ximalaya.com/nonce` | 获取 `data.nonces`，供广告记录相关数据使用；不应误认成账号登录 nonce | `XmAdRequest.java:238-247` |
| POST `adse.ximalaya.com/ting/preload` | JSON body 请求预加载物料；query 带 appid/name/version/network/operator 和时间信息 | `XmAdRequest.java:301-318` |
| POST `adse.ximalaya.com/adx/preload/ad?slotId={id}` | 开屏广告预请求 | `adsdk/aggregationsdk/splashload/SplashPreRequestNetAction.java:36-55` |
| POST `adse.ximalaya.com/adx/parallel/config` | 开屏并行配置，`slotIds=1` | `XmAdRequest.java:321-334` |
| POST `adse.ximalaya.com/soundPatch` | 主 App 的声音贴片请求，返回 `AdvertisList`，可包含竞价列表 | `host/manager/request/a.java:1015-1085`；`host/util/e/a.java:59-61`；`host/manager/request/b.java:94,166` |
| POST `adse.ximalaya.com/xm/ad?name=insert_sound_patch` | 插播广告；name 放 query 及 body；头贴片 `trackId,insertPatchPosition=1`，尾贴片为 2 | `host/manager/request/a.java:1196-1235`；`host/manager/ad/z.java:478-480`；`opensdk/player/advertis/HeadSoundAdManager.java:136-145`；`TailSoundAdManager.java:65` |
| POST `adbehavior.ximalaya.com/api/v1/adRealTime` | 聚合统计事件，JSON 或 gzip JSON（由配置及分桶决定） | `adsdk/aggregationsdk/record/xdcs/XdcsManager.java:22-64`；`AdUrlConstants.java:150-155` |
| POST `ad.ximalaya.com/ad-action/feedback` | 广告“不感兴趣”反馈 | `adsdk/adapter/base/AbstractNativeAd.java:166`；`AdUrlConstants.java:75-76` |
| POST `adse.ximalaya.com/hook/showWithRead` | 阅读场景奖励回调，带 adId/rewardSendTime/xt/fallbackReq/signature | `adsdk/aggregationsdk/rewardvideoad/RewardVideoAdManager.java:432-465` |
| GET `{业务主机}/mobile-resource-position/resource/advertising/free/v2` | 下发业务免广告配置，含展示开关、用户类型和广告位规则 | `host/manager/request/CommonRequestM.java:18290-18307`；`host/util/e/g.java:5347-5349` |
| GET `{M HTTPS 主机}/business-svip-coupon-mobile-web/ad-vip/ad-free-switch/entry` | 请求隐私设置页的免广告入口，返回显示文案/状态 | `CommonRequestM.java:13159-13182`；`host/util/e/g.java:1429-1431` |
| POST 同上 `/set` | JSON `{"enabled":"true/false"}`，保存免广告设置 | `CommonRequestM.java:13185-13208`；`host/util/e/g.java:1433-1435` |

表中 scheme 可由配置切换的地址特意没有固定写 HTTPS。`AdUrlConstants.java:15-37,55-76,150-171` 是对应端点构造证据。旧接口还有 `/ting/direct`、`/ting/direct_v2`、`/ting/feed`、`/ting/loading` 等定义（`host/util/e/a.java:23-100`），本表未把仅在 URL 类看到的所有路径认定成已跟踪业务链。

广告主请求公共字段由 `XmAdRequest.publicParams()` 生成：`appid`、`appVersion`（`AttributionReporter.APP_VERSION` 的值经常量定义核对）、`sdkVersion`、`sdkJarVersion`、`xt`、`userAgent`、模板版本、当前 `trackId`、`album`、非零 `uid`、`sdks`，折叠设备另加 `isFoldDevice=1`。`XmAdRtbRequestParamsUtil` 还会加入 `AdSdkToken` 与 `sdkInfo`，其用途是第三方广告竞价令牌和 SDK 信息，与账号登录 token 不同。

证据：`adsdk/request/XmAdRequest.java:44-73,125-149`；`adsdk/s2srtb/XmAdRtbRequestParamsUtil.java:13-41`。HTTP 序列化由 `DefaultAdHttpClient.java:30-38,59-100` 证实：JSON POST 用 `application/json`，表单 POST 用 `application/x-www-form-urlencoded`。

### 开屏与信息流

开屏请求 `SplashRequestNetAction.call()` 把 `startType`（热启动1、冷启动0）、`isSplashAd=true`、`isShakeDisable`、`isHonorMinusScreen`、`isFromDirectional`、`usePreload`、`throughputType` 送到 ADX；按需添加 `usePreloadResponseId`、`usePreloadAdids`、召回免广告实验值、`clientLocalCache` 和 `channelid`。返回第一组广告位的物料交给加载器。

开屏存在预请求、素材预下载、异步/并行第三方加载、超时退出及缓存选择。`AsyncSplashTimeManager` 的总请求默认预算是 3000ms，可被配置覆盖，并按 ADX 已消耗时间计算第三方剩余预算。这是代码默认值，不是每次真实开屏固定等待3秒。

开屏交互不限于点击：包内 `ShakeUtils` 实现加速度/线性加速度阈值检测，在 resume 时注册传感器，在 pause 时注销；达到阈值调用 `onShake()`。旋转行为另有 `RotateBehavior`，资源包含摇动、滑动、长按等提示。静态证据能确认支持这些互动；本次未运行，不确定当前账号/广告是否启用，也未以资源名推断每种交互都能触发外部跳转。

信息流由 `AggregationManager.loadNativeAd()` 进入聚合链，自有物料从 `XMSDKManager.loadNativeAd()` 向 ADX 获取，生成 `XmNativeAd`；CSJ 实现明确调用 `TTAdSdk...loadFeedAd()`，并支持 `withBid(slotAdm)`。自有 `loadNativeExpressAd` 则直接返回“喜马未接入信息流模版广告”，不能把所有媒体的模板能力一概而论。

证据：`adsdk/aggregationsdk/splashload/SplashRequestNetAction.java:55-93`；`adsdk/splash/async/AsyncSplashTimeManager.java:11-12,40-79,100-122`；`adsdk/splash/ShakeUtils.java:69-114,119-145`；`adsdk/CSJSDKManager.java:78-101`；`adsdk/XMSDKManager.java:50-116`。

### 播放贴片如何插入、何时不请求

可见调用链为 `opensdk.player.advertis.q`（原名 XmAdsManager）构造广告上下文 → `host.manager.ad.z`（原名 AdsDataHandler） → `host.manager.request.a.a(...)` → `/soundPatch` → `AdvertisList` → 播放器广告任务。广告列表支持独立 `soundUrl`、前中后插播、内容播放状态、缓存和服务端下发策略。

`AdsDataHandler` 发送上下文包括 track/album、`sourceType`、`appid=0`、前后台/亮屏状态 `isDisplayedInScreen`、播放方式、`duringPlay`、`paused`、免费听剩余时间、播放顺序/倍速、播放来源、`adPlayVersion`、SDK 热更新版本、已缓存物料、上次奖励展示时间、奖励时间及 ticket 等。在主链无网络时直接报错；命中免广告、召回免广告、已有播放广告页、无障碍模式或部分免费听冲突条件时返回空广告列表。

`InsertSoundPatchAdHelper` 另外建立头/中/尾 SoundAdManager，按配置与 `Track.adBreakPoints` 安排插播。Head/Tail 请求的 `insertPatchPosition` 显式区分位置。请求超时处理后忽略迟到结果，避免旧广告插入已变化的播放任务。

证据：`opensdk/player/advertis/q.java:881-920,1031-1061,1671`；`host/manager/ad/z.java:90-150,190-239,249-273,289-332,460-472`；`opensdk/player/advertis/InsertSoundPatchAdHelper.java:290-326`；`HeadSoundAdManager.java:136-179`。

频控不是只有服务端：`AdClientRequestFrequencyHelper` 读取广告位对应 `FrequencyInfo`，区分亮屏/闭屏的 `totalCount`、`lastExposureTime`、`intervalCount`，校验 `dayMaxShow`、`intervalSecond`、`maxShow`；跨天清零，短周期过期清零。旧贴片请求还检查上次声音贴片曝光时间、广告场景和配置下发的最小曝光间隔。具体秒数/次数来自服务端或实验配置，本 APK 无法给出所有用户的确定值。

证据：`host/manager/ad/videoad/AdClientRequestFrequencyHelper.java:32-160`；`host/manager/request/a.java:966-1005,1015-1038`。后一方法有反编译分支异常，报告不据其 Java 文本宣称所有实际门控条件的精确布尔表达式。

### 缓存及竞价

`CacheSDKAdManager` 用按 `dspPositionId` 分组的 Stack 保存 SDK 广告对象，拒绝 `INoCacheAd`，记录 saveTime，消费后删除。有效性检查会清理过期缓存；`adCacheTime` 默认 15 分钟，配置中心可改，开屏缓存另校验 SDK 广告自身 `isValid()`。素材级缓存由 `PreloadAdManager` 等下载图片/视频/声音，不能把 SDK 对象的15分钟直接套到全部素材文件。

`WaterfallLoadManager` 首先判断是否新开屏 RTB 或通用 RTB；旧链保存自有 XM 兜底、从第三方物料建立加载队列，优先考虑有效缓存，另对京东竞价广告进行价格比较。真实价格、竞价成功/失败及填充率本次没有运行证据。

证据：`adsdk/aggregationsdk/CacheSDKAdManager.java:16-20,34-69,84-112,115-155`；`adsdk/aggregationsdk/waterfall/WaterfallLoadManager.java:79-175`；`adsdk/preload/PreloadAdManager.java:368`。

### 免广告、会员与设置

不能从 VIP 字段推断“所有 VIP 所有位置永远免广告”。本包可确认的主要机制是：

1. **业务规则下发**：`requestAdFreeConfig()` 请求 `/mobile-resource-position/resource/advertising/free/v2`，解析 `AdFreeInfo`，记录 updateDate/userType；`CommonAdFreeManager` 持久化到 `ad_free_main_request_body`，按日期和新用户/召回用户状态恢复、失效、更新，同时通知播放进程。广告 SDK 通过反射调用 `CommonAdFreeForReflect.interceptAdRequest(slotId)`，由 `CommonAdFreeManager` 按广告位列表处理。精确循环匹配方法的 JADX 输出有错误，故只确认证据明确的字段/调用和按广告位处理的设计，不据错误 Java 重写完整算法。
2. **特定用户权益**：`ResourcePositionAdFreeManager` 请求旧版 `/resource/advertising/free`，本地保存 `resource_ad_free_day` 等，存在长时间未打开 App 的召回路径和实验控制。
3. **特定内容规则**：`PlayPageAdFreeManager` 下载配置到本地文件，用专辑ID查询免广告列表并通知播放进程，说明免广告还可能由内容集合决定。
4. **设置页开关**：隐私页会请求 `ad-vip/ad-free-switch/entry`，据服务端返回状态/文案显示入口；切换调用 `/set`，body 仅 `enabled`。本次未登录运行，不确定该入口对何种会员开放、点击后权益持续多久。
5. **激励权益**：观看广告可以触发业务奖励回调；应以业务层和服务端发放为准，不能认为客户端 `onReward(true)` 就证明账户获得了时长/会员。

证据：`adsdk/request/XmAdRequest.java:112-173`；`host/manager/adfree/CommonAdFreeForReflect.java:7-11`；`CommonAdFreeManager.java:82-132,147-181,197-242,259-340`；`host/manager/ResourcePositionAdFreeManager.java:44-64,79-95,202-224`；`host/manager/play/PlayPageAdFreeManager.java:226-251,397-407,435-456`；`main/fragment/setting/PrivacySettingFragmentNew.java:661-738`。

### 激励广告怎样判定完成

`RewardVideoAdManager` 保存业务传入的广告位、额外参数、listener，生成 requestId 并从列表加载候选；根据物料选择自有视频、GDT 或 CSJ 的激励/全屏/Draw 类型，加载失败可尝试下一个。自有 `XmRewardVideoAdFragment` 在视频完成或可暂停计时器到达下发的时长时调用 `requestReward()`，用 `reward` 标志防止重复回调；管理器也用 `hasCalledRewardSuccess` 防重，把布尔结果交给业务 `onReward(z)`。

这只是 SDK 层完成信号。`onRewardSuccess(false)` 也会设置内部 `isRewardSuccess=true`（表示回调已发生）并将真实结果放在 `isRealFinishTask`，因此分析时不能只读取该内部变量就判断领奖成功。

阅读场景 positionId=279 在关闭且相关状态满足时调用 `/hook/showWithRead`：携带 `adId`、`rewardSendTime`、`xt`、`fallbackReq`，并调用宿主 `getPlaySignature()` 生成 signature。其他奖励场景通过宿主回调或业务接口完成，服务端核验和账本不能从本地 APK 全面确认。包内 `assets/rewardVideoConfig.json` 提供提前关闭确认、完成/失败文案以及时长、点击、点击停留等解锁样式的配置结构；这能证明支持多种奖励交互模板，但不能证明全部模板在本版本当前生效。

证据：`adsdk/aggregationsdk/rewardvideoad/RewardVideoAdManager.java:92-151,246-260,334-394,432-465`；`.../view/XmRewardVideoAdFragment.java:914-960,988-1005`；`analysis/packaged/assets/rewardVideoConfig.json`。

### 曝光、点击、外链与数据

物料 `AdModel.fromJSON()` 解析 `showUrls`、`clickUrls`、`thirdShowStatUrls`、`thirdClickStatUrls`、`thirdStatUrl`、深链唤起成功/失败 URL。曝光调用 `ShowRecordManager → ShowRecord → ShowRecordUtil`，对符合事件条件的物料发第三方监测 URL，并生成内部 `AdCollectData`，由 `XdcsManager.statOnlineAd()` 发往 `/api/v1/adRealTime`；部分事件单独记录真实曝光、关闭、播放时长与完成。点击链 `ClickRecord → ClickRecordUtil` 同样执行第三方监测、内部记录及 link 装饰，某些点击统计支持 GET 或 JSON POST，并带 `ubtReportMap`。`ThirdAdStatUtil` 替换监测宏后调用 GET，不是点击所有外链才上报。

`ShowRecord` / `ClickRecord` 还登记广告展示/点击与安装归因，App 有广告落地页、下载/安装相关组件；仅静态证据不能判定某条广告实际下载了什么。

广告请求共享账号/设备上下文，但并非把 Cookie 无条件给所有监测域名：`XmAdSDK` 的 header handler 调用 `XmAdCookieWhiteListManager.canAddCookie()` 决定是否加入主 App Cookie，缺省允许 ximalaya 内部域，配置可扩展白名单；判定实现使用 host 的字符串 `contains`，不是严格的域名后缀解析。该实现值得单独测试，但本次没有验证可利用性，也没有发生 Cookie 外传的运行证据。

Cookie 构造包含设备标识 `环境&_device`、账号 `环境&_token=uid&token`、型号、系统版本、网络/运营商、UA、部分设备/定位字段。实际主 App 分支通常优先复用 `getCookieForMainApp()`，仅 fallback 才完整使用 SDK CookieHelper。不要据 CookieHelper 支持字段便认定所有字段在所有设备实测均采集：IMEI/MAC/OAID/定位受权限、系统返回、宿主控制器和远端配置约束。宿主给广告控制器 `isCanUseLocation=false`，同时可经 `getXmLocation()` 提供 App 自身定位；JAD 控制器还 `isCanUseIP=false` 同时提供 `getIp()`，因此这些 false 的含义是禁止 SDK 自行取用，不能等同于从未给广告任何位置/IP。

证据：`adsdk/model/AdModel.java:843-847,1044-1045`；`adsdk/aggregationsdk/record/ShowRecord.java:13-15`、`ShowRecordUtil.java:73-140`；`ClickRecord.java:15-31`、`ClickRecordUtil.java:36-111`；`adsdk/manager/ThirdAdStatUtil.java:118-122,1382-1402`；`adsdk/XmAdSDK.java:165-198`；`adsdk/request/XmAdCookieWhiteListManager.java:21-26,41-62,78-95`；`CookieHelper.java:33-78,199-273,316`；`host/manager/ad/t.java:230-297,352`。

### 未验证边界与复核

- 未启动 APK、未登录、未请求真实广告服务，所以没有真实广告填充率、曝光次数、奖励到账或会员免广告范围的运行结论。
- 服务端下发、第三方 SDK 网络请求、广告热更新模块与原生库内部实现可能改变具体请求细节；本节覆盖已追踪到的自有调度与关键业务请求，不是所有第三方域名的穷尽网络清单。
- 可用 `rg -n 'adx/ad|soundPatch|insert_sound_patch|adRealTime|ad-free-switch' analysis/jadx/sources/com/ximalaya/ting/android/{adsdk,host}` 复核端点，再按上列具体类/方法读取调用方。
- ✓ 广告请求接口及参数；✓ 聚合SDK调用；✓ 开屏/信息流/贴片/激励；✓ 缓存与频控；✓ 免广告逻辑及设置；✓ 曝光点击统计与隐私字段；✓ 未验证项明确标记。

## 五、复核、未知项与验收

### 关键证据入口

- [APK 身份与各 DEX 哈希](evidence/artifact-identity.json)
- [REA 容器证据摘要](evidence/rea-artifact-summary.json)（完整证据见同目录 `rea-artifact.json`）
- [应用版本及权限](evidence/badging.txt)、[清单](evidence/manifest.txt)、[签名验证](evidence/signature.txt)
- [网络安全配置](evidence/network-security-config.txt)
- [JADX 覆盖与未恢复方法清单](evidence/decompilation-coverage.json)、[处理日志](evidence/jadx.log)
- [主要业务请求实现](jadx/sources/com/ximalaya/ting/android/host/manager/request/CommonRequestM.java)
- [登录请求](jadx/sources/com/ximalaya/ting/android/loginservice/LoginRequest.java)、[登录状态保存](jadx/sources/com/ximalaya/ting/android/host/manager/account/l.java)、[刷新逻辑](jadx/sources/com/ximalaya/ting/android/host/manager/account/h.java)
- [播放地址处理](jadx/sources/com/ximalaya/ting/android/opensdk/util/af.java)
- [广告请求](jadx/sources/com/ximalaya/ting/android/adsdk/request/XmAdRequest.java)、[聚合调度](jadx/sources/com/ximalaya/ting/android/adsdk/aggregationsdk/AggregationManager.java)
- [包内 Cookie 配置](packaged/assets/cookie.json)、[激励模板配置](packaged/assets/rewardVideoConfig.json)

### 可在本工作区执行的核验

```sh
cd /Users/xiaobaitu/github.com/shixi
shasum -a 256 ximalaya_app.apk
/Users/xiaobaitu/Library/Android/sdk/build-tools/36.0.0/aapt2 dump badging ximalaya_app.apk
rg -n 'last_refresh_token_time|86400000|refreshTokenResult' analysis/jadx/sources/com/ximalaya/ting/android/host/manager/account/h.java
rg -n 'getCoreCookie|&_token|getCookieForH5' analysis/jadx/sources/com/ximalaya/ting/android/host/manager/request/CommonRequestM.java
rg -n 'smsKey|signature|nonce' analysis/jadx/sources/com/ximalaya/ting/android/loginservice/LoginRequest.java
rg -n 'getPlayerRequestProvider|getAntiLeechUrl|playUrlInfos' analysis/jadx/sources/com/ximalaya/ting/android/host/manager/request/CommonRequestM.java
rg -n 'adx/ad|soundPatch|insert_sound_patch|adRealTime|ad-free-switch' analysis/jadx/sources/com/ximalaya/ting/android/adsdk analysis/jadx/sources/com/ximalaya/ting/android/host
```

第一条哈希应与样本表相同。其余命令只读取本地样本和分析文件，不会向业务服务器发送请求。

### 尚不能从本次证据确定的内容

| 问题 | 当前证据边界 |
|---|---|
| token、nonce、authCode、媒体签名的真实期限及设备/IP绑定 | 客户端能看到传递与判断，服务器完整规则未知；没有真实会话或响应 |
| 登录/播放 JNI 的完整加密算法与密钥派生 | 已追到调用边界，未反汇编原生实现；不猜算法 |
| 当前线上选择 v3/v4、广告供应商、免广告用户范围、频控具体值 | 受运行条件和远端配置影响，未读取当前账号配置 |
| 接口当前在线可用、登录成功、音频真正可播、广告展示、奖励/支付到账 | 未运行、未抓包、未执行这些业务，不能据静态调用链断言成功 |
| 所有第三方流量、远端 H5/RN 更新、完整直播 RTC/IM 协议 | 超出本次核心调用链覆盖；没有全量动态证据 |

### 验收与文件变更

- ✓ 核验指定 APK 的包名、版本、哈希及静态分析覆盖。
- ✓ 主要功能提供代表性接口、HTTP 方法、关键参数/响应及调用证据；播放授权和地址处理详细展开。
- ✓ 登录方式、签名/加密边界、会话保存/恢复、刷新、失效、退出及免密重新登录详细展开。
- ✓ 广告请求、SDK聚合、开屏/信息流/贴片/激励、缓存频控、免广告与统计详细展开。
- ✓ 其他内容保持简要，并区分静态证实、推断与未验证；没有编造抓包结果或可用凭据。
- ✓ 提供源码定位、证据文件与本地复核命令。
- ⏭️ 真机运行、登录抓包及线上业务验证：用户已确认仅静态分析，故不适用。

变更前：工作区只有用户提供的 APK。变更后：新增本报告及 `analysis/` 内对应的反编译代码、打包资源和核验记录；原 APK 未修改。JADX 工具及其配置/缓存放在本次专用 `/tmp/shixi-jadx*` 路径，没有改写全局工具或 REA 客户端配置。回退时删除本次新增的 `analysis/` 目录即可；如不再需要，也可删除上述专用临时目录。
