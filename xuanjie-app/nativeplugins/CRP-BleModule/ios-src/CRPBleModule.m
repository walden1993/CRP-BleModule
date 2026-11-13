//
//  CRPBleModule.m
//  CRP-BleModule (iOS) — uni-app 原生插件入口实现
//
//  职责：把 JS 门面调用的方法名（与 Android 插件一致）转发给 CRPRingBridge。
//  真正的 SDK 交互都在 CRPRingBridge 里，这一层只做「参数解析 + 回调封装」。
//
//  ⚠️ 未在 Xcode 编译验证（Windows 无 Xcode）。
//

#import "CRPBleModule.h"
#import "CRPRingBridge.h"

@implementation CRPBleModule

UNI_EXPORT_METHOD(@selector(startScan:callback:))
UNI_EXPORT_METHOD(@selector(stopScan:callback:))
UNI_EXPORT_METHOD(@selector(connect:callback:))
UNI_EXPORT_METHOD(@selector(disconnect:callback:))
UNI_EXPORT_METHOD(@selector(isConnected:callback:))
UNI_EXPORT_METHOD(@selector(queryBattery:callback:))
UNI_EXPORT_METHOD(@selector(listenBattery:callback:))
UNI_EXPORT_METHOD(@selector(setConnectionListener:callback:))
UNI_EXPORT_METHOD(@selector(startMeasure:callback:))
UNI_EXPORT_METHOD(@selector(stopMeasure:callback:))

UNI_EXPORT_METHOD(@selector(queryCurrentSteps:callback:))
UNI_EXPORT_METHOD(@selector(queryHistorySteps:callback:))
UNI_EXPORT_METHOD(@selector(queryHistoryStepsDetails:callback:))
UNI_EXPORT_METHOD(@selector(listenSteps:callback:))

UNI_EXPORT_METHOD(@selector(queryHistorySleep:callback:))
UNI_EXPORT_METHOD(@selector(queryHistorySleepList:callback:))
UNI_EXPORT_METHOD(@selector(querySleepDetails:callback:))
UNI_EXPORT_METHOD(@selector(listenSleep:callback:))

UNI_EXPORT_METHOD(@selector(queryHistoryHeartRate:callback:))
UNI_EXPORT_METHOD(@selector(queryHistoryBloodOxygen:callback:))
UNI_EXPORT_METHOD(@selector(queryHistoryHrv:callback:))
UNI_EXPORT_METHOD(@selector(queryHistoryStress:callback:))

UNI_EXPORT_METHOD(@selector(enableTimingHeartRate:callback:))
UNI_EXPORT_METHOD(@selector(disableTimingHeartRate:callback:))
UNI_EXPORT_METHOD(@selector(queryTimingHeartRateState:callback:))
UNI_EXPORT_METHOD(@selector(enableTimingBloodOxygen:callback:))
UNI_EXPORT_METHOD(@selector(enableTimingHrv:callback:))
UNI_EXPORT_METHOD(@selector(enableTimingTemp:callback:))

UNI_EXPORT_METHOD(@selector(queryFirmwareVersion:callback:))
UNI_EXPORT_METHOD(@selector(listenWearState:callback:))

#pragma mark - 工具

/// 统一出口：成功回调一次；流式事件回调用同一个 cb 连续回调
static void CRPDeliver(id<DCUniModuleCallBackProtocol> callback, NSDictionary *result, BOOL keepAlive) {
    if (!callback) return;
    // TODO-MAC: 流式回调在 uni-app iOS 里用 callback.invokeAndKeepAlive:；
    //           一次性用 callback.invoke:。若 API 名不同按 DCUniBase 头文件校正。
    if (keepAlive) { [callback invokeAndKeepAlive:result]; }
    else { [callback invoke:result]; }
}

/// 解析 {"address": "xx"} / {"type": "heart"} 这类参数
static NSString *CRPStrParam(NSDictionary *params, NSString *key) {
    id v = params[key];
    return [v isKindOfClass:[NSString class]] ? v : nil;
}
static NSInteger CRPIntParam(NSDictionary *params, NSString *key, NSInteger def) {
    id v = params[key];
    return [v respondsToSelector:@selector(integerValue)] ? [v integerValue] : def;
}

#pragma mark - 扫描 / 连接

- (void)startScan:(NSDictionary *)params callback:(id<DCUniModuleCallBackProtocol>)callback {
    [[CRPRingBridge shared] startScan:^(NSDictionary *e) { CRPDeliver(callback, e, YES); }];
    CRPDeliver(callback, @{@"success": @YES, @"event": @"scanStarted"}, YES);
}

