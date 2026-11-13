//
//  CRPRingBridge.m
//  CRP-BleModule (iOS)
//
//  ⚠️ 未在 Xcode 编译验证（本机 Windows 无 Xcode）。首次在 Mac 上编译时按报错校正，
//     每个待校正点都标了 `// TODO-MAC:`。
//
//  设计要点（与 Android 侧保持同一套 JS 契约）：
//    · 查询类接口：优先用 iOS SDK 的 **回调式 API**（getHeartRecordData / getO2RecordData /
//      getStepArchiveData / getBattery / getSoftver ...）直接 reply；
//    · 没有回调式 API 的（如实时步数 getSteps）：用「触发读取 + 等 delegate receiveXxx + 超时」，
//      和 Android 插件里 queryCurrentSteps 的做法完全一致；
//    · 推送类（实时测量、连接状态、佩戴状态）：转成 event 回调，由 JS 的 stream 消费。
//

#import "CRPRingBridge.h"
#import <objc/message.h>

// SDK 头：framework 的 umbrella header 由 modulemap 暴露；Swift 类通过 -Swift.h 可见
@import CRPSmartRing;
// TODO-MAC: 若 @import 不可用，改成 #import <CRPSmartRing/CRPSmartRing.h>

static NSString * const kErrNoSdk   = @"未加载 CRPSmartRing.framework（iOS 包未打进 SDK）";
static NSString * const kErrNoConn  = @"戒指未连接";
static NSString * const kErrTimeout = @"设备未回调（超时）";

@implementation CRPRingBridge {
    // 连接状态监听
    CRPBridgeEvent _connCb;
    BOOL _connectedFlag;
    // 扫描
    NSMutableArray *_scanList;
    BOOL _scanning;
    CRPBridgeEvent _scanCb;
    // 一次性等待（getSteps / 实时值这类没有回调式 API 的）
    CRPBridgeReply _pendingSteps;
    // 推送转发回调
    CRPBridgeEvent _measureCb;
    CRPBridgeEvent _wearCb;
    CRPBridgeEvent _sleepCb;
    NSInteger _wearState;
    // 最近一次推送缓存
    NSInteger _lastSteps, _lastDistance, _lastCalory;
    NSInteger _lastHr, _lastSpo2, _lastHrv, _lastStress;
}

+ (instancetype)shared {
    static CRPRingBridge *inst;
    static dispatch_once_t once;
    dispatch_once(&once, ^{ inst = [CRPRingBridge new]; });
    return inst;
}

- (instancetype)init {
    if (self = [super init]) {
        _scanList = [NSMutableArray array];
        Class sdk = NSClassFromString(@"CRPSmartRing.CRPSmartRingSDK");
        if (sdk) {
            // SDK 存在：把自己挂成 delegate，这样推送的数据才收得到
            // TODO-MAC: 若 CRPSmartRingSDK 在 ObjC 里类名不同，按 -Swift.h 里的 @interface 名字改
            [[self sdkInstance] setValue:self forKey:@"delegate"];
        }
    }
    return self;
}

- (id)sdkInstance {
    Class cls = NSClassFromString(@"CRPSmartRing.CRPSmartRingSDK");
    if (!cls) return nil;
    return [cls performSelector:@selector(sharedInstance)];
}

- (BOOL)sdkAvailable { return [self sdkInstance] != nil; }
- (BOOL)connected { return _connectedFlag; }

- (NSArray *)discoveries { return [_scanList copy]; }

// ============================ 扫描 / 连接 ============================

- (void)startScan:(CRPBridgeEvent)onEvent {
    _scanCb = [onEvent copy];
    [_scanList removeAllObjects];
    id sdk = [self sdkInstance];
    if (!sdk) { [self emitEvent:_scanCb name:@"scanError" data:@{@"message": kErrNoSdk}]; return; }
    _scanning = YES;
    // scan:duration progressHandler:completionHandler:  (CRPSmartRing-Swift.h:2110)
    [sdk scan:10 progressHandler:^(NSArray<CRPDiscovery *> *list) {
        for (CRPDiscovery *d in list) {
            NSString *mac = d.mac ?: @"";
            if (!mac.length) continue;
            BOOL dup = NO;
            for (NSDictionary *item in self->_scanList) {
                if ([item[@"mac"] isEqualToString:mac]) { dup = YES; break; }
            }
            if (dup) continue;
            [self->_scanList addObject:@{@"mac": mac, @"name": (d.localName ?: @""), @"rssi": @(d.RSSI)}];
        }
        // 把增量推给 JS（与 Android 的 onScanning 事件对齐）
        [self emitEvent:self->_scanCb name:@"scanning" data:@{@"count": @(self->_scanList.count), @"devices": [self->_scanList copy]}];
    } completionHandler:^(NSArray<CRPDiscovery *> * _Nullable tail, enum CRPError err) {
        // SDK 的 completionHandler 有两个参数：(NSArray<CRPDiscovery*>*, CRPError)
        self->_scanning = NO;
        [self emitEvent:self->_scanCb name:@"scanComplete" data:@{
            @"count": @(self->_scanList.count),
            @"devices": [self->_scanList copy],
            @"error": @((NSInteger)err)
        }];
    }];
}

