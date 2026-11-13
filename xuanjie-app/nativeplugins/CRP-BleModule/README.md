# CRP-BleModule（开发商3 / MYRingSDK）接入说明

对应：`uni.requireNativePlugin("CRP-BleModule")`，JS 侧渠道实现在
`xuanjie-app/utils/ring_channels/crp.js`。

## 为什么要有这个插件

开发商3 **没有提供 uni-app 插件、也没有 JS 实现** —— 只有 Android AAR
（`my-ring-ble-1.1.3-1015.aar`，410 KB）与 iOS Swift framework（`CRPSmartRing.framework`）。
所以 A/B/C/D 四档全部依赖这一层：**我们自己写原生插件**，再由 `crp.js` 统一成 `ring.*` 门面
（与开发商1 的 `native.js` 同构）。

## 两条构建路径（离线基座为**权威**）

项目实际打包走**离线基座**（HBuilder-Integrate-AS），所以模块写在基座里：

| 路径 | 位置 | 状态 |
| --- | --- | --- |
| **离线基座（权威）** | AAR → `simpleDemo/libs/`；模块类 → `simpleDemo/src/main/java/com/huarong/yuanqibao/CRPBleModule.java`；登记 → `simpleDemo/src/main/assets/dcloud_uniplugins.json` | ✅ **已验证：`compileDebugJavaWithJavac` 与 `assembleDebug` 均 BUILD SUCCESSFUL** |
| DCloud 插件（云打包/自定义基座） | 本目录 `package.json` + `android/` + `ios/` | 保留备用；云打包时用 |

> 基座里现有 `EcgModule` / `ReminderModule` / `FindDeviceModule` 也是同一套路
> （`dcloud_uniplugins.json` 里按 `{name, class}` 登记），本模块与它们同构。

## Android 构建方式（**必须先设 JAVA_HOME**）

AGP 8.7 要求 JDK 17+；本机默认 `JAVA_HOME` 指向 JDK 11，直接构建会失败并报：

```
Android Gradle plugin requires Java 17 to run. You are currently using Java 11.
```

用 HBuilderX 自带的 Corretto 17 即可：

```powershell
$env:JAVA_HOME = 'D:\DEV\001-tools\HBuilderX\plugins\amazon-corretto'   # openjdk 17.0.12
cd xuanjie-native-app\android\HBuilder-Integrate-AS
.\gradlew.bat :simpleDemo:assembleDebug --offline --console=plain
```

## 已验证的事实（2026-10-15）

- AAR `classes.jar` 里**只有 `com/crrepa/**`**，未打包 io.dcloud/okhttp，与基座现有 AAR **无重复类**；
- 引入该 AAR 后 **APK 权限仍是 16 条、全部在白名单内**，没有新增敏感权限；
- 模块方法/回调签名全部按 AAR 的 `javap` 结果写（不是文档转述），要点：
  - `CRPBleClient.create(Context)` 静态方法；`scanDevice(CRPScanCallback, long)`；`cancelScan()`
  - `CRPScanDevice` **没有** `getName()/getAddress()`，要通过 `getDevice()` 拿 `BluetoothDevice`
  - `CRPHeartRateChangeListener` 有 **5 个**抽象方法：`onTimingInterval/onRealtimeHeartRate/onHeartRate/onHistoryHeartRate/onTimingHeartRate`
  - `CRPBleConnectionStateListener.STATE_{DISCONNECTED,CONNECTING,CONNECTED,DISCONNECTING}`
  - 历史数据按天分页：`com.crrepa.ble.conn.type.CRPHistoryDay`

## 回调约定（JS 侧不要按 HTTP 的 code 判）

```js
// 一次性
uni.requireNativePlugin('CRP-BleModule').queryBattery(r => { /* {success, msg, data} */ })
// 流式（扫描 / 连接状态 / 实时测量）：同一个 callback 会被多次回调
uni.requireNativePlugin('CRP-BleModule').startScan({ seconds: 10 }, r => { /* 多次 */ })
```

## 待办

1. **真机最小闭环**（需要一只开发商3 戒指 + 安卓机）：init → 扫描 → 连接 → 状态 → 电量 → 心率实时值；
   把每步证据（日志/数值）记进 `docs/`。
2. **M2 扩展**：血氧 / HRV / 压力 / 体温 / 睡眠 / 步数 的查询方法按同一模式补齐（接口名见
   `docs/crp-ring-app-parity-plan.md`）。
3. **iOS**：`ios/` 下建 OC 插件类 + `CRPSmartRing.framework`（Swift）桥，并把 `CRPManagerDelegate`
   回调转发成与 Android 相同的 JS 契约。本轮只要求骨架 + 可行性结论。
4. ⚠️ **DFU/OTA 缺依赖**：厂商只给了 `my-ring-ble-1.1.3-1015.aar`，**没有 DFU 包**
   （样例 manifest 里声明的 `com.realsil.sdk.dfu.DfuService`、`com.example.dfulibrary.SifliDfuService`
   类在两个 AAR 里都不存在）→ OTA 必须等厂商补 DFU 相关 AAR，否则会引入不存在的类。
5. ⚠️ **合规**：AAR 是否可随仓库分发需与厂商确认（本仓库对厂商 AAR 的既有做法是入库，
   因为"只从 git 删除、磁盘还在"曾导致别人拉下来构建不了）。
