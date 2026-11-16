# 精简候选验收记录

下文第一部分保留修订5的失败记录；当前修订6见文末。设备：隔离 emulator-5562，Android 14 / ARM64 / 4 KB。未登录、未输入账号、未支付、未打赏、未领奖。用户原有模拟器未操作。

候选：`implementation/work/slim-candidate.apk`，SHA-256 `5b88e51b6c976cbad042f7c65d112b942a9647a9d0b1334a213a60525a58bfe1`。

## 实际运行结果

| 检查 | 结果 | 证据 |
|---|---|---|
| 清洁安装、隐私与通知权限、兴趣页到首页 | 通过，主 PID 6705 持续运行 | `slim-home.xml` |
| 已确认会员页签及月卡预售 Bar | 首页均未显示；普通推荐/快听/我的保留 | `slim-home.xml` |
| 匿名搜索“单田芳经典” | 通过，真实专辑结果与标签 | `slim-search-results.xml` |
| 进入公开专辑 | 通过，乱世枭雄486集 | `slim-album.png` |
| 播放 | 通过，系统 PLAYING(3)，23,015 ms、speed=1.0 | `slim-media-playing.txt`、`slim-player.png` |
| 暂停 | 通过，PAUSED(2)，24,527 ms | `slim-media-paused-late.txt` |
| 恢复后切后台 | 通过，PLAYING(3)，84,539 ms | `slim-media-background.txt` |
| 恢复前台的广告时长窗口 | **未通过**，出现“看视频领30分钟时长”等广告引导，定位为 FreeListenDialogNew 的独立显示链 | `slim-offline-search.xml`；原调用 `main/playpage/dialog/d.java:1152` |
| 断网错误与恢复 | 前一候选 R4 已见“网络未连接/重试”，恢复同一查询成功；R5 当前测试被上述窗口打断，不能当作终版通过 | `final-offline-error.xml`、`final-network-recovered.xml` |
| 直播观看 | 未验证：尝试已有 URI/频道入口未取得可实际观看的房间，不以 Intent Status:ok 冒充成功 | `final-live-home.xml`、`final-live-ugc.xml` |
| 登录、支付、打赏、任务领奖 | 按用户要求不进行账号相关运行测试；未修改其主要入口和 native 库，不能因此声称服务端能力已通过 | payload比较及代码diff |

## 构建与范围自检

- Apktool 构建成功，签名 v1/v2 校验通过，ZIP 对齐通过：`functional-build.log`、`slim-signature.txt`、`slim-alignment.txt`。
- 原始 APK 124,973,695 字节，候选 122,712,636 字节，减少 2,261,059 字节（1.81%）。这是实际数值，不声称已大幅缩小。
- 最终只替换 classes3/4/5/11/12/13 六个 DEX及完整性配置 PNG，删除禁用的 GDT 插件资产；其它九个 DEX、Manifest及全部87个SO后缀成员保持原始字节：`slim-payload-comparison.json`。
- 共12个 smali文件的逐文件原文在 `implementation/work/functional-originals/`，差异为 `ads-functional.diff`、`promotions-functional.diff`、`main-functional.diff`、`member-bar-functional.diff`。
- 主动支付/登录/礼物/错误 Dialog没有全局禁用；没有调用奖励成功回调，没有修改任务资格或账号权益。
- 发现独立广告时长窗口后，需要窄修并重测。当前不将本记录作为“全部精简需求已完成”的证明。


## 修订6复验（2026-11-04）

候选 `implementation/work/slim-candidate-r6.apk`，SHA-256 `63d568e2eecb4ef3a5af7bb15d8af89b05c8694654099fead566ed94a6b6c260`。本节取代修订5的候选验收结论，保留上文失败证据以便追溯。

### 修复内容与边界

新增两处窄修：播放页中央调度在原支付分支之后过滤自动场景4/12/14/15；混合用途 FreeListenDialogNew 关闭自动广告倒计时和广告状态入口，隐藏相应视频按钮、手势、备用入口和广告副标题。非广告任务方法 w/x、主动领取 n、主动购买 e 和 onDismiss 原文不变；支付前置分支原文不变。逐方法证据为 `source-diff-check-r6.json`，补丁见 `promotions-functional.diff`。不是全局关闭 Dialog，也未伪造广告或奖励成功。

### 实机模拟器结果

| 检查 | 结果与证据 |
|---|---|
| 清空数据后启动、隐私确认、通知权限及兴趣页进入首页 | 通过，`r6-home.xml`；未见会员页签或已定位月卡推广Bar |
| 搜索大明演义 | 通过，真实专辑/声音结果，`r6-search-results.xml` |
| 完整播放页与公开音频 | 通过，`r6-play-start.png`；`r6-media-playing.txt` PLAYING(3)、45,030ms |
| 暂停 | 通过，`r6-media-paused.txt` PAUSED(2)、82,206ms |
| 恢复播放并切后台 | 通过，`r6-media-background.txt` PLAYING(3)、244,229ms，非零持续进度 |
| 回前台自动广告时长窗口 | 未复现修订5的看视频领时长窗口。首次回前台出现悬浮锁屏权限说明（`r6-resume.xml/png`），该功能提示保留；点“知道了”后再HOME并恢复，完整播放器正常，`r6-resume-after-permission.xml/png` 无该广告窗口 |
| 正常暂停、返回 | 通过，`r6-back.xml` 回到搜索结果且显示继续播放 |
| 断网搜索错误 | 通过，显示网络未连接/当前无网络/重试，`r6-offline-error.xml/png` |
| 恢复网络同一查询 | 通过，`r6-network-recovered.xml` 再次返回真实专辑结果 |
| 进程稳定性 | 此连续流程主PID10406未变，`r6-exit-info-final.txt`；Frida服务关闭，未注入App |
| 签名/对齐/原包对比 | 通过，`slim-signature-r6.txt`、`slim-alignment-r6.txt`、`slim-payload-comparison-r6.json` |

14个smali文件变更；六DEX及隐藏PNG被替换，GDT插件被删，其余负载成员保持原始字节。未直接修改SO机器码。122,712,636字节（117.03MiB），比原包减少1.81%。

### 验收限制

这是可安装的测试签名候选，不是原官方签名包；不能直接覆盖原官方安装。原包和原用户模拟器均未改动。登录、支付、打赏、任务领奖按范围未运行；直播未取得实际房间，未验证拉流。相关代码/库保留不等于这些能力已通过端到端测试。混合任务窗口非广告领取状态只做静态保留检查，未点击领奖。没有验证全部服务端AB、远程H5/RN推广或全部广告SDK网络初始化；不能承诺任何场景绝对无广告。

复验时使用隔离Android14 ARM64模拟器安装最终同哈希APK，重复“首页→搜索大明演义→播放→暂停/继续→HOME→恢复→返回搜索→断网→恢复网络搜索”。不要使用工作目录中的探针APK代替最终候选。
