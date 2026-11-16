# Brand Strategy: 拾昔

## Stage Progress
- Current stage: 6
- Previous gate passed: 用户选定 B；几何系统已建；三个数字应用形式已完成检查（启动器实机截图、品牌组合渲染、单色主题形式渲染）。
- Next gate to pass: 本轮数字 Logo 资产交付。

## Strategic Foundation
- Core concept: 简洁表达轻量化听书与节目收听，传递故事陪伴。
- Brand personality: 专注、温暖、可靠、轻盈。
- Competitive gap identified: 保留音频类别联想，用独立连续轮廓建立识别。

## Deliverables This Stage
- `assets/logo.svg`：主色矢量，#BC451D；`logo-reversed.svg`：纯白反白；`logo-monochrome.svg`：#252925 单色。三者使用同一 evenodd 负形母版，路径数据见 `geometry.json`。
- Android：`app/src/main/res/drawable/launcher_foreground.xml` 已替换为该母版；保留现有白色背景和自适应资源中的 monochrome 引用。
- `preview-b.html` / `preview-b.png`：品牌组合、圆形/圆角方形蒙版、单色以及 24/48/96 CSS 像素渲染。预览标题与说明为设计注释，不是产品口号。
- 几何：64×64 母版，标志实际范围约 x10–55 / y8–55；独立使用时保护母版留白，标志外边界至少 8 单位净空；最小母版显示尺寸 24 px，主要图标建议至少 48 px。24 px 已视觉检查负形保留，但不得追加细节。
- Android：108×108 前景中将母版平移至 (22,22)，核心位于中心安全区域；以实际 launcher.png 核查圆形裁切无缺失。圆角方形和主题单色在渲染预览核查；未宣称覆盖所有厂商启动器。
- 色彩：#BC451D / 白色 5.24:1，#BC451D / #FCFCFB 5.10:1，正文 #252925 / #FCFCFB 14.38:1；均达到文字 AA 4.5:1。纯白标志在 #171B18 深色背景渲染检查通过。
- 字体：品牌名称沿用平台中文无衬线字体的 semibold，预览使用系统字体；无第三方字体文件及授权依赖。横向组合间距至少半个标志高度。不将品牌 Logo 替换播放、暂停、返回等功能图标。
- 禁用方式：拉伸、旋转、另加描边或投影、改变负形比例、压缩安全留白、添加声波/耳机装饰、使用渐变替代纯色母版。
- 范围：数字 Logo 与 Android 图标；印刷、品牌口号、字体定制、动画不在此次请求范围。仅制作临时安装验证包，未覆盖此前交付 APK，也未更换签名或应用版本。

## Rationale Log
| Decision | Strategic Basis | Alternatives Considered |
|----------|-----------------|------------------------|
| 右侧横向部分缩短，转接圆润 | 减轻用户选定 B 草案的插头联想，保持小尺寸轮廓连续 | 不增加独立声波或装饰性细节 |
| 沿用橙色与系统字体 | 与已确认的现有产品设计保持一致 | 不新增颜色和字体授权依赖 |
| 统一母版派生所有版本 | 避免各触点形状漂移 | 不单独描摹小图和 Android 版本 |

## Quality Gate Status
- Ready for next stage? YES（本轮数字资产已交付）
- Blockers: 无。识别和差异化为设计评估，未进行独立用户辨识研究或商标检索；平台验证为特定模拟器与列明渲染，不扩大为全部机型认证。
