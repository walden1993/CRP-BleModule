//
//  CRPBleModule.m
//  CRP-BleModule (iOS) — uni-app 原生插件入口实现
//
//  职责：把 JS 门面调用的方法名（与 Android 插件一致）转发给 CRPRingBridge。
//  真正的 SDK 交互都在 CRPRingBridge 里，这一层只做「参数解析 + 回调封装」。
//
//  ⚠️ 与第一版的重要修正（2026-11-13，参考 DCloud 官方文档）：
//      DCloud 原生插件 iOS 的回调**不是** DCUniModuleCallBackProtocol + invoke:，
//      而是 block 类型 **UniModuleKeepAliveCallback**：
//          - (void)xxx:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback
//          UNI_EXPORT_METHOD(@selector(xxx:callback:))
//      调用方式：callback(result, keepAlive)  —— keepAlive=NO 一次性，YES 可连续回调（流式）。
//      第一版写成 invoke:/invokeAndKeepAlive: 会导致 plugin 注册失败或回调不通。
//

#import "CRPBleModule.h"
#import "CRPRingBridge.h"

/// 取字符串参数
static NSString *CRPStr(NSDictionary *options, NSString *key) {
    id v = options[key];
    return [v isKindOfClass:[NSString class]] ? v : nil;
}

/// 取整型参数
static NSInteger CRPInt(NSDictionary *options, NSString *key, NSInteger def) {
    id v = options[key];
    return [v respondsToSelector:@selector(integerValue)] ? [v integerValue] : def;
}

@implementation CRPBleModule

#pragma mark - 扫描 / 连接

UNI_EXPORT_METHOD(@selector(startScan:callback:))
UNI_EXPORT_METHOD(@selector(stopScan:callback:))
UNI_EXPORT_METHOD(@selector(connect:callback:))
UNI_EXPORT_METHOD(@selector(disconnect:callback:))
UNI_EXPORT_METHOD(@selector(isConnected:callback:))
UNI_EXPORT_METHOD(@selector(setConnectionListener:callback:))

- (void)startScan:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    [[CRPRingBridge shared] startScan:^(NSDictionary *e) {
        if (callback) callback(e, YES);   // 流式：scanning / scanComplete 多次回调
    }];
    if (callback) callback(@{@"success": @YES, @"event": @"scanStarted"}, YES);
}

- (void)stopScan:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    [[CRPRingBridge shared] stopScan];
    if (callback) callback(@{@"success": @YES, @"msg": @"已停止扫描"}, NO);
}

- (void)connect:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    NSString *address = CRPStr(options, @"address");
    if (!address.length) { if (callback) callback(@{@"success": @NO, @"msg": @"缺少 address 参数"}, NO); return; }
    [[CRPRingBridge shared] connectAddress:address reply:^(NSDictionary *r) {
        if (callback) callback(r, NO);
    }];
}

- (void)disconnect:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    [[CRPRingBridge shared] disconnect:^(NSDictionary *r) { if (callback) callback(r, NO); }];
}

- (void)isConnected:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    if (callback) callback(@{@"success": @YES, @"data": @([CRPRingBridge shared].connected)}, NO);
}

- (void)setConnectionListener:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    [[CRPRingBridge shared] setConnectionListener:^(NSDictionary *e) {
        if (callback) callback(e, YES);
    }];
}

#pragma mark - 电量 / 固件

UNI_EXPORT_METHOD(@selector(queryBattery:callback:))
UNI_EXPORT_METHOD(@selector(listenBattery:callback:))
UNI_EXPORT_METHOD(@selector(queryFirmwareVersion:callback:))
UNI_EXPORT_METHOD(@selector(listenWearState:callback:))

- (void)queryBattery:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    [[CRPRingBridge shared] queryBattery:^(NSDictionary *r) { if (callback) callback(r, NO); }];
}

