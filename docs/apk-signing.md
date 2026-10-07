# 固定 APK 签名

本机构建读取仓库根目录的 `keystore.properties`；格式见
`keystore.properties.example`。配置齐全时，debug 和 release 使用同一个项目密钥。
debug 的包名仍为 `dev.amenhancer.module.debug`，release 的包名为
`dev.amenhancer.module`。

持久密钥存放在 `signing/am-plus-plus-release.jks`。密钥文件和包含密码的
`keystore.properties` 均被 Git 忽略；备份这两个文件，换机器时沿用它们。
不要每次构建重新生成密钥。

GitHub Actions 的所有 push 构建读取以下仓库 secrets，并在打包后验证签名：

- `AMPP_RELEASE_KEYSTORE_BASE64`：同一个密钥文件的 Base64
- `AMPP_RELEASE_STORE_PASSWORD`
- `AMPP_RELEASE_KEY_ALIAS`
- `AMPP_RELEASE_KEY_PASSWORD`

未配置密钥时，debug 构建沿用 Android 默认调试签名。pull_request 构建不会解码
项目私钥，其 APK 用于检查；需要在手机上更新时，使用配置了项目密钥的 push 构建。

固定签名只保证以后同包名、同密钥的 APK 可以覆盖更新。旧 APK 若由另一个密钥
签名，Android 仍会拒绝覆盖；给新 APK 重签不能改变旧 APK 的签名。
