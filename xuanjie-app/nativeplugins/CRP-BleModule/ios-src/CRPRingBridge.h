//
//  CRPRingBridge.h
//  CRP-BleModule (iOS)
//
//  戒指3（开发商3 / CRP / MYRingSDK）iOS 桥接层。
//
//  ┌─ 为什么要这一层 ────────────────────────────────────────────────────────┐
//  │ Android SDK(1.1.3) 是「查询式」：queryCurrentSteps() 发指令，数据走监听回调  │
//  │ iOS SDK(1.5.5) 是「推送式」：一切数据由 CRPManagerDelegate 的 receiveXxx 推上来 │
//  │ 但 JS 层（xuanjie-app/utils/ring_channels/crp.js）已经按「查询返回 Promise」写好，│
//  │ 所以这一层的职责是：把「查询」翻译成「触发读取 + 等 delegate 回调 + 超时」      │
//  │ -> JS 侧一行都不用改，Android/iOS 共用同一套门面契约。                       │
//  └────────────────────────────────────────────────────────────────────────┘
//
//  ⚠️ 本文件与 CRPBleModule.m 都在 Windows 上写的，**尚未在 Xcode 编译验证**。
//     在 Mac / 云 Mac 上首次编译时，请按 CRPSmartRing-Swift.h 校正：
//     头文件位置：CRPSmartRing.framework/Headers/CRPSmartRing-Swift.h
//
//  关键 iOS API（已从头文件核对过，行号对应 CRPSmartRing-Swift.h）：
//    CRPSmartRingSDK.sharedInstance                          (2067)
//    - scan:duration progressHandler:completionHandler:      (2110)
//    - interruptScan                                         (2111)
//    - getBattery:                                           (2116)
//    - getSteps                                              (2115)
//    - setTimingHeartRate: / getTimingHeartRateInterval:      (2130/2131)
//    - setTimingHRV: / setTimingO2: / setTimingStress:        (2132/2134/2292)
//    - getTimingHeartRate:: / getTimingHRV:: / getTimingO2::  (2151/2152/2153)
//    - setStartSingleHR / setStopSingleHR                    (2136/2137)
//    - setStartHRV / setStopHRV / setStartStress / setStopStress (2139~2142)
//    - setStartSpO2 / setStopSpO2                            (2145/2146)
//    - readStepDataWithDay:                                  (1222)
//    - readSleepDataWithDay:                                 (1223)
//    - readGoMoreSleepDataList / readGoMoreSleepDataDetailWithId: (1285/1286)
//    delegate（@protocol CRPManagerDelegate，1412~1453）：
//      didState: / receiveSteps: / receiveHeartRate: / receiveRealTimeHeartRate:
//      receiveHRV: / receiveSpO2: / receiveStress: / receiveSleepList: / receiveWearState:
//

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// 统一回调：与 Android 插件保持同一形状 {success, msg, data, event}
typedef void (^CRPBridgeReply)(NSDictionary * _Nonnull result);
/// 流式事件：多次回调（监听类接口用）
typedef void (^CRPBridgeEvent)(NSDictionary * _Nonnull event);

@interface CRPRingBridge : NSObject

+ (instancetype)shared;

/// 是否有 SDK、是否已连接
@property (nonatomic, readonly) BOOL sdkAvailable;
@property (nonatomic, readonly) BOOL connected;
/// 最近一次拿到的 peripherals（扫描结果）
@property (nonatomic, readonly) NSArray *discoveries;

// ---------------- 扫描 / 连接 ----------------
- (void)startScan:(CRPBridgeEvent)onEvent;      // 推送 {name, mac, rssi} / {event:"scanComplete"}
- (void)stopScan;
- (void)connectAddress:(NSString *)address reply:(CRPBridgeReply)reply;
- (void)disconnect:(CRPBridgeReply)reply;
- (void)setConnectionListener:(CRPBridgeEvent)onEvent;

// ---------------- 电量 ----------------
- (void)queryBattery:(CRPBridgeReply)reply;

// ---------------- 步数（对应 Android queryCurrentSteps / queryHistorySteps / queryHistoryStepsDetails） ----------------
- (void)queryCurrentSteps:(CRPBridgeReply)reply;
- (void)queryHistoryStepsDaysAgo:(NSInteger)daysAgo reply:(CRPBridgeReply)reply;
- (void)queryHistoryStepsDetailsDaysAgo:(NSInteger)daysAgo reply:(CRPBridgeReply)reply;

// ---------------- 睡眠 ----------------
- (void)queryHistorySleepDaysAgo:(NSInteger)daysAgo reply:(CRPBridgeReply)reply;
- (void)queryHistorySleepList:(CRPBridgeReply)reply;
- (void)querySleepDetailsId:(NSNumber *)sleepId reply:(CRPBridgeReply)reply;

// ---------------- 自动测量（timing） ----------------
- (void)enableTimingHeartRate:(NSInteger)minutes reply:(CRPBridgeReply)reply;
- (void)disableTimingHeartRate:(CRPBridgeReply)reply;
- (void)queryTimingHeartRateState:(CRPBridgeReply)reply;
- (void)enableTimingBloodOxygen:(NSInteger)interval reply:(CRPBridgeReply)reply;
- (void)enableTimingHrv:(NSInteger)interval reply:(CRPBridgeReply)reply;
- (void)enableTimingTemp:(CRPBridgeReply)reply;

// ---------------- 历史（自动测量的结果回读） ----------------
- (void)queryHistoryHeartRate:(CRPBridgeReply)reply;
- (void)queryHistoryBloodOxygen:(CRPBridgeReply)reply;
- (void)queryHistoryHrv:(CRPBridgeReply)reply;
- (void)queryHistoryStress:(CRPBridgeReply)reply;

// ---------------- 实时测量 ----------------
- (void)startMeasureType:(NSString *)type onEvent:(CRPBridgeEvent)onEvent;
- (void)stopMeasureType:(NSString *)type reply:(CRPBridgeReply)reply;

// ---------------- 佩戴状态 / 固件 ----------------
- (void)listenWearState:(CRPBridgeEvent)onEvent;
- (void)queryFirmwareVersion:(CRPBridgeReply)reply;

@end

NS_ASSUME_NONNULL_END