- (void)listenBattery:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    // iOS 侧电量只能查询，没有持续推送；先回一次当前值并保持通道
    [[CRPRingBridge shared] queryBattery:^(NSDictionary *r) { if (callback) callback(r, YES); }];
}

- (void)queryFirmwareVersion:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    [[CRPRingBridge shared] queryFirmwareVersion:^(NSDictionary *r) { if (callback) callback(r, NO); }];
}

- (void)listenWearState:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    [[CRPRingBridge shared] listenWearState:^(NSDictionary *e) { if (callback) callback(e, YES); }];
}

#pragma mark - 测量

UNI_EXPORT_METHOD(@selector(startMeasure:callback:))
UNI_EXPORT_METHOD(@selector(stopMeasure:callback:))

- (void)startMeasure:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    NSString *type = CRPStr(options, @"type") ?: @"heart";
    [[CRPRingBridge shared] startMeasureType:type onEvent:^(NSDictionary *e) {
        if (callback) callback(e, YES);   // 测量结果是流式推送
    }];
}

- (void)stopMeasure:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    NSString *type = CRPStr(options, @"type") ?: @"heart";
    [[CRPRingBridge shared] stopMeasureType:type reply:^(NSDictionary *r) { if (callback) callback(r, NO); }];
}

#pragma mark - 步数 / 睡眠

UNI_EXPORT_METHOD(@selector(queryCurrentSteps:callback:))
UNI_EXPORT_METHOD(@selector(queryHistorySteps:callback:))
UNI_EXPORT_METHOD(@selector(queryHistoryStepsDetails:callback:))
UNI_EXPORT_METHOD(@selector(listenSteps:callback:))
UNI_EXPORT_METHOD(@selector(queryHistorySleep:callback:))
UNI_EXPORT_METHOD(@selector(queryHistorySleepList:callback:))
UNI_EXPORT_METHOD(@selector(querySleepDetails:callback:))
UNI_EXPORT_METHOD(@selector(listenSleep:callback:))

- (void)queryCurrentSteps:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    [[CRPRingBridge shared] queryCurrentSteps:^(NSDictionary *r) { if (callback) callback(r, NO); }];
}
- (void)queryHistorySteps:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    [[CRPRingBridge shared] queryHistoryStepsDaysAgo:CRPInt(options, @"daysAgo", 0)
                                               reply:^(NSDictionary *r) { if (callback) callback(r, NO); }];
}
- (void)queryHistoryStepsDetails:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    [[CRPRingBridge shared] queryHistoryStepsDetailsDaysAgo:CRPInt(options, @"daysAgo", 0)
                                                     reply:^(NSDictionary *r) { if (callback) callback(r, NO); }];
}
- (void)listenSteps:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    // iOS 步数为 receiveSteps 推送；JS 侧 getLastData 会走 queryCurrentSteps，这里保持空实现
    if (callback) callback(@{@"success": @YES, @"event": @"listenStarted"}, YES);
}
- (void)queryHistorySleep:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    [[CRPRingBridge shared] queryHistorySleepDaysAgo:CRPInt(options, @"daysAgo", 0)
                                               reply:^(NSDictionary *r) { if (callback) callback(r, NO); }];
}
- (void)queryHistorySleepList:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    [[CRPRingBridge shared] queryHistorySleepList:^(NSDictionary *r) { if (callback) callback(r, NO); }];
}
- (void)querySleepDetails:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    [[CRPRingBridge shared] querySleepDetailsId:@(CRPInt(options, @"id", 0))
                                          reply:^(NSDictionary *r) { if (callback) callback(r, NO); }];
}
- (void)listenSleep:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    if (callback) callback(@{@"success": @YES, @"event": @"listenStarted"}, YES);
}

#pragma mark - 历史（自动测量的结果回读）

UNI_EXPORT_METHOD(@selector(queryHistoryHeartRate:callback:))
UNI_EXPORT_METHOD(@selector(queryHistoryBloodOxygen:callback:))
UNI_EXPORT_METHOD(@selector(queryHistoryHrv:callback:))
UNI_EXPORT_METHOD(@selector(queryHistoryStress:callback:))