- (void)stopScan:(NSDictionary *)params callback:(id<DCUniModuleCallBackProtocol>)callback {
    [[CRPRingBridge shared] stopScan];
    CRPDeliver(callback, @{@"success": @YES, @"msg": @"已停止扫描"}, NO);
}

- (void)connect:(NSDictionary *)params callback:(id<DCUniModuleCallBackProtocol>)callback {
    NSString *address = CRPStrParam(params, @"address");
    if (!address.length) { CRPDeliver(callback, @{@"success": @NO, @"msg": @"缺少 address 参数"}, NO); return; }
    [[CRPRingBridge shared] connectAddress:address reply:^(NSDictionary *r) { CRPDeliver(callback, r, NO); }];
}

- (void)disconnect:(NSDictionary *)params callback:(id<DCUniModuleCallBackProtocol>)callback {
    [[CRPRingBridge shared] disconnect:^(NSDictionary *r) { CRPDeliver(callback, r, NO); }];
}

- (void)isConnected:(NSDictionary *)params callback:(id<DCUniModuleCallBackProtocol>)callback {
    BOOL c = [CRPRingBridge shared].connected;
    CRPDeliver(callback, @{@"success": @YES, @"data": @(c)}, NO);
}

- (void)queryBattery:(NSDictionary *)params callback:(id<DCUniModuleCallBackProtocol>)callback {
    [[CRPRingBridge shared] queryBattery:^(NSDictionary *r) { CRPDeliver(callback, r, NO); }];
}

- (void)listenBattery:(NSDictionary *)params callback:(id<DCUniModuleCallBackProtocol>)callback {
    // iOS 侧电量走 getBattery 回调，没有持续推送；这里先回当前值，保持接口可用
    [[CRPRingBridge shared] queryBattery:^(NSDictionary *r) { CRPDeliver(callback, r, YES); }];
}

- (void)setConnectionListener:(NSDictionary *)params callback:(id<DCUniModuleCallBackProtocol>)callback {
    [[CRPRingBridge shared] setConnectionListener:^(NSDictionary *e) { CRPDeliver(callback, e, YES); }];
}

#pragma mark - 测量

- (void)startMeasure:(NSDictionary *)params callback:(id<DCUniModuleCallBackProtocol>)callback {
    NSString *type = CRPStrParam(params, @"type") ?: @"heart";
    [[CRPRingBridge shared] startMeasureType:type onEvent:^(NSDictionary *e) { CRPDeliver(callback, e, YES); }];
}

- (void)stopMeasure:(NSDictionary *)params callback:(id<DCUniModuleCallBackProtocol>)callback {
    NSString *type = CRPStrParam(params, @"type") ?: @"heart";
    [[CRPRingBridge shared] stopMeasureType:type reply:^(NSDictionary *r) { CRPDeliver(callback, r, NO); }];
}

#pragma mark - 步数 / 睡眠

- (void)queryCurrentSteps:(NSDictionary *)params callback:(id<DCUniModuleCallBackProtocol>)callback {
    [[CRPRingBridge shared] queryCurrentSteps:^(NSDictionary *r) { CRPDeliver(callback, r, NO); }];
}
- (void)queryHistorySteps:(NSDictionary *)params callback:(id<DCUniModuleCallBackProtocol>)callback {
    NSInteger daysAgo = CRPIntParam(params, @"daysAgo", 0);
    [[CRPRingBridge shared] queryHistoryStepsDaysAgo:daysAgo reply:^(NSDictionary *r) { CRPDeliver(callback, r, NO); }];
}
- (void)queryHistoryStepsDetails:(NSDictionary *)params callback:(id<DCUniModuleCallBackProtocol>)callback {
    NSInteger daysAgo = CRPIntParam(params, @"daysAgo", 0);
    [[CRPRingBridge shared] queryHistoryStepsDetailsDaysAgo:daysAgo reply:^(NSDictionary *r) { CRPDeliver(callback, r, NO); }];
}
- (void)listenSteps:(NSDictionary *)params callback:(id<DCUniModuleCallBackProtocol>)callback {
    // iOS 侧步数是 receiveSteps 推送；JS 的 getLastData 会走 queryCurrentSteps，这里保持空实现
    CRPDeliver(callback, @{@"success": @YES, @"event": @"listenStarted"}, YES);
}

