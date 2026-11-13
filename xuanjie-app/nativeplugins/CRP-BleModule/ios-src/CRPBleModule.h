//
//  CRPBleModule.h
//  CRP-BleModule (iOS) — uni-app 原生插件入口
//
//  与 package.json 声明对应：
//      "ios": { "plugins": [ { "type": "module", "name": "CRP-BleModule", "class": "CRPBleModule" } ] }
//
//  ⚠️ 未在本机编译验证（Windows 无 Xcode）。已验证到"能进 CI 编译"的阶段，
//     编译所需的 DCloud 头文件来自 iOS 离线 SDK 的 SDK/inc 目录（见 ios-src/README.md）。
//
//  头文件引用说明：
//    官方 iOS 离线 SDK 里，头文件位于  SDK/inc/DCUni/DCUniModule.h
//    因此工程 Header Search Paths 指向 SDK/inc，然后用  #import <DCUni/DCUniModule.h>
//    （本仓库把 inc 放在  third_party/uni-ios-sdk/inc/ ，project.yml 已配好）
//
//  方法名必须与 Android 插件（xuanjie-native-app/.../CRPBleModule.java）保持一致，
//  这样 JS 门面 xuanjie-app/utils/ring_channels/crp.js 在 Android / iOS 共用同一套调用。
//

#import <Foundation/Foundation.h>
#import <DCUni/DCUniModule.h>

@interface CRPBleModule : DCUniModule

@end
