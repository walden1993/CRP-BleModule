# CRP-BleModule 的 iOS 静态库怎么编

这个目录是 **在 Mac 上编译 `libCRPBleModule.a`** 用的源码工程。
uni-app 的 iOS 原生插件必须是**预编译静态库**，HBuilderX 云打包只负责拼装，不负责编译插件。

产物放回上一级：`../ios/libCRPBleModule.a`（`package.json` 里 `ios.libs` 已声明）。

---

## 环境事实（决定用哪条路）

厂商 `CRPSmartRing.framework` 的构建信息：

```
DTXcode 2630 · DTSDKName iphoneos26.2 · DTPlatformVersion 26.2 · MinimumOSVersion 9.0 · arm64
```

也就是说它是 **Xcode 26.3 / iOS 26.2 SDK** 编的。

| 你的机器 | 能装的最高 Xcode | 结论 |
|---|---|---|
| Windows（当前主力机） | 无 | ❌ 完全不能编 |
| Mac mini 2014 + macOS 12.7.6 | **Xcode 14.3.1**（iOS 16.4 SDK） | 🟡 **可以试**：静态库只用到头文件，不链接 framework，有可能成功；但头文件里若有新 SDK 类型会报错 |
| 云 Mac / GitHub Actions macOS runner | Xcode 15/16/26 | ✅ 最稳 |

---

## 路线 A：GitHub Actions（免费，推荐）

仓库里已备好 workflow：`.github/workflows/build-ios-plugin.yml`

```bash
# 1) 在 GitHub 建一个 private 仓库（不要推证书！.gitignore 已忽略 .p12/.key/p12_password.txt）
# 2) 只推插件目录 + workflow
git init && git add xuanjie-app/nativeplugins/CRP-BleModule .github/workflows/build-ios-plugin.yml
git commit -m "ios plugin"
git remote add origin git@github.com:<你>/<仓库>.git && git push -u origin main

# 3) GitHub → Actions → build-ios-plugin → Run workflow
# 4) 跑完下载 Artifacts：libCRPBleModule-arm64.zip
# 5) 解压出 libCRPBleModule.a，放回：
#    xuanjie-app/nativeplugins/CRP-BleModule/ios/libCRPBleModule.a
```

免费额度：公开仓库无限 · 私有仓库 2000 分钟/月（这个任务约 3~5 分钟）。

---

## 路线 B：有 Mac（含 Mac mini 2014 + Xcode 14.3.1）

```bash
# 一次性准备
xcode-select --install                      # 命令行工具
# 安装 Xcode 14.3.1（Monterey 最高版本）：App Store 或 developer.apple.com/download/all
brew install xcodegen

cd xuanjie-app/nativeplugins/CRP-BleModule/ios-src
./build.sh
```

`build.sh` 会：`xcodegen generate` → `xcodebuild -sdk iphoneos -arch arm64` → 复制产物到 `../ios/`。

**如果失败**，看报错类型：
- `framework built for iOS 26.2` / `SDK version mismatch` → 老 Xcode 链接不了，走路线 A 或 C
- `DCUniModule.h not found` → 补 uni-app iOS 离线 SDK 的 `DCUniBase` 头文件路径（改 `project.yml` 的 `HEADER_SEARCH_PATHS`）
- `@import CRPSmartRing` 报模块找不到 → 改成 `#import <CRPSmartRing/CRPSmartRing-Swift.h>`（`CRPRingBridge.m` 顶部已注明）

---

## 路线 C：云 Mac（按小时计费）

要求：macOS 15+、Xcode 26、xcodegen。把本目录拷上去后同样 `./build.sh`。
适合还想顺带做 **Transporter 上传 App Store** 的情况（上传只能在 macOS 做）。

---

## 编出来后怎么用

```text
../ios/libCRPBleModule.a    ← 云打包时 package.json 的 ios.libs 会带上它
../ios/CRPSmartRing.framework ← 已随插件提供（动态 framework，云打包负责 embed）
```

然后 HBuilderX → 发行 → App 云打包 → iOS，填写：

| 字段 | 值 |
|---|---|
| Bundle ID | `com.huarong.yuanqibao` |
| 证书 | `证书信息/ios证书/MyraRing_Distribution.p12` |
| 证书密码 | `证书信息/ios证书/p12_password.txt` |
| 描述文件 | `证书信息/ios证书/MyraRing_AppStore.mobileprovision` |

---

## 当前状态（2026-11-13）

| 项 | 状态 |
|---|---|
| 插件入口源码（49 个方法契约） | ✅ 已写 |
| 桥接层（推送式 → 查询式适配） | 🟡 骨架（20 已实现 / 9 待字段校正 / 17 待补） |
| 厂商 framework | ✅ 已复制进 `ios/` |
| `libCRPBleModule.a` | ❌ **未产出（阻塞云打包 iOS）** |
| Apple 证书 + 描述文件 | ✅ 已生成并交叉验证 |
| `manifest.json` 的 iOS 段（appid/bundleId） | ❌ 未配置 |