- (void)stopScan {
    id sdk = [self sdkInstance];
    if (sdk) { [sdk interruptScan]; }   // (2111)
    _scanning = NO;
}

- (void)connectAddress:(NSString *)address reply:(CRPBridgeReply)reply {
    id sdk = [self sdkInstance];
    if (!sdk) { reply([self fail:kErrNoSdk]); return; }
    CRPDiscovery *target = nil;
    for (CRPDiscovery *d in [_scanList copy]) {
        if ([[d.mac uppercaseString] isEqualToString:[address uppercaseString]]) { target = d; break; }
    }
    // 没扫描过就直接连：iOS 必须先 scan 拿到 CRPDiscovery（与 Android 按 MAC 直连不同）
    if (!target) { reply(@{@"success": @NO, @"msg": @"请先扫描到该设备再连接（iOS 需要 CRPDiscovery 对象）"}); return; }
    // connet:(CRPDiscovery*)  (2112，注意 SDK 方法名拼写就是 connet)
    [sdk connet:target];
    // 连接结果通过 delegate didState: 回来；这里给个 20s 超时兜底
    [self replyAfterTimeout:reply seconds:20 message:@"连接超时（20 秒内未收到已连接状态）"];
}

- (void)disconnect:(CRPBridgeReply)reply {
    id sdk = [self sdkInstance];
    if (sdk) { [sdk remove:^(enum CRPState state, enum CRPError err) {}]; }   // (2114)
    _connectedFlag = NO;
    reply([self ok:@{}]);
}

- (void)setConnectionListener:(CRPBridgeEvent)onEvent { _connCb = [onEvent copy]; }

// ============================ 电量 / 固件 ============================

- (void)queryBattery:(CRPBridgeReply)reply {
    id sdk = [self sdkInstance];
    if (!sdk) { reply([self fail:kErrNoSdk]); return; }
    // getBattery:(void (^)(NSInteger, CRPError))handler  (2116)
    [sdk getBattery:^(NSInteger value, enum CRPError err) {
        if (err != 0) { reply([self fail:[NSString stringWithFormat:@"读取电量失败%ld", (long)err]]); return; }
        reply([self ok:@{@"data": @(value)}]);
    }];
}

- (void)queryFirmwareVersion:(CRPBridgeReply)reply {
    id sdk = [self sdkInstance];
    if (!sdk) { reply([self fail:kErrNoSdk]); return; }
    // getSoftver:(void (^)(NSString*, CRPError))handler  (2117)
    [sdk getSoftver:^(NSString *ver, enum CRPError err) {
        if (err != 0) { reply([self fail:@"读取固件版本失败"]); return; }
        reply([self ok:@{@"data": ver ?: @""}]);
    }];
}

// ============================ 步数 ============================

- (void)queryCurrentSteps:(CRPBridgeReply)reply {
    id sdk = [self sdkInstance];
    if (!sdk) { reply([self fail:kErrNoSdk]); return; }
    // 没有"查询当日步数"的回调式 API：getSteps 之后等 receiveSteps: 推送（同 Android 做法）
    _pendingSteps = [reply copy];
    [sdk getSteps];   // (2115)
    [self replyPending:^{
        if (!self->_pendingSteps) return;
        CRPBridgeReply r = self->_pendingSteps; self->_pendingSteps = nil;
        r([self fail:kErrTimeout]);
    } seconds:8];
}