- (void)queryHistoryHeartRate:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    [[CRPRingBridge shared] queryHistoryHeartRate:^(NSDictionary *r) { if (callback) callback(r, NO); }];
}
- (void)queryHistoryBloodOxygen:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    [[CRPRingBridge shared] queryHistoryBloodOxygen:^(NSDictionary *r) { if (callback) callback(r, NO); }];
}
- (void)queryHistoryHrv:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    [[CRPRingBridge shared] queryHistoryHrv:^(NSDictionary *r) { if (callback) callback(r, NO); }];
}
- (void)queryHistoryStress:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    [[CRPRingBridge shared] queryHistoryStress:^(NSDictionary *r) { if (callback) callback(r, NO); }];
}

#pragma mark - 定时测量（自动测量）历史

UNI_EXPORT_METHOD(@selector(queryHistoryTimingHeartRate:callback:))
UNI_EXPORT_METHOD(@selector(queryHistoryTimingBloodOxygen:callback:))
UNI_EXPORT_METHOD(@selector(queryHistoryTimingHrv:callback:))

- (void)queryHistoryTimingHeartRate:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    [[CRPRingBridge shared] queryHistoryTimingHeartRateDaysAgo:CRPInt(options, @"daysAgo", 0)
                                                        reply:^(NSDictionary *r) { if (callback) callback(r, NO); }];
}
- (void)queryHistoryTimingBloodOxygen:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    [[CRPRingBridge shared] queryHistoryTimingBloodOxygenDaysAgo:CRPInt(options, @"daysAgo", 0)
                                                          reply:^(NSDictionary *r) { if (callback) callback(r, NO); }];
}
- (void)queryHistoryTimingHrv:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    [[CRPRingBridge shared] queryHistoryTimingHrvDaysAgo:CRPInt(options, @"daysAgo", 0)
                                                  reply:^(NSDictionary *r) { if (callback) callback(r, NO); }];
}

#pragma mark - 自动测量（timing）

UNI_EXPORT_METHOD(@selector(enableTimingHeartRate:callback:))
UNI_EXPORT_METHOD(@selector(disableTimingHeartRate:callback:))
UNI_EXPORT_METHOD(@selector(queryTimingHeartRateState:callback:))
UNI_EXPORT_METHOD(@selector(enableTimingBloodOxygen:callback:))
UNI_EXPORT_METHOD(@selector(enableTimingHrv:callback:))
UNI_EXPORT_METHOD(@selector(enableTimingTemp:callback:))

- (void)enableTimingHeartRate:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    [[CRPRingBridge shared] enableTimingHeartRate:CRPInt(options, @"minutes", 30)
                                            reply:^(NSDictionary *r) { if (callback) callback(r, NO); }];
}
- (void)disableTimingHeartRate:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    [[CRPRingBridge shared] disableTimingHeartRate:^(NSDictionary *r) { if (callback) callback(r, NO); }];
}
- (void)queryTimingHeartRateState:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    [[CRPRingBridge shared] queryTimingHeartRateState:^(NSDictionary *r) { if (callback) callback(r, NO); }];
}
- (void)enableTimingBloodOxygen:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    [[CRPRingBridge shared] enableTimingBloodOxygen:CRPInt(options, @"interval", 30)
                                              reply:^(NSDictionary *r) { if (callback) callback(r, NO); }];
}
- (void)enableTimingHrv:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    [[CRPRingBridge shared] enableTimingHrv:CRPInt(options, @"interval", 30)
                                      reply:^(NSDictionary *r) { if (callback) callback(r, NO); }];
}
- (void)enableTimingTemp:(NSDictionary *)options callback:(UniModuleKeepAliveCallback)callback {
    // 戒指3 能力位 temperature=false，如实回不支持
    if (callback) callback(@{@"success": @NO, @"msg": @"当前戒指不支持定时体温"}, NO);
}

@end