- (void)queryHistorySleep:(NSDictionary *)params callback:(id<DCUniModuleCallBackProtocol>)callback {
    NSInteger daysAgo = CRPIntParam(params, @"daysAgo", 0);
    [[CRPRingBridge shared] queryHistorySleepDaysAgo:daysAgo reply:^(NSDictionary *r) { CRPDeliver(callback, r, NO); }];
}
- (void)queryHistorySleepList:(NSDictionary *)params callback:(id<DCUniModuleCallBackProtocol>)callback {
    [[CRPRingBridge shared] queryHistorySleepList:^(NSDictionary *r) { CRPDeliver(callback, r, NO); }];
}
- (void)querySleepDetails:(NSDictionary *)params callback:(id<DCUniModuleCallBackProtocol>)callback {
    NSInteger sid = CRPIntParam(params, @"id", 0);
    [[CRPRingBridge shared] querySleepDetailsId:@(sid) reply:^(NSDictionary *r) { CRPDeliver(callback, r, NO); }];
}
- (void)listenSleep:(NSDictionary *)params callback:(id<DCUniModuleCallBackProtocol>)callback {
    CRPDeliver(callback, @{@"success": @YES, @"event": @"listenStarted"}, YES);
}

#pragma mark - 历史

- (void)queryHistoryHeartRate:(NSDictionary *)params callback:(id<DCUniModuleCallBackProtocol>)callback {
    [[CRPRingBridge shared] queryHistoryHeartRate:^(NSDictionary *r) { CRPDeliver(callback, r, NO); }];
}
- (void)queryHistoryBloodOxygen:(NSDictionary *)params callback:(id<DCUniModuleCallBackProtocol>)callback {
    [[CRPRingBridge shared] queryHistoryBloodOxygen:^(NSDictionary *r) { CRPDeliver(callback, r, NO); }];
}
- (void)queryHistoryHrv:(NSDictionary *)params callback:(id<DCUniModuleCallBackProtocol>)callback {
    [[CRPRingBridge shared] queryHistoryHrv:^(NSDictionary *r) { CRPDeliver(callback, r, NO); }];
}
- (void)queryHistoryStress:(NSDictionary *)params callback:(id<DCUniModuleCallBackProtocol>)callback {
    [[CRPRingBridge shared] queryHistoryStress:^(NSDictionary *r) { CRPDeliver(callback, r, NO); }];
}

#pragma mark - 自动测量（timing）

- (void)enableTimingHeartRate:(NSDictionary *)params callback:(id<DCUniModuleCallBackProtocol>)callback {
    NSInteger minutes = CRPIntParam(params, @"minutes", 30);
    [[CRPRingBridge shared] enableTimingHeartRate:minutes reply:^(NSDictionary *r) { CRPDeliver(callback, r, NO); }];
}
- (void)disableTimingHeartRate:(NSDictionary *)params callback:(id<DCUniModuleCallBackProtocol>)callback {
    [[CRPRingBridge shared] disableTimingHeartRate:^(NSDictionary *r) { CRPDeliver(callback, r, NO); }];
}
- (void)queryTimingHeartRateState:(NSDictionary *)params callback:(id<DCUniModuleCallBackProtocol>)callback {
    [[CRPRingBridge shared] queryTimingHeartRateState:^(NSDictionary *r) { CRPDeliver(callback, r, NO); }];
}
- (void)enableTimingBloodOxygen:(NSDictionary *)params callback:(id<DCUniModuleCallBackProtocol>)callback {
    NSInteger interval = CRPIntParam(params, @"interval", 30);
    [[CRPRingBridge shared] enableTimingBloodOxygen:interval reply:^(NSDictionary *r) { CRPDeliver(callback, r, NO); }];
}
- (void)enableTimingHrv:(NSDictionary *)params callback:(id<DCUniModuleCallBackProtocol>)callback {
    NSInteger interval = CRPIntParam(params, @"interval", 30);
    [[CRPRingBridge shared] enableTimingHrv:interval reply:^(NSDictionary *r) { CRPDeliver(callback, r, NO); }];
}
- (void)enableTimingTemp:(NSDictionary *)params callback:(id<DCUniModuleCallBackProtocol>)callback {
    [[CRPRingBridge shared] enableTimingTemp:^(NSDictionary *r) { CRPDeliver(callback, r, NO); }];
}

#pragma mark - 其他

- (void)queryFirmwareVersion:(NSDictionary *)params callback:(id<DCUniModuleCallBackProtocol>)callback {
    [[CRPRingBridge shared] queryFirmwareVersion:^(NSDictionary *r) { CRPDeliver(callback, r, NO); }];
}
- (void)listenWearState:(NSDictionary *)params callback:(id<DCUniModuleCallBackProtocol>)callback {
    [[CRPRingBridge shared] listenWearState:^(NSDictionary *e) { CRPDeliver(callback, e, YES); }];
}

@end
