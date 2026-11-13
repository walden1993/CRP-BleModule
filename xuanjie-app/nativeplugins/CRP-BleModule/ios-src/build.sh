#!/usr/bin/env bash
# 在 Mac / 云 Mac 上把 CRP-BleModule 编成 uni-app 可用的静态库。
#
# 用法：cd xuanjie-app/nativeplugins/CRP-BleModule/ios-src && ./build.sh
# 产物：../ios/libCRPBleModule.a  +  ../ios/include/CRPBleModule.h
#
# 说明：
#  · 真机包只要 arm64（DCloud 云打包的 iOS 只加 arm64 设备包），不需要模拟器 slice；
#  · 插件静态库里**不要**把 CRPSmartRing.framework 静态链进去 —— 它由 package.json 的
#    embedFrameworks 交给宿主工程嵌入，否则会出现符号重复。
set -euo pipefail

cd "$(dirname "$0")"

if ! command -v xcodebuild >/dev/null 2>&1; then
  echo "✗ 没有 xcodebuild：请先装 Xcode（App Store）并执行 sudo xcode-select -s /Applications/Xcode.app"
  exit 1
fi

if [ ! -d project.xcodeproj ]; then
  if ! command -v xcodegen >/dev/null 2>&1; then
    echo "✗ 没有 xcodegen：请先 brew install xcodegen"
    exit 1
  fi
  xcodegen generate
fi

OUT="../ios"
mkdir -p "$OUT/include"

xcodebuild \
  -project CRP-BleModule.xcodeproj \
  -scheme CRP-BleModule \
  -configuration Release \
  -sdk iphoneos \
  -arch arm64 \
  ONLY_ACTIVE_ARCH=NO \
  CODE_SIGNING_ALLOWED=NO \
  SKIP_INSTALL=YES \
  CONFIGURATION_BUILD_DIR="$PWD/build" \
  clean build

if [ ! -f build/libCRPBleModule.a ]; then
  echo "✗ 没生成 libCRPBleModule.a，看上面的编译错误（常见：DCUniBase 头路径、SDK 类名大小写）"
  exit 1
fi

cp -f build/libCRPBleModule.a "$OUT/libCRPBleModule.a"
cp -f CRPBleModule.h "$OUT/include/CRPBleModule.h"
echo "✓ 已产出：$OUT/libCRPBleModule.a"
echo "  下一步：在 HBuilderX 用「App云打包」时勾选本地原生插件 CRP-BleModule，或走 iOS 离线 SDK。"