- (void)queryHistoryStepsDaysAgo:(NSInteger)daysAgo reply:(CRPBridgeReply)reply {
    id sdk = [self sdkInstance];
    if (!sdk) { reply([self fail:kErrNoSdk]); return; }
    // getStepArchiveData:(day)  -> CRPTimingStepsRecordModel  (2154)
    [sdk getStepArchiveData:daysAgo :^(CRPTimingStepsRecordModel *m, enum CRPError err) {
        if (err != 0 || !m) { reply([self fail:@"查询历史步数失败"]); return; }
        // TODO-MAC: 按 CRPTimingStepsRecordModel 的真实字段取出 steps / distance / calories，
        //           并保留 Android 侧的单位口径：distance 走「米」、calories 走「千卡」。
        //           Android 实测 SDK 给的是厘米/卡，iOS 需真机各抓一次原始值再定换算。
        NSDictionary *data = @{
            @"daysAgo": @(daysAgo),
            @"steps": @(0),      // TODO-MAC: m.steps
            @"distance": @(0),   // TODO-MAC: m.distance（厘米? 米? 真机确认）
            @"calories": @(0)    // TODO-MAC: m.calory
        };
        reply([self ok:@{@"data": data}]);
    }];
}

- (void)queryHistoryStepsDetailsDaysAgo:(NSInteger)daysAgo reply:(CRPBridgeReply)reply {
    // iOS 侧没有"分桶明细"，用 getStepArchiveData 的当日汇总代替；
    // JS 的 getLastData 已按「历史步数优先、明细求和兜底」实现，这里返回空数组即可。
    reply([self ok:@{@"data": @{@"daysAgo": @(daysAgo), @"steps": @[], @"total": @0}}]);
}

// ============================ 睡眠 ============================

- (void)queryHistorySleepDaysAgo:(NSInteger)daysAgo reply:(CRPBridgeReply)reply {
    id sdk = [self sdkInstance];
    if (!sdk) { reply([self fail:kErrNoSdk]); return; }
    // getSleepData:(day) -> CRPSleepRecordModel  (2150)
    [sdk getSleepData:daysAgo :^(CRPSleepRecordModel *m, enum CRPError err) {
        if (err != 0 || !m) { reply([self fail:@"查询睡眠失败"]); return; }
        // TODO-MAC: 按 CRPSleepRecordModel 字段映射成 Android 侧 sleepJson 的形状
        //           { totalTime, deepTime, lightTime, awakeTime, remTime, details[] }
        reply([self ok:@{@"data": @{}}]);
    }];
}

- (void)queryHistorySleepList:(CRPBridgeReply)reply {
    id sdk = [self sdkInstance];
    if (!sdk) { reply([self fail:kErrNoSdk]); return; }
    [sdk readGoMoreSleepDataList];   // (1285)，结果走 delegate receiveSleepList:
    reply([self ok:@{@"data": @[]}]);
}

- (void)querySleepDetailsId:(NSNumber *)sleepId reply:(CRPBridgeReply)reply {
    id sdk = [self sdkInstance];
    if (!sdk) { reply([self fail:kErrNoSdk]); return; }
    [sdk readGoMoreSleepDataDetailWithId:[sleepId integerValue]];   // (1286)
    reply([self ok:@{@"data": @{}}]);
}

// ============================ 自动测量（timing）============================

- (void)enableTimingHeartRate:(NSInteger)minutes reply:(CRPBridgeReply)reply {
    id sdk = [self sdkInstance];
    if (!sdk) { reply([self fail:kErrNoSdk]); return; }
    [sdk setTimingHeartRate:minutes];                       // (2130)
    reply([self ok:@{@"msg": @"定时心率已开启"}]);
}
- (void)disableTimingHeartRate:(CRPBridgeReply)reply {
    [[self sdkInstance] setTimingHeartRate:0];              // 0 = 关闭
    reply([self ok:@{}]);
}
- (void)queryTimingHeartRateState:(CRPBridgeReply)reply {
    id sdk = [self sdkInstance];
    if (!sdk) { reply([self fail:kErrNoSdk]); return; }
    [sdk getTimingHeartRateInterval:^(NSInteger interval, enum CRPError err) {   // (2131)
        reply([self ok:@{@"data": @(interval)}]);
    }];
}
- (void)enableTimingBloodOxygen:(NSInteger)interval reply:(CRPBridgeReply)reply {
    [[self sdkInstance] setTimingO2:interval];              // (2134)
    reply([self ok:@{}]);
}
- (void)enableTimingHrv:(NSInteger)interval reply:(CRPBridgeReply)reply {
    [[self sdkInstance] setTimingHRV:interval];             // (2132)
    reply([self ok:@{}]);
}
- (void)enableTimingTemp:(CRPBridgeReply)reply {
    // 体温：戒指3 能力位 false，SDK 有 setTimingTemperatureWithInterval: (2178) 但真机不支持
    reply([self ok:@{@"msg": @"当前戒指不支持定时体温"}]);
}

