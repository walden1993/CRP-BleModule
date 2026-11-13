# 上传到 GitHub 仓库后怎么跑

这个包的内容要上传到 👉 https://github.com/walden1993/CRP-BleModule

## 目录结构（上传后应长这样）

```
.github/workflows/build-ios-plugin.yml      ← Actions 工作流
xuanjie-app/nativeplugins/CRP-BleModule/
    package.json
    ios/CRPSmartRing.framework/             ← 厂商 SDK（8.6 MB）
    ios-src/CRPBleModule.h / .m
    ios-src/CRPRingBridge.h / .m
    ios-src/project.yml / build.sh
    ios-src/Resources/PrivacyInfo.xcprivacy
    ios-src/fastlane/Appfile / Fastfile
    ios-src/README.md
```

**包内没有证书、私钥、密码**（已扫描确认），可以放心上传。

## 两种上传方式

### 方式一：网页拖拽（推荐，无需命令行）

1. 解压本压缩包到一个空文件夹
2. 打开仓库页面 → **Add file → Upload files**
3. 把解压出来的 `xuanjie-app` 整个文件夹拖进去（会自动保留层级）
4. Commit
5. `.github` 是隐藏文件夹，网页拖拽可能漏掉 → 用 **Add file → Create new file**，
   文件名填 `.github/workflows/build-ios-plugin.yml`，把压缩包里同名文件的内容粘进去，Commit

### 方式二：命令行（如果这台电脑能连 github.com）

```bash
git clone https://github.com/walden1993/CRP-BleModule.git
# 把解压出来的内容拷进 clone 下来的目录（保持 .github 与 xuanjie-app 同级）
cd CRP-BleModule
git add -A && git commit -m "feat: iOS 插件源码 + 编译 workflow" && git push
```

## 跑编译

1. 仓库页面 → **Actions** 标签
2. 左侧选 **build-ios-plugin** → 右侧 **Run workflow** → 绿色按钮
3. 等 3~5 分钟，出现绿色 ✓
4. 点进这次运行 → 页面底部 **Artifacts** → 下载 `libCRPBleModule-arm64`
5. 解压得到 `libCRPBleModule.a`
6. 放回本地：

```
D:\DEV\007-git\huarong-iot\xuanjie-app\nativeplugins\CRP-BleModule\ios\libCRPBleModule.a
```

## 如果 Actions 报错

把失败那一步的日志（红色 ✗ 那段的最后 30 行）发给我，我来改源码。
最可能的两类：

| 报错关键词 | 含义 | 处理 |
|---|---|---|
| `DCUniModule.h not found` | 缺 uni-app iOS 离线 SDK 的 DCUniBase 头文件 | 我改 `project.yml`，改成把 DCUniBase 头也一起放进仓库 |
| `framework built for iOS 26.2` / `SDK version mismatch` | runner 的 Xcode 太旧 | 换 `runs-on: macos-15` → 更新的镜像，或改 `macos-26` |
| `@import CRPSmartRing` module not found | framework 的 modulemap 未被识别 | 改成 `#import <CRPSmartRing/CRPSmartRing-Swift.h>` |

## 安全提醒

- 仓库**建议设为 private**：`CRPSmartRing.framework` 是厂商商业授权 SDK，公开分发有授权风险
- 私有仓库 Actions 免费额度 2000 分钟/月，本任务约 3~5 分钟，够用
- **不要**把 `证书信息/ios证书/` 里的 `.p12` / `.key` / `p12_password.txt` 传到任何公开仓库
