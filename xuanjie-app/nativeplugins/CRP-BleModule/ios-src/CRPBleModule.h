//
//  CRPBleModule.h
//  CRP-BleModule (iOS) — uni-app 原生插件入口
//
//  与 package.json 里声明的 iOS 插件 class 对应：
//      "ios": { "plugins": [ { "type": "module", "name": "CRP-BleModule", "class": "CRPBleModule" } ] }
//
//  ⚠️ 未在 Xcode 编译验证（Windows 无 Xcode）。首次在 Mac 上编译时按报错校正，
//     每个待校正点标了 `// TODO-MAC:`。
//
//  方法名必须与 Android 侧（xuanjie-native-app/.../CRPBleModule.java）保持一致，
//  这样 JS 门面（xuanjie-app/utils/ring_channels/crp.js）在 Android / iOS 上共用同一套调用。
//

#import <Foundation/Foundation.h>
#import <DCUniBase/DCUniModule.h>

@interface CRPBleModule : DCUniModule

@end