// ============================ 历史（自动测量结果回读）============================

- (void)queryHistoryHeartRate:(CRPBridgeReply)reply {
    id sdk = [self sdkInstance];
    if (!sdk) { reply([self fail:kErrNoSdk]); return; }
    [sdk getHeartRecordData:^(NSArray<CRPHeartRecordModel *> *list, enum CRPError err) {   // (2138)
        NSMutableArray *arr = [NSMutableArray array];
        for (CRPHeartRecordModel *m in list) {
            // TODO-MAC: 字段名按 CRPSmartRing-Swift.h 校正
            [arr addObject:@{@"hr": @0, @"time": @0}];
        }
        reply([self ok:@{@"data": arr}]);
    }];
}

- (void)queryHistoryBloodOxygen:(CRPBridgeReply)reply {
    id sdk = [self sdkInstance];
    if (!sdk) { reply([self fail:kErrNoSdk]); return; }
    [sdk getO2RecordData:^(NSArray<CRPO2RecordModel *> *list, enum CRPError err) {       // (2147)
        NSMutableArray *arr = [NSMutableArray array];
        for (CRPO2RecordModel *m in list) { [arr addObject:@{@"bo": @0, @"time": @0}]; }
        reply([self ok:@{@"data": arr}]);
    }];
}

- (void)queryHistoryHrv:(CRPBridgeReply)reply {
    id sdk = [self sdkInstance];
    if (!sdk) { reply([self fail:kErrNoSdk]); return; }
    [sdk getHRVRecord:^(NSArray<CRPHRVRecordModel *> *list, enum CRPError err) {         // (2143)
        NSMutableArray *arr = [NSMutableArray array];
        for (CRPHRVRecordModel *m in list) { [arr addObject:@{@"hrv": @0, @"time": @0}]; }
        reply([self ok:@{@"data": arr}]);
    }];
}

- (void)queryHistoryStress:(CRPBridgeReply)reply {
    id sdk = [self sdkInstance];
    if (!sdk) { reply([self fail:kErrNoSdk]); return; }
    [sdk getStressRecord:^(NSArray<CRPStressRecordModel *> *list, enum CRPError err) {   // (2144)
        NSMutableArray *arr = [NSMutableArray array];
        for (CRPStressRecordModel *m in list) { [arr addObject:@{@"stress": @0, @"time": @0}]; }
        reply([self ok:@{@"data": arr}]);
    }];
}

// ============================ 实时测量 ============================

- (void)startMeasureType:(NSString *)type onEvent:(CRPBridgeEvent)onEvent {
    id sdk = [self sdkInstance];
    if (!sdk) { [self emitEvent:onEvent name:@"measureError" data:@{@"message": kErrNoSdk}]; return; }
    SEL sel = NULL;
    if ([type isEqualToString:@"heart"])       sel = @selector(setStartSingleHR);   // 2136
    else if ([type isEqualToString:@"hrv"])    sel = @selector(setStartHRV);        // 2139
    else if ([type isEqualToString:@"stress"]) sel = @selector(setStartStress);     // 2141
    else if ([type isEqualToString:@"spo2"])   sel = @selector(setStartSpO2);       // 2145
    if (sel && [sdk respondsToSelector:sel]) {
        ((void (*)(id, SEL))objc_msgSend)(sdk, sel);
    }
    // 结果通过 delegate 的 receiveHeartRate/receiveRealTimeHeartRate/receiveHRV/... 回来
    [self emitEvent:onEvent name:@"measureStarted" data:@{@"type": type}];
}

- (void)stopMeasureType:(NSString *)type reply:(CRPBridgeReply)reply {
    id sdk = [self sdkInstance];
    SEL sel = NULL;
    if ([type isEqualToString:@"heart"])       sel = @selector(setStopSingleHR);
    else if ([type isEqualToString:@"hrv"])    sel = @selector(setStopHRV);
    else if ([type isEqualToString:@"stress"]) sel = @selector(setStopStress);
    else if ([type isEqualToString:@"spo2"])   sel = @selector(setStopSpO2);
    if (sdk && sel && [sdk respondsToSelector:sel]) { ((void (*)(id, SEL))objc_msgSend)(sdk, sel); }
    reply([self ok:@{}]);
}

