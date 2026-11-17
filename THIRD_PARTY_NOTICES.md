# 第三方组件说明

本项目原创代码采用 AGPL-3.0-only，见 [LICENSE](LICENSE)。该许可不改变第三方代码、原生库、商标和音频内容的权利归属。

## 开源依赖

AndroidX（Compose、Media3、Room、DataStore、CameraX 等）、Kotlin、kotlinx.coroutines、OkHttp、Okio、Coil 和 ZXing 使用 Apache License 2.0。各组件的版权与声明以对应版本发行物为准，许可正文见 [Apache-2.0](licenses/Apache-2.0.txt)。

## 原生组件

`app/src/main/jniLibs/arm64-v8a/libencrypt.so` 与 `liblogin_encrypt.so` 为喜马拉雅原始客户端中的原生组件，用于现有接口签名和登录协议。本仓库没有提供其源码，也未取得可核验的独立再分发授权记录；它们不适用本项目的 AGPL 许可。公开分发前需明确其授权依据。

其余原生库由上述开源依赖引入，遵循所属组件的许可。

## 名称与内容

喜马拉雅的名称、商标、节目封面及音频内容归各自权利人所有。Ximalaya Lite 是独立第三方客户端，与喜马拉雅官方无隶属或授权关系。账号权益与内容访问限制仍由服务方决定。