// ============================ 佩戴状态 ============================

- (void)listenWearState:(CRPBridgeEvent)onEvent {
    // receiveWearState: 推送 (2087)
    _wearCb = [onEvent copy];
}

// ============================ delegate（CRPManagerDelegate，全部 @optional）============================

- (void)didState:(enum CRPState)state {
    // iOS CRPState: 需按头文件的枚举取值判断"已连接"
    // TODO-MAC: 用 CRPState 枚举的真实取值替换 2
    BOOL nowConnected = ((NSInteger)state == (NSInteger)CRPStateConnected);
    if (nowConnected == _connectedFlag) return;
    _connectedFlag = nowConnected;
    [self emitEvent:_connCb name:@"connectionState" data:@{@"state": nowConnected ? @2 : @0}];
}

- (void)receiveSteps:(CRPStepModel *)model {
    _lastSteps = model.steps;
    _lastDistance = model.distance;
    _lastCalory = model.calory;
    if (_pendingSteps) {
        CRPBridgeReply r = _pendingSteps; _pendingSteps = nil;
        // 与 Android stepsJson 同形；单位口径待真机确认（Android 是厘米/卡）
        r([self ok:@{@"data": @{
            @"steps": @(_lastSteps),
            @"distance": @(_lastDistance),
            @"calories": @(_lastCalory),
            @"distanceRaw": @(_lastDistance),
            @"caloriesRaw": @(_lastCalory)
        }}]);
    }
}

- (void)receiveHeartRate:(NSInteger)heartRate {
    _lastHr = heartRate;
    [self emitEvent:_measureCb name:@"measureResult" data:@{@"type": @"heart", @"status": @0, @"data": @(heartRate)}];
}
- (void)receiveRealTimeHeartRate:(NSInteger)heartRate {
    _lastHr = heartRate;
    [self emitEvent:_measureCb name:@"realtimeHeart" data:@{@"heartRate": @(heartRate)}];
}
- (void)receiveSpO2:(NSInteger)o2 {
    _lastSpo2 = o2;
    [self emitEvent:_measureCb name:@"measureResult" data:@{@"type": @"spo2", @"status": @0, @"data": @(o2)}];
}
- (void)receiveHRV:(NSInteger)hrv {
    _lastHrv = hrv;
    [self emitEvent:_measureCb name:@"measureResult" data:@{@"type": @"hrv", @"status": @0, @"data": @(hrv)}];
}
- (void)receiveStress:(NSInteger)stress {
    _lastStress = stress;
    [self emitEvent:_measureCb name:@"measureResult" data:@{@"type": @"stress", @"status": @0, @"data": @(stress)}];
}
- (void)receiveWearState:(NSInteger)state {
    _wearState = state;
    [self emitEvent:_wearCb name:@"wearState" data:@{@"wearState": @(state)}];
}
- (void)receiveSleepList:(NSArray<CRPGoMoreSleepRecord *> *)list {
    // TODO-MAC: 按 CRPGoMoreSleepRecord 字段映射成 Android 的 sleepJson 形状
    [self emitEvent:_sleepCb name:@"sleepList" data:@{@"count": @(list.count)}];
}

// ============================ 工具方法 ============================

- (NSDictionary *)ok:(NSDictionary *)data {
    NSMutableDictionary *d = [@{@"success": @YES, @"msg": @""} mutableCopy];
    [d addEntriesFromDictionary:data ?: @{}];
    return d;
}
- (NSDictionary *)fail:(NSString *)msg { return @{@"success": @NO, @"msg": msg ?: @"失败"}; }

- (void)emitEvent:(CRPBridgeEvent)cb name:(NSString *)name data:(NSDictionary *)data {
    if (!cb) return;
    NSMutableDictionary *d = [@{@"success": @YES, @"event": name} mutableCopy];
    if (data) [d addEntriesFromDictionary:data];
    dispatch_async(dispatch_get_main_queue(), ^{ cb(d); });
}

/// 给"没有回调的推送式操作"加超时兜底
- (void)replyPending:(void (^)(void))onTimeout seconds:(NSTimeInterval)sec {
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(sec * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
        onTimeout();
    });
}

- (void)replyAfterTimeout:(CRPBridgeReply)reply seconds:(NSTimeInterval)sec message:(NSString *)msg {
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(sec * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
        if (self->_connectedFlag) { reply([self ok:@{}]); }
        else { reply([self fail:msg]); }
    });
}

@end
