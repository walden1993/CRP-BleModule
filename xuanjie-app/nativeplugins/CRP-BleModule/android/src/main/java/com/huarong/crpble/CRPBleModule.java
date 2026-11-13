package com.huarong.crpble;

import android.content.Context;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.crrepa.ble.CRPBleClient;
import com.crrepa.ble.conn.CRPBleConnection;
import com.crrepa.ble.conn.CRPBleDevice;
import com.crrepa.ble.conn.bean.CRPHeartRateInfo;
import com.crrepa.ble.conn.bean.CRPHistoryBloodOxygenInfo;
import com.crrepa.ble.conn.bean.CRPHistoryHeartRateInfo;
import com.crrepa.ble.conn.bean.CRPHistoryHrvInfo;
import com.crrepa.ble.conn.bean.CRPHistoryStressInfo;
import com.crrepa.ble.conn.bean.CRPHistoryTempInfo;
import com.crrepa.ble.conn.bean.CRPSleepChronotypeInfo;
import com.crrepa.ble.conn.bean.CRPSleepDetailsInfo;
import com.crrepa.ble.conn.bean.CRPSleepInfo;
import com.crrepa.ble.conn.bean.CRPStepsDetailsInfo;
import com.crrepa.ble.conn.bean.CRPStepsInfo;
import com.crrepa.ble.conn.bean.CRPTimingBloodOxygenInfo;
import com.crrepa.ble.conn.bean.CRPTimingHrvInfo;
import com.crrepa.ble.conn.callback.CRPFirmwareVersionCallback;
import com.crrepa.ble.conn.listener.CRPBatteryListener;
import com.crrepa.ble.conn.listener.CRPBleConnectionStateListener;
import com.crrepa.ble.conn.listener.CRPBloodOxygenChangeListener;
import com.crrepa.ble.conn.listener.CRPHeartRateChangeListener;
import com.crrepa.ble.conn.callback.CRPHardwareCheckCallback;
import com.crrepa.ble.conn.callback.CRPShutDownCallback;
import com.crrepa.ble.conn.listener.CRPBloodPressureChangeListener;
import com.crrepa.ble.conn.listener.CRPHrvChangeListener;
import com.crrepa.ble.conn.listener.CRPSleepChangeListener;
import com.crrepa.ble.conn.listener.CRPStepsChangeListener;
import com.crrepa.ble.conn.listener.CRPStressChangeListener;
import com.crrepa.ble.conn.listener.CRPTempChangeListener;
import com.crrepa.ble.conn.listener.CRPWearStateChangeListener;
import com.crrepa.ble.conn.type.CRPHistoryDay;
import com.crrepa.ble.scan.bean.CRPScanDevice;
import com.crrepa.ble.scan.callback.CRPScanCallback;
import com.crrepa.ble.scan.CRPScanRecordParser;
import com.crrepa.ble.scan.bean.CRPScanRecordInfo;

import java.util.List;

import io.dcloud.feature.uniapp.annotation.UniJSMethod;
import io.dcloud.feature.uniapp.bridge.UniJSCallback;
import io.dcloud.feature.uniapp.common.UniModule;

/**
 * 戒指3（开发商3 / MYRingSDK · com.crrepa.ble）的 uni 原生插件模块。
 *
 * 为什么写在**离线包**里而不是只用 DCloud 插件目录：
 *   基座（HBuilder-Integrate-AS）是本项目实际的打包路径，它用
 *   `fileTree("libs")` 把 `simpleDemo/libs/*.aar` 一起构建，并在
 *   `assets/dcloud_uniplugins.json` 里按 {name, class} 登记本地模块 ——
 *   现有 EcgModule / ReminderModule / FindDeviceModule 就是这么加进来的。
 *   本类与它们同构；`xuanjie-app/nativeplugins/CRP-BleModule/` 保留给云打包/自定义基座路径。
 *
 * 回调约定（与仓库现有模块保持一致，JS 侧不要按 HTTP 那套 code 判）：
 *   · 一次性：callback.invoke({ success: true|false, msg, data? })
 *   · 流式（扫描/连接状态/实时测量）：callback.invokeAndKeepAlive({...}) 多次回调
 *
 * 方法名与签名全部取自 AAR 的真实 javap 结果（不是文档转述）：
 *   CRPBleClient.create(Context) / scanDevice(CRPScanCallback, long) / cancelScan() /
 *   getBleDevice(String) → CRPBleDevice.connect() → CRPBleConnection（接口）/
 *   setConnectionStateListener / setBatteryListener + queryBattery /
 *   setHeartRateChangeListener（5 个方法）+ start/stopMeasureHeartRate /
 *   queryHistoryHeartRate / enable|disable|queryTimingHeartRateState / queryFirmwareVersion /
 *   setWearStateChangeListener
 */
public class CRPBleModule extends UniModule {
    /**
     * 类加载探针（2026-11-03 真机）：只要 DCloud 解析并实例化本类，logcat 就会出现这一行。
     * 有这行 = 类可加载、插件已注册，问题在 JS / 方法调用；没这行 = DCloud 没解析到该模块。
     */
    static {
        android.util.Log.i("CRP", "plugin class loaded: " + CRPBleModule.class.getName());
    }

    /**
     * Application 级 SDK 客户端（对齐官方 demo：SampleApplication.onCreate 里 CRPBleClient.create(this)）。
     * 2026-11-03 对照 MYRingSDK-1.1.0/RingSample 发现：之前是**懒创建**且可能用 Activity context，
     * 与 demo 的 Application 级单例不一致 —— 现场表现是扫描/连接时好时坏。
     */
    private static CRPBleClient appBleClient = null;

    /** 供 MyApplication.onCreate 调用（在 super.onCreate 之后、与 demo 一致） */
    public static void initWithApplication(android.content.Context appCtx) {
        try {
            if (appBleClient == null && appCtx != null) {
                appBleClient = CRPBleClient.create(appCtx.getApplicationContext());
                android.util.Log.i("CRP", "CRPBleClient created @Application: " + (appBleClient != null));
            }
        } catch (Throwable t) {
            android.util.Log.e("CRP", "initWithApplication 失败: " + t);
        }
    }


    /*
     *   流式方法（扫描/连接状态/实时测量/监听类）的**首包也必须用 invokeAndKeepAlive**（push）。
     *   同一个 callback 上先 invoke() 再 invokeAndKeepAlive() —— invoke 会把回调"消费掉"，
     *   后续事件全部到不了 JS（表现为 Java 日志有数据、JS 列表却是空的）。
     *   只有**失败终止**分支才用 invoke()（reply）。
     */

    private static final String TAG = "[CRP-BleModule]";

    private CRPBleClient bleClient;
    private CRPBleConnection bleConnection;
    private CRPBleDevice bleDevice;
    /** BATTERY_CACHE：最近一次读到的电量（%）。CRP 戒指回电量可能要 6s+，缓存后秒回，避免每次干等 */
    private volatile int lastBatteryPct = -1;

    /**
     * 把 SDK 回调的原始电量归一成 0~100 的百分比。
     * 2026-11-11 真机：onBattery 回的是 **115**（超出 0~100），而 demoSDK 文档没写量纲。
     * 常见两种可能：① 十分位（11.5%）；② 充电时 >100。
     * 处理：>100 时按十分位换算（115 -> 12），并**打印原始值**，后续拿到厂商说明再校准。
     */
    private int normalizeBattery(int raw) {
        if (raw < 0) return 0;
        if (raw <= 100) return raw;
        int pct = Math.round(raw / 10f);
        android.util.Log.w("CRP", "onBattery 原始值 " + raw + " 超出 0~100，按十分位换算为 " + pct + "%");
        return Math.min(100, pct);
    }
    /** 连接期监听收到的值缓存（连接期没有 JS callback，先存这里，startBloodPressure 时补发） */
    private final JSONObject lastVital = new JSONObject();

    // ---------------- 工具 ----------------

    private Context appContext() {
        try {
            Context c = mUniSDKInstance.getContext();
            return c == null ? null : c.getApplicationContext();
        } catch (Throwable t) {
            return null;
        }
    }

    private static JSONObject ok(String msg) {
        JSONObject o = new JSONObject();
        o.put("success", true);
        o.put("msg", msg == null ? "" : msg);
        return o;
    }

    private static JSONObject fail(String msg) {
        JSONObject o = new JSONObject();
        o.put("success", false);
        o.put("msg", msg == null ? "调用失败" : msg);
        return o;
    }

    private static void reply(UniJSCallback cb, JSONObject payload) {
        if (cb != null) {
            cb.invoke(payload);
        }
    }

    private static void push(UniJSCallback cb, JSONObject payload) {
        if (cb != null) {
            cb.invokeAndKeepAlive(payload);
        }
    }

    /** 确保 client 已创建；未初始化时返回 null 并回调失败信息 */
    private CRPBleClient requireClient(UniJSCallback cb) {
        if (bleClient != null) {
            return bleClient;
        }
        Context c = appContext();
        if (c == null) {
            reply(cb, fail("Android context 不可用，无法初始化 SDK"));
            return null;
        }
        try {
            if (appBleClient != null) {
                bleClient = appBleClient;    // 优先用 Application 层创建的实例（与 demo 一致）
            } else {
                bleClient = CRPBleClient.create(c);
            }
        } catch (Throwable t) {
            reply(cb, fail("初始化失败：" + t.getMessage()));
            return null;
        }
        if (bleClient == null) {
            reply(cb, fail("初始化失败：CRPBleClient.create 返回 null"));
            return null;
        }
        return bleClient;
    }

    private CRPBleConnection requireConnection(UniJSCallback cb) {
        if (bleConnection == null) {
            reply(cb, fail("设备未连接"));
            return null;
        }
        return bleConnection;
    }

    // ---------------- 初始化 / 状态 ----------------

    @UniJSMethod(uiThread = true)
    public void initialize(UniJSCallback callback) {
        CRPBleClient client = requireClient(callback);
        if (client == null) {
            return;
        }
        android.util.Log.i("CRP", "initialize ok: btEnable=" + client.isBluetoothEnable());
        JSONObject out = ok("SDK 已就绪");
        out.put("bluetoothEnable", client.isBluetoothEnable());
        reply(callback, out);
    }

    @UniJSMethod(uiThread = true)
    public void isBluetoothEnable(JSONObject params, UniJSCallback callback) {
        CRPBleClient client = requireClient(callback);
        if (client == null) {
            return;
        }
        JSONObject out = ok("");
        out.put("data", client.isBluetoothEnable());
        reply(callback, out);
    }

    // ---------------- 扫描 ----------------

    /** @param params { seconds: 10 } */
    @UniJSMethod(uiThread = true)
    public void startScan(JSONObject params, final UniJSCallback callback) {
        final CRPBleClient client = requireClient(callback);
        if (client == null) {
            return;
        }
        android.util.Log.i("CRP", "CRP scan start: btEnable=" + client.isBluetoothEnable());
        long period = 10000L;
        if (params != null && params.getLong("seconds") != null) {
            period = params.getLong("seconds") * 1000L;
        }
        try {
            android.util.Log.i("CRP", "CRP calling scanDevice(period=" + period + ")");
            boolean started = client.scanDevice(new CRPScanCallback() {
                @Override
                public void onScanning(CRPScanDevice device) {
                    if (device == null) {
                        return;
                    }
                    android.util.Log.i("CRP", "onScanning name=" + (device.getDevice() == null ? "-" : device.getDevice().getName())
                        + " mac=" + (device.getDevice() == null ? "-" : device.getDevice().getAddress())
                        + " rssi=" + device.getRssi());
                    // 2026-11-03：扫描会报出**所有** BLE 设备（实测 70 个，名字多为 null），
                    // 直接用会把列表塞满。按厂商 demo 的做法解析广播，只上报 CRP 戒指类设备。
                    CRPScanRecordInfo record = null;
                    try {
                        record = CRPScanRecordParser.parseScanRecord(device.getScanRecord());
                    } catch (Throwable t) {
                        android.util.Log.w("CRP", "parseScanRecord 失败: " + t);
                    }
                    if (record != null) {
                        android.util.Log.i("CRP", "scanRecord firmwareType=" + record.getFirmwareType()
                            + " type=" + record.getType() + " chipId=" + record.getChipId()
                            + " color=" + record.getColor() + " size=" + record.getSize()
                            + " visable=" + record.isVisable());
                    }
                    boolean looksLikeCrpRing = record != null
                        && ((record.getFirmwareType() != null && record.getFirmwareType().length() > 0)
                            || record.getType() > 0
                            || record.getChipId() > 0);
                    if (!looksLikeCrpRing) {
                        // 非 CRP 设备：只留在日志里，不进列表（否则 70 个"未知设备"没法选）
                        return;
                    }
                    JSONObject item = new JSONObject();
                    try {
                        if (device.getDevice() != null) {
                            item.put("name", device.getDevice().getName());
                            item.put("address", device.getDevice().getAddress());
                        }
                    } catch (Throwable ignored) {
                        // 个别机型读 name 需要权限，读不到就不带这个字段，不要因此中断扫描
                    }
                    item.put("rssi", device.getRssi());
                    if (record != null) {
                        if (record.getFirmwareType() != null) item.put("firmwareType", record.getFirmwareType());
                        item.put("type", record.getType());
                        item.put("isVisable", record.isVisable());
                    }
                    JSONObject out = ok("");
                    // ⚠️ 必须带 event：JS 侧 stream() 用 r.event || 'data' 取事件名，
                    // 少了它设备会被当成普通 'data' 事件，门面 searchDevice 永远收不到（真机 2026-11-03）。
                    out.put("event", "device");
                    out.put("data", item);
                    push(callback, out);
                }

                @Override
                public void onScanComplete(List<CRPScanDevice> list) {
                android.util.Log.i("CRP", "CRP onScanComplete count=" + (list == null ? 0 : list.size()));
                    JSONObject out = ok("扫描结束");
                    out.put("event", "scanFinish");
                    out.put("data", new JSONArray());
                    out.put("count", list == null ? 0 : list.size());
                    push(callback, out);
                }
            }, period);
            if (!started) {
                reply(callback, fail("扫描未能启动（可能蓝牙未开或权限不足）"));
            }
        } catch (Throwable t) {
            reply(callback, fail("扫描异常：" + t.getMessage()));
        }
    }

    @UniJSMethod(uiThread = true)
    public void stopScan(JSONObject params, UniJSCallback callback) {
        CRPBleClient client = requireClient(callback);
        if (client == null) {
            return;
        }
        try {
            client.cancelScan();
            push(callback, ok("已停止扫描"));
        } catch (Throwable t) {
            reply(callback, fail("停止扫描失败：" + t.getMessage()));
        }
    }

    // ---------------- 连接 ----------------

    /** @param params { address: "AA:BB:..." } */
    @UniJSMethod(uiThread = true)
    public void connect(JSONObject params, final UniJSCallback callback) {
        try {
            android.util.Log.i("CRP", "CRP connect entry address="
                + (params == null ? "-" : String.valueOf(params.getString("address"))));
        } catch (Throwable t) { /* 忽略 */ }
        final CRPBleClient client = requireClient(callback);
        if (client == null) {
            return;
        }
        String address = params == null ? null : params.getString("address");
        if (address == null || address.length() == 0) {
            reply(callback, fail("缺少 address 参数"));
            return;
        }
        try {
            bleDevice = client.getBleDevice(address);
            if (bleDevice == null) {
                reply(callback, fail("未找到设备：" + address));
                return;
            }
            bleConnection = bleDevice.connect();
            if (bleConnection == null) {
                reply(callback, fail("连接对象创建失败"));
                return;
            }
            bleConnection.setConnectionStateListener(new CRPBleConnectionStateListener() {
                // 状态值：0 断开 / 1 连接中 / 2 已连接 / 3 断开中（见 CRPBleConnectionStateListener 常量）
                @Override
                public void onConnectionStateChange(int state) {
                    android.util.Log.i("CRP", "CRP connectionState=" + state);
                    // 对齐 demo（DeviceActivity.closeGatt）：STATE_DISCONNECTED(0) 必须 close 释放 GATT，
                    // 否则下一次连接会连不上（GATT 未释放 -> 133/超时）。2026-11-03 对照 MYRingSDK demo 补上。
                    if (state == 2) {
                        // ⚠️ 必须等**连上之后**再查：刚调完 connect() 时状态还是"连接中"，那时发查询会被丢弃
                        // （真机 14:25 那次一条回调都没有，就是这个原因）。
                        android.util.Log.i("CRP", "连上后再查硬件能力表");
                        try {
                            bleConnection.querySupportHardwareCheck(new CRPHardwareCheckCallback() {
                                @Override
                                public void onSupportHardwareCheck(java.util.Map<Integer, Boolean> map) {
                                    android.util.Log.i("CRP", "硬件能力表(支持查询) = " + map);
                                }
                    
                                @Override
                                public void onHardwareCheckResult(java.util.Map<Integer, Boolean> map) {
                                    android.util.Log.i("CRP", "硬件检测结果(实际检测) = " + map);
                                }
                            });
                        } catch (Throwable t) {
                            android.util.Log.w("CRP", "querySupportHardwareCheck 失败: " + t);
                        }
                    }
                                        if (state == 0) {
                        try {
                            if (bleConnection != null) {
                                bleConnection.close();
                                android.util.Log.i("CRP", "connection closed (GATT released)");
                            }
                        } catch (Throwable t) {
                            android.util.Log.e("CRP", "connection.close 失败: " + t);
                        }
                        bleConnection = null;
                    }
                    JSONObject out = ok("");
                    out.put("data", state);
                    out.put("state", state);
                    push(callback, out);
                }
            });

              // （连接时注册血压监听的探针已移除：血压能力位已按用户确认置 false，不需要再注册）
                
              // 查硬件能力表：HARDWARE_HR_IC / HARDWARE_TEMPERATURE_IC / HARDWARE_ACC 等，
              // 用客观数据判断这枚戒指到底有哪些传感器（血压没有独立硬件位，但缺失项能说明问题）。
              try {
                  bleConnection.querySupportHardwareCheck(new CRPHardwareCheckCallback() {
                      @Override
                      public void onSupportHardwareCheck(java.util.Map<Integer, Boolean> map) {
                          android.util.Log.i("CRP", "硬件能力表(支持查询) = " + map);
                      }
              
                      @Override
                      public void onHardwareCheckResult(java.util.Map<Integer, Boolean> map) {
                          android.util.Log.i("CRP", "硬件检测结果(实际检测) = " + map);
                      }
                  });
              } catch (Throwable t) {
                  android.util.Log.w("CRP", "querySupportHardwareCheck 失败: " + t);
              }
            push(callback, ok("连接请求已发出"));
        } catch (Throwable t) {
            reply(callback, fail("连接异常：" + t.getMessage()));
        }
    }

    @UniJSMethod(uiThread = true)
    public void disconnect(JSONObject params, UniJSCallback callback) {
        try {
            if (bleConnection != null) {
                bleConnection.close();
            }
            bleConnection = null;
            bleDevice = null;
            push(callback, ok("已断开"));
        } catch (Throwable t) {
            reply(callback, fail("断开失败：" + t.getMessage()));
        }
    }

    @UniJSMethod(uiThread = true)
    public void isConnected(JSONObject params, UniJSCallback callback) {
        boolean connected = false;
        try {
            connected = bleDevice != null && bleDevice.isConnected();
        } catch (Throwable ignored) {
            // 保持 false
        }
        JSONObject out = ok("");
        out.put("data", connected);
        reply(callback, out);
    }

    // ---------------- 电量 / 佩戴 ----------------

    @UniJSMethod(uiThread = true)
    public void queryBattery(JSONObject params, final UniJSCallback callback) {
        final CRPBleConnection conn = requireConnection(callback);
        if (conn == null) {
            return;
        }
        // 已有缓存：直接返回（CRP 戒指回电量可能要 6s+，缓存让页面秒显）
        if (lastBatteryPct >= 0) {
            JSONObject cached = ok("");
            cached.put("data", lastBatteryPct);
            cached.put("cached", true);
            android.util.Log.i("CRP", "onBattery(cached)=" + lastBatteryPct);
            reply(callback, cached);
            return;
        }
        // 注意（2026-11-09 真机）：这枚戒指实测要 ~6s 才回电量；此前 3 次 x 800ms 就判失败，
        // 导致"真值回不来、页面永远 --"。现在最长等 15s，且**只在拿到值时才回复**。
        final int[] tries = {0};
        final boolean[] done = {false};
        try {
            conn.setBatteryListener(new CRPBatteryListener() {
                @Override
                public void onBattery(int battery) {
                    if (done[0]) return;
                    done[0] = true;
                    int pct = normalizeBattery(battery);
                    lastBatteryPct = pct;
                    JSONObject out = ok("");
                    out.put("data", pct);
                    out.put("raw", battery);
                    android.util.Log.i("CRP", "onBattery=" + pct + "（原始 " + battery + "，第 " + (tries[0] + 1) + " 次查询）");
                    reply(callback, out);
                }

                @Override
                public void onRealTimeBattery(int battery, int mv) {
                    if (done[0]) return;
                    done[0] = true;
                    int pct = normalizeBattery(battery);
                    lastBatteryPct = pct;
                    android.util.Log.i("CRP", "onRealTimeBattery=" + pct + "%（原始 " + battery + "）/ " + mv + "mv");
                    JSONObject out = ok("");
                    out.put("data", pct);
                    out.put("raw", battery);
                    out.put("voltage", mv);
                    reply(callback, out);
                }
            });
            final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            final Runnable attempt = new Runnable() {
                @Override
                public void run() {
                    if (done[0]) return;
                    if (tries[0] >= 15) {
                        android.util.Log.w("CRP", "电量查询 15 次仍无回调");
                        reply(callback, fail("设备未返回电量"));
                        return;
                    }
                    tries[0]++;
                    try {
                        conn.queryBattery();
                    } catch (Throwable t) {
                        android.util.Log.w("CRP", "queryBattery 异常: " + t);
                    }
                    try {
                        conn.queryRealTimeBattery();
                    } catch (Throwable t) {
                        android.util.Log.w("CRP", "queryRealTimeBattery 异常: " + t);
                    }
                    h.postDelayed(this, 1000);
                }
            };
            h.post(attempt);
        } catch (Throwable t) {
            reply(callback, fail("查询电量失败：" + t.getMessage()));
        }
    }

    @UniJSMethod(uiThread = true)
    public void listenWearState(JSONObject params, final UniJSCallback callback) {
        final CRPBleConnection conn = requireConnection(callback);
        if (conn == null) {
            return;
        }
        try {
            conn.setWearStateChangeListener(new CRPWearStateChangeListener() {
                @Override
                public void onWearStateChange(boolean wore) {
                    JSONObject out = ok("");
                    out.put("data", wore);
                    push(callback, out);
                }
            });
            push(callback, ok("已开始监听佩戴状态"));
        } catch (Throwable t) {
            reply(callback, fail("监听佩戴状态失败：" + t.getMessage()));
        }
    }

    // ---------------- 心率：实时 / 历史 / 定时 ----------------

    /** @param params { type: "heart" }（目前只实现心率，其它指标在 M2 按同一模式扩展） */
    @UniJSMethod(uiThread = true)
    public void startMeasure(JSONObject params, final UniJSCallback callback) {
        final CRPBleConnection conn = requireConnection(callback);
        if (conn == null) {
            return;
        }
        android.util.Log.i("CRP", "CRP startMeasure entry params=" + (params == null ? "null" : params.toJSONString()));
        String type = params == null ? "heart" : params.getString("type");
        if (type == null) {
            type = "heart";
        }
        if (!"heart".equals(type)) {
            reply(callback, fail("暂不支持该指标：" + type + "（M2 阶段接入）"));
            return;
        }
        try {
            conn.setHeartRateChangeListener(new CRPHeartRateChangeListener() {
                @Override
                public void onTimingInterval(int interval) {
                    android.util.Log.i("CRP", "onTimingInterval 报回的间隔 = " + interval);
                    JSONObject out = ok("");
                    out.put("event", "timingInterval");
                    out.put("data", interval);
                    push(callback, out);
                }

                @Override
                public void onRealtimeHeartRate(int hr) {
                    pushVital(callback, "realtimeHeart", hr);
                }

                @Override
                public void onHeartRate(int hr) {
                    pushVital(callback, "heart", hr);
                }

                @Override
                public void onHistoryHeartRate(List<CRPHistoryHeartRateInfo> list) {
                    JSONArray arr = new JSONArray();
                    if (list != null) {
                        for (CRPHistoryHeartRateInfo i : list) {
                            if (i == null) {
                                continue;
                            }
                            JSONObject item = new JSONObject();
                            item.put("rawTime", i.getRawTime());
                            item.put("hr", i.getHr());
                            arr.add(item);
                        }
                    }
                    JSONObject out = ok("");
                    out.put("event", "historyHeart");
                    out.put("data", arr);
                    push(callback, out);
                }

                @Override
                public void onTimingHeartRate(CRPHeartRateInfo info) {
                    // 定时心率是按天分组的结构，字段较多；M2 阶段再按真实 getter 展开，
                    // 这里只上报"收到"避免猜测字段名。
                    JSONObject out = ok("");
                    out.put("event", "timingHeart");
                    push(callback, out);
                }
            });
            android.util.Log.i("CRP", "CRP calling startMeasureHeartRate()");
            conn.startMeasureHeartRate();
            android.util.Log.i("CRP", "CRP startMeasureHeartRate() returned");
            push(callback, ok("已开始心率测量"));
        } catch (Throwable t) {
            reply(callback, fail("开始测量失败：" + t.getMessage()));
        }
    }

    private void pushVital(UniJSCallback callback, String event, int value) {
        android.util.Log.i("CRP", "CRP vital " + event + "=" + value);
        JSONObject out = ok("");
        out.put("event", event);
        out.put("data", value);
        out.put("at", System.currentTimeMillis());
        push(callback, out);
    }

    @UniJSMethod(uiThread = true)
    public void stopMeasure(JSONObject params, UniJSCallback callback) {
        CRPBleConnection conn = requireConnection(callback);
        if (conn == null) {
            return;
        }
        try {
            conn.stopMeasureHeartRate();
            push(callback, ok("已停止测量"));
        } catch (Throwable t) {
            reply(callback, fail("停止测量失败：" + t.getMessage()));
        }
    }

    /**
     * 血压测量（对照 demo：setBloodPressureListener + startMeasureBloodPressure / stopMeasureBloodPressure）。
     * 回调 onBloodPressureChange(sbp, dbp) -> 推送 {event:"bloodPressure", data:{systolic, diastolic}}。
     */
    @UniJSMethod(uiThread = true)
    public void startBloodPressure(JSONObject params, final UniJSCallback callback) {
        android.util.Log.i("CRP", "CRP startBloodPressure entry");
        final CRPBleConnection conn = requireConnection(callback);
        if (conn == null) {
            return;
        }
        try {
            android.util.Log.i("CRP", "BP: 即将注册 setBloodPressureListener");
            conn.setBloodPressureListener(new CRPBloodPressureChangeListener() {
                @Override
                public void onBloodPressureChange(int sbp, int dbp) {
                    android.util.Log.i("CRP", "CRP vital bloodPressure=" + sbp + "/" + dbp);
                    JSONObject out = ok("");
                    out.put("event", "bloodPressure");
                    JSONObject d = new JSONObject();
                    d.put("systolic", sbp);
                    d.put("diastolic", dbp);
                    out.put("data", d);
                    push(callback, out);
                }

            });
            conn.startMeasureBloodPressure();
            android.util.Log.i("CRP", "CRP calling startMeasureBloodPressure()");
            push(callback, ok("已开始血压测量"));
        } catch (Throwable t) {
            reply(callback, fail("开始血压测量失败：" + t.getMessage()));
        }
    }

    @UniJSMethod(uiThread = true)
    public void stopBloodPressure(JSONObject params, UniJSCallback callback) {
        final CRPBleConnection conn = requireConnection(callback);
        if (conn == null) {
            return;
        }
        try {
            conn.stopMeasureBloodPressure();
            reply(callback, ok("已停止血压测量"));
        } catch (Throwable t) {
            reply(callback, fail("停止血压测量失败：" + t.getMessage()));
        }
    }

    // ==================== 设备操作（对照 SDK 1.1.3 真实方法） ====================

    /** 同步设备时间（SDK: syncTime()）。睡眠/步数的时间戳正确性依赖它，建议连接后自动调一次。 */
    @UniJSMethod(uiThread = true)
    public void syncDeviceTime(JSONObject params, UniJSCallback callback) {
        final CRPBleConnection conn = requireConnection(callback);
        if (conn == null) return;
        try {
            conn.syncTime();
            android.util.Log.i("CRP", "syncTime() 已调用");
            reply(callback, ok("已同步设备时间"));
        } catch (Throwable t) {
            reply(callback, fail("同步时间失败：" + t.getMessage()));
        }
    }

    /** 解绑/解除系统配对（SDK: removeBond()）—— 破坏性操作，调用前 UI 必须二次确认 */
    @UniJSMethod(uiThread = true)
    public void unbindDevice(JSONObject params, UniJSCallback callback) {
        final CRPBleConnection conn = requireConnection(callback);
        if (conn == null) return;
        try {
            conn.removeBond();
            android.util.Log.i("CRP", "removeBond() 已调用");
            reply(callback, ok("已解除配对"));
        } catch (Throwable t) {
            reply(callback, fail("解绑失败：" + t.getMessage()));
        }
    }

    /** 恢复出厂/重置（SDK: reset()）—— 破坏性操作 */
    @UniJSMethod(uiThread = true)
    public void resetDevice(JSONObject params, UniJSCallback callback) {
        final CRPBleConnection conn = requireConnection(callback);
        if (conn == null) return;
        try {
            conn.reset();
            android.util.Log.i("CRP", "reset() 已调用");
            reply(callback, ok("已重置设备"));
        } catch (Throwable t) {
            reply(callback, fail("重置失败：" + t.getMessage()));
        }
    }

    /** 重启（SDK: restart()） */
    @UniJSMethod(uiThread = true)
    public void restartDevice(JSONObject params, UniJSCallback callback) {
        final CRPBleConnection conn = requireConnection(callback);
        if (conn == null) return;
        try {
            conn.restart();
            android.util.Log.i("CRP", "restart() 已调用");
            reply(callback, ok("设备正在重启"));
        } catch (Throwable t) {
            reply(callback, fail("重启失败：" + t.getMessage()));
        }
    }

    /** 关机（SDK: shutDown()） */
    @UniJSMethod(uiThread = true)
    public void shutDownDevice(JSONObject params, UniJSCallback callback) {
        final CRPBleConnection conn = requireConnection(callback);
        if (conn == null) return;
        try {
            // shutDown 需要回调：onSuccess() 表示设备已响应关机
            conn.shutDown(new CRPShutDownCallback() {
                @Override
                public void onSuccess() {
                    android.util.Log.i("CRP", "shutDown() onSuccess");
                    reply(callback, ok("设备已关机"));
                }
            });
            android.util.Log.i("CRP", "shutDown() 已调用（等待设备响应）");
        } catch (Throwable t) {
            reply(callback, fail("关机失败：" + t.getMessage()));
        }
    }

    @UniJSMethod(uiThread = true)
    public void queryHistoryHeartRate(JSONObject params, UniJSCallback callback) {
        CRPBleConnection conn = requireConnection(callback);
        if (conn == null) {
            return;
        }
        try {
            // 结果通过 setHeartRateChangeListener.onHistoryHeartRate 回来的，
            // JS 侧要先 startMeasure（或直接挂监听）才能收到 —— 这里显式提示。
            conn.queryHistoryHeartRate();
            push(callback, ok("历史心率查询已发出，结果经监听回调返回"));
        } catch (Throwable t) {
            reply(callback, fail("查询历史心率失败：" + t.getMessage()));
        }
    }

    /** @param params { minutes: 30 } → interval 为分钟数 */
    @UniJSMethod(uiThread = true)
    public void enableTimingHeartRate(JSONObject params, UniJSCallback callback) {
        CRPBleConnection conn = requireConnection(callback);
        if (conn == null) {
            return;
        }
        int minutes = 30;
        if (params != null && params.getInteger("minutes") != null) {
            minutes = params.getIntValue("minutes");
        }
        try {
            conn.enableTimingHeartRate(minutes);
            android.util.Log.i("CRP", "enableTimingHeartRate(" + minutes + ") 已下发");
            push(callback, ok("定时心率已开启"));
        } catch (Throwable t) {
            reply(callback, fail("开启定时心率失败：" + t.getMessage()));
        }
    }

    @UniJSMethod(uiThread = true)
    public void disableTimingHeartRate(JSONObject params, UniJSCallback callback) {
        CRPBleConnection conn = requireConnection(callback);
        if (conn == null) {
            return;
        }
        try {
            conn.disableTimingHeartRate();
            push(callback, ok("定时心率已关闭"));
        } catch (Throwable t) {
            reply(callback, fail("关闭定时心率失败：" + t.getMessage()));
        }
    }

    @UniJSMethod(uiThread = true)
    public void queryTimingHeartRateState(JSONObject params, UniJSCallback callback) {
        CRPBleConnection conn = requireConnection(callback);
        if (conn == null) {
            return;
        }
        try {
            conn.queryTimingHeartRateState();
            push(callback, ok("定时心率状态查询已发出，结果经监听回调返回"));
        } catch (Throwable t) {
            reply(callback, fail("查询定时心率状态失败：" + t.getMessage()));
        }
    }

    // ---------------- M2：血氧 / HRV / 压力 / 体温 / 睡眠 / 步数 ----------------
    //
    // 说明：这些指标的数据都是"先挂监听、再发查询"，结果经监听回调返回。
    // 所以 JS 侧约定是：调用 startXxx/queryXxx 时传入的同一个 callback，
    // 会先收到一次 {success:true,msg:...} 的应答，随后**多次**收到
    // {success:true,event:'...',data:...} 的数据（invokeAndKeepAlive）。
    // 不这样做就会出现"点了没反应"——查了但没有监听，数据无处可去。

    /** 把 0..14 映射成 CRPHistoryDay（0=今天） */
    private static CRPHistoryDay dayOf(int n) {
        int v = n < 0 ? 0 : (n > 14 ? 14 : n);
        try {
            return CRPHistoryDay.getInstance((byte) v);
        } catch (Throwable t) {
            return CRPHistoryDay.TODAY;
        }
    }

    private static JSONObject stepsJson(CRPStepsInfo s) {
        JSONObject o = new JSONObject();
        if (s == null) {
            return o;
        }
        // 2026-11-13 真机单位实测（937 步时）：SDK 的 distance=62800、calories=680000，
        //   62800 厘米 = 628 米（0.67 米/步，合理）→ distance 单位是**厘米**；
        //   680000 卡 = 680 千卡（一天累计含基础代谢，合理）→ calories 单位是**卡(cal)**。
        // 后端 DeviceIngestService 的换算是按「米 / 千卡」写的：
        //   distanceKm = round(distance / 100.0) / 10.0   // 即 distance/1000 = 公里
        //   kcal       = calories
        // 所以这里统一换算成 米 / 千卡 再上报，避免运动页出现 "62.8 km / 680000 kcal" 这种离谱数字。
        // 原始值一并保留（distanceRaw/caloriesRaw），便于以后真机复核单位。
        int rawDistance = s.getDistance();
        int rawCalories = s.getCalories();
        o.put("steps", s.getSteps());
        o.put("distance", Math.round(rawDistance / 100f));      // 厘米 -> 米
        o.put("calories", Math.round(rawCalories / 1000f));     // 卡 -> 千卡
        o.put("distanceRaw", rawDistance);
        o.put("caloriesRaw", rawCalories);
        o.put("time", s.getTime());           // 单位：秒
        return o;
    }

    private static JSONObject sleepJson(CRPSleepInfo s) {
        JSONObject o = new JSONObject();
        if (s == null) {
            return o;
        }
        o.put("totalTime", s.getTotalTime());
        o.put("deepTime", s.getDeepTime());
        o.put("lightTime", s.getLightTime());
        o.put("awakeTime", s.getAwakeTime());
        o.put("remTime", s.getRemTime());
        JSONArray details = new JSONArray();
        List<CRPSleepInfo.DetailBean> list = s.getDetails();
        if (list != null) {
            for (CRPSleepInfo.DetailBean d : list) {
                if (d == null) {
                    continue;
                }
                JSONObject item = new JSONObject();
                item.put("startTime", d.getStartTime());
                item.put("endTime", d.getEndTime());
                item.put("totalTime", d.getTotalTime());
                item.put("type", d.getType());     // 对应 SLEEP_STATE_DEEP/LIGHT/REM/AWAKE
                details.add(item);
            }
        }
        o.put("details", details);
        return o;
    }

    /** @param params { type: 'spo2'|'hrv'|'stress'|'heart' } 之外的指标请用各自的 startXxx 方法 */

    @UniJSMethod(uiThread = true)
    public void startBloodOxygen(JSONObject params, final UniJSCallback callback) {
        final CRPBleConnection conn = requireConnection(callback);
        if (conn == null) {
            return;
        }
        try {
            conn.setBloodOxygenChangeListener(new CRPBloodOxygenChangeListener() {
                @Override
                public void onTimingInterval(int interval) {
                    pushEvent(callback, "timingInterval", interval);
                }

                @Override
                public void onBloodOxygen(int spo2) {
                    pushVital(callback, "spo2", spo2);
                }

                @Override
                public void onHistoryBloodOxygen(List<CRPHistoryBloodOxygenInfo> list) {
                    JSONArray arr = new JSONArray();
                    if (list != null) {
                        for (CRPHistoryBloodOxygenInfo i : list) {
                            if (i == null) {
                                continue;
                            }
                            JSONObject item = new JSONObject();
                            item.put("rawTime", i.getRawTime());
                            item.put("spo2", i.getBo());
                            arr.add(item);
                        }
                    }
                    pushEvent(callback, "historySpo2", arr);
                }

                @Override
                public void onTimingBloodOxygen(CRPTimingBloodOxygenInfo info) {
                    pushEvent(callback, "timingSpo2", null);
                }

                @Override
                public void onSupportSleepBloodOxygen(boolean support) {
                    pushEvent(callback, "supportSleepSpo2", support);
                }
            });
            conn.startMeasureBloodOxygen();
            push(callback, ok("已开始血氧测量"));
        } catch (Throwable t) {
            reply(callback, fail("开始血氧测量失败：" + t.getMessage()));
        }
    }

    @UniJSMethod(uiThread = true)
    public void stopBloodOxygen(JSONObject params, UniJSCallback callback) {
        CRPBleConnection conn = requireConnection(callback);
        if (conn == null) {
            return;
        }
        try {
            conn.stopMeasureBloodOxygen();
            push(callback, ok("已停止血氧测量"));
        } catch (Throwable t) {
            reply(callback, fail("停止血氧测量失败：" + t.getMessage()));
        }
    }

    // ==================== 定时监测（自动测量） ====================
    // 对照 SDK：enableTimingHeartRate(int) / enableTimingBloodOxygen(int) / enableTimingHrv(int) 可设间隔，
    // enableTimingTemp() 只有开/关。间隔单位厂商文档（扫描件 PDF）未写明，先按分钟透传，
    // 实际值由回调 onTimingInterval 报回来确认（已在监听里打日志）。

    @UniJSMethod(uiThread = true)
    public void enableTimingBloodOxygen(JSONObject params, final UniJSCallback callback) {
        final CRPBleConnection conn = requireConnection(callback);
        if (conn == null) return;
        int interval = params == null ? 15 : params.getIntValue("interval");
        try {
            conn.enableTimingBloodOxygen(interval);
            android.util.Log.i("CRP", "enableTimingBloodOxygen(" + interval + ")");
            push(callback, ok("已开启定时血氧"));
        } catch (Throwable t) {
            reply(callback, fail("开启定时血氧失败：" + t.getMessage()));
        }
    }

    @UniJSMethod(uiThread = true)
    public void disableTimingBloodOxygen(JSONObject params, UniJSCallback callback) {
        final CRPBleConnection conn = requireConnection(callback);
        if (conn == null) return;
        try {
            conn.disableTimingBloodOxygen();
            reply(callback, ok("已关闭定时血氧"));
        } catch (Throwable t) {
            reply(callback, fail("关闭定时血氧失败：" + t.getMessage()));
        }
    }

    @UniJSMethod(uiThread = true)
    public void enableTimingHrv(JSONObject params, final UniJSCallback callback) {
        final CRPBleConnection conn = requireConnection(callback);
        if (conn == null) return;
        int interval = params == null ? 15 : params.getIntValue("interval");
        try {
            conn.enableTimingHrv(interval);
            android.util.Log.i("CRP", "enableTimingHrv(" + interval + ")");
            push(callback, ok("已开启定时 HRV"));
        } catch (Throwable t) {
            reply(callback, fail("开启定时 HRV 失败：" + t.getMessage()));
        }
    }

    @UniJSMethod(uiThread = true)
    public void disableTimingHrv(JSONObject params, UniJSCallback callback) {
        final CRPBleConnection conn = requireConnection(callback);
        if (conn == null) return;
        try {
            conn.disableTimingHrv();
            reply(callback, ok("已关闭定时 HRV"));
        } catch (Throwable t) {
            reply(callback, fail("关闭定时 HRV 失败：" + t.getMessage()));
        }
    }

    @UniJSMethod(uiThread = true)
    public void enableTimingTemp(JSONObject params, UniJSCallback callback) {
        final CRPBleConnection conn = requireConnection(callback);
        if (conn == null) return;
        try {
            conn.enableTimingTemp();
            android.util.Log.i("CRP", "enableTimingTemp()");
            reply(callback, ok("已开启定时体温"));
        } catch (Throwable t) {
            reply(callback, fail("开启定时体温失败：" + t.getMessage()));
        }
    }

    @UniJSMethod(uiThread = true)
    public void disableTimingTemp(JSONObject params, UniJSCallback callback) {
        final CRPBleConnection conn = requireConnection(callback);
        if (conn == null) return;
        try {
            conn.disableTimingTemp();
            reply(callback, ok("已关闭定时体温"));
        } catch (Throwable t) {
            reply(callback, fail("关闭定时体温失败：" + t.getMessage()));
        }
    }

    @UniJSMethod(uiThread = true)
    public void queryHistoryBloodOxygen(JSONObject params, UniJSCallback callback) {
        CRPBleConnection conn = requireConnection(callback);
        if (conn == null) {
            return;
        }
        try {
            conn.queryHistoryBloodOxygen();
            push(callback, ok("历史血氧查询已发出"));
        } catch (Throwable t) {
            reply(callback, fail("查询历史血氧失败：" + t.getMessage()));
        }
    }

    @UniJSMethod(uiThread = true)
    public void startHrv(JSONObject params, final UniJSCallback callback) {
        final CRPBleConnection conn = requireConnection(callback);
        if (conn == null) {
            return;
        }
        try {
            conn.setHrvChangeListener(new CRPHrvChangeListener() {
                @Override
                public void onHrv(int hrv) {
                    pushVital(callback, "hrv", hrv);
                }

                @Override
                public void onHistoryHrv(List<CRPHistoryHrvInfo> list) {
                    JSONArray arr = new JSONArray();
                    if (list != null) {
                        for (CRPHistoryHrvInfo i : list) {
                            if (i == null) {
                                continue;
                            }
                            JSONObject item = new JSONObject();
                            item.put("rawTime", i.getRawTime());
                            item.put("hrv", i.getHrv());
                            arr.add(item);
                        }
                    }
                    pushEvent(callback, "historyHrv", arr);
                }

                @Override
                public void onTimingInterval(int interval) {
                    pushEvent(callback, "timingInterval", interval);
                }

                @Override
                public void onTimingHrv(CRPTimingHrvInfo info) {
                    pushEvent(callback, "timingHrv", null);
                }
            });
            conn.startMeasureHrv();
            push(callback, ok("已开始 HRV 测量"));
        } catch (Throwable t) {
            reply(callback, fail("开始 HRV 测量失败：" + t.getMessage()));
        }
    }

    @UniJSMethod(uiThread = true)
    public void stopHrv(JSONObject params, UniJSCallback callback) {
        CRPBleConnection conn = requireConnection(callback);
        if (conn == null) {
            return;
        }
        try {
            conn.stopMeasureHrv();
            push(callback, ok("已停止 HRV 测量"));
        } catch (Throwable t) {
            reply(callback, fail("停止 HRV 测量失败：" + t.getMessage()));
        }
    }

    @UniJSMethod(uiThread = true)
    public void queryHistoryHrv(JSONObject params, final UniJSCallback callback) {
        final CRPBleConnection conn = requireConnection(callback);
        if (conn == null) {
            return;
        }
        // 2026-11-13 修复（同类问题统一清理）：原实现发完指令就 push("已发出")，
        // 数据其实在 onHistoryHrv 回调里 -> JS 永远拿不到，HRV 同步因此静默失败。
        try {
            final boolean[] done = {false};
            conn.setHrvChangeListener(new CRPHrvChangeListener() {
                @Override
                public void onHrv(int hrv) {
                }

                @Override
                public void onHistoryHrv(List<CRPHistoryHrvInfo> list) {
                    if (done[0]) return;
                    done[0] = true;
                    JSONArray arr = new JSONArray();
                    if (list != null) {
                        for (CRPHistoryHrvInfo it : list) {
                            if (it == null) continue;
                            JSONObject o = new JSONObject();
                            o.put("hrv", it.getHrv());
                            o.put("time", it.getRawTime());
                            arr.add(o);
                        }
                    }
                    android.util.Log.i("CRP", "onHistoryHrv 条数=" + arr.size());
                    JSONObject out = ok("");
                    out.put("data", arr);
                    reply(callback, out);
                }

                @Override
                public void onTimingInterval(int interval) {
                }

                @Override
                public void onTimingHrv(CRPTimingHrvInfo info) {
                }
            });
            conn.queryHistoryHrv();
            final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.postDelayed(() -> {
                if (done[0]) return;
                done[0] = true;
                android.util.Log.w("CRP", "查询历史 HRV 8s 无回调");
                reply(callback, fail("查询历史 HRV 超时（设备未回调）"));
            }, 8000);
        } catch (Throwable t) {
            reply(callback, fail("查询历史 HRV 失败：" + t.getMessage()));
        }
    }

    @UniJSMethod(uiThread = true)
    public void startStress(JSONObject params, final UniJSCallback callback) {
        final CRPBleConnection conn = requireConnection(callback);
        if (conn == null) {
            return;
        }
        try {
            conn.setStressChangeListener(new CRPStressChangeListener() {
                @Override
                public void onStressChange(int stress) {
                    pushVital(callback, "stress", stress);
                }

                @Override
                public void onHistoryStressChange(List<CRPHistoryStressInfo> list) {
                    JSONArray arr = new JSONArray();
                    if (list != null) {
                        for (CRPHistoryStressInfo i : list) {
                            if (i == null) {
                                continue;
                            }
                            JSONObject item = new JSONObject();
                            if (i.getDate() != null) {
                                item.put("date", i.getDate().getTime());
                            }
                            item.put("stress", i.getStress());
                            arr.add(item);
                        }
                    }
                    pushEvent(callback, "historyStress", arr);
                }
            });
            conn.startMeasureStress();
            push(callback, ok("已开始压力测量"));
        } catch (Throwable t) {
            reply(callback, fail("开始压力测量失败：" + t.getMessage()));
        }
    }

    @UniJSMethod(uiThread = true)
    public void stopStress(JSONObject params, UniJSCallback callback) {
        CRPBleConnection conn = requireConnection(callback);
        if (conn == null) {
            return;
        }
        try {
            conn.stopMeasureStress();
            push(callback, ok("已停止压力测量"));
        } catch (Throwable t) {
            reply(callback, fail("停止压力测量失败：" + t.getMessage()));
        }
    }

    @UniJSMethod(uiThread = true)
    public void queryHistoryStress(JSONObject params, final UniJSCallback callback) {
        final CRPBleConnection conn = requireConnection(callback);
        if (conn == null) {
            return;
        }
        // 2026-11-13 修复（同类清理）：数据在 onHistoryStressChange 回调里，
        // 原实现发完即回导致「压力同步」永远拿不到值。
        try {
            final boolean[] done = {false};
            conn.setStressChangeListener(new CRPStressChangeListener() {
                @Override
                public void onStressChange(int stress) {
                }

                @Override
                public void onHistoryStressChange(List<CRPHistoryStressInfo> list) {
                    if (done[0]) return;
                    done[0] = true;
                    JSONArray arr = new JSONArray();
                    if (list != null) {
                        for (CRPHistoryStressInfo it : list) {
                            if (it == null) continue;
                            JSONObject o = new JSONObject();
                            o.put("stress", it.getStress());
                            if (it.getDate() != null) {
                                o.put("time", it.getDate().getTime());
                            }
                            arr.add(o);
                        }
                    }
                    android.util.Log.i("CRP", "onHistoryStressChange 条数=" + arr.size());
                    JSONObject out = ok("");
                    out.put("data", arr);
                    reply(callback, out);
                }
            });
            conn.queryHistoryStress();
            final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.postDelayed(() -> {
                if (done[0]) return;
                done[0] = true;
                android.util.Log.w("CRP", "查询历史压力 8s 无回调");
                reply(callback, fail("查询历史压力超时（设备未回调）"));
            }, 8000);
        } catch (Throwable t) {
            reply(callback, fail("查询历史压力失败：" + t.getMessage()));
        }
    }

    @UniJSMethod(uiThread = true)
    public void listenTemperature(JSONObject params, final UniJSCallback callback) {
        android.util.Log.i("CRP", "CRP listenTemperature entry");
        final CRPBleConnection conn = requireConnection(callback);
        if (conn == null) {
            return;
        }
        try {
            conn.setTempChangeListener(new CRPTempChangeListener() {
                @Override
                public void onTimingState(boolean on) {
                    pushEvent(callback, "timingTempState", on);
                }

                @Override
                public void onHistoryTempChange(CRPHistoryTempInfo info) {
                    JSONArray arr = new JSONArray();
                    if (info != null && info.getTempList() != null) {
                        for (Float f : info.getTempList()) {
                            arr.add(f);
                        }
                    }
                    pushEvent(callback, "historyTemp", arr);
                }
            });
            push(callback, ok("已开始监听体温"));
            // 2026-11-03：CRP SDK **没有**实时体温回调（CRPTempChangeListener 只有 onTimingState /
            // onHistoryTempChange），体温只能来自定时监测的历史数据。注册完监听后主动查一次今天的历史，
            // 值会从同一个监听回调回来。放在 Java 侧做，避免 JS 直接调 queryHistoryTemp（需要 CRPHistoryDay 参数）。
            try {
                conn.queryHistoryTemp(CRPHistoryDay.TODAY);
                android.util.Log.i("CRP", "CRP 主动查一次历史体温(TODAY)");
            } catch (Throwable t2) {
                android.util.Log.w("CRP", "查询历史体温失败: " + t2);
            }
        } catch (Throwable t) {
            reply(callback, fail("监听体温失败：" + t.getMessage()));
        }
    }

    @UniJSMethod(uiThread = true)
    public void queryHistoryTemp(JSONObject params, UniJSCallback callback) {
        CRPBleConnection conn = requireConnection(callback);
        if (conn == null) {
            return;
        }
        int daysAgo = params == null || params.getInteger("daysAgo") == null ? 0 : params.getIntValue("daysAgo");
        try {
            conn.queryHistoryTemp(dayOf(daysAgo));
            push(callback, ok("历史体温查询已发出"));
        } catch (Throwable t) {
            reply(callback, fail("查询历史体温失败：" + t.getMessage()));
        }
    }

    // ---------------- 睡眠 ----------------

    @UniJSMethod(uiThread = true)
    public void listenSleep(JSONObject params, final UniJSCallback callback) {
        final CRPBleConnection conn = requireConnection(callback);
        if (conn == null) {
            return;
        }
        try {
            conn.setSleepChangeListener(new CRPSleepChangeListener() {
                @Override
                public void onHistorySleepChange(CRPHistoryDay day, CRPSleepInfo info) {
                    JSONObject data = sleepJson(info);
                    if (day != null) {
                        data.put("daysAgo", day.ordinal());
                    }
                    pushEvent(callback, "sleep", data);
                }

                @Override
                public void onHistorySleepListChange(List<Long> list) {
                    JSONArray arr = new JSONArray();
                    if (list != null) {
                        for (Long t : list) {
                            arr.add(t);
                        }
                    }
                    pushEvent(callback, "sleepList", arr);
                }

                @Override
                public void onSleepDetails(CRPSleepDetailsInfo info) {
                    JSONObject o = new JSONObject();
                    if (info != null) {
                        o.put("id", info.getId());
                        o.put("sleepTime", info.getSleepTime());
                        o.put("sleepEfficiency", info.getSleepEfficiency());
                        o.put("score", info.getScore());
                        if (info.getStartDate() != null) {
                            o.put("startAt", info.getStartDate().getTime());
                        }
                        if (info.getEndDate() != null) {
                            o.put("endAt", info.getEndDate().getTime());
                        }
                        if (info.getType() != null) {
                            o.put("type", info.getType().name());
                        }
                    }
                    pushEvent(callback, "sleepDetails", o);
                }

                @Override
                public void onSleepInfo(CRPSleepInfo info) {
                    pushEvent(callback, "sleepInfo", sleepJson(info));
                }

                @Override
                public void onSleepChronotype(CRPSleepChronotypeInfo info) {
                    JSONObject o = new JSONObject();
                    if (info != null && info.getSleepChronotype() != null) {
                        o.put("chronotype", info.getSleepChronotype().name());
                        o.put("highConfidence", info.isHighConfidence());
                    }
                    pushEvent(callback, "sleepChronotype", o);
                }

                @Override
                public void onSleepEnd(boolean ok) {
                    pushEvent(callback, "sleepEnd", ok);
                }
            });
            push(callback, ok("已开始监听睡眠"));
        } catch (Throwable t) {
            reply(callback, fail("监听睡眠失败：" + t.getMessage()));
        }
    }

    @UniJSMethod(uiThread = true)
    public void queryHistorySleepList(JSONObject params, UniJSCallback callback) {
        CRPBleConnection conn = requireConnection(callback);
        if (conn == null) {
            return;
        }
        try {
            conn.queryHistorySleepList();
            push(callback, ok("睡眠记录列表查询已发出"));
        } catch (Throwable t) {
            reply(callback, fail("查询睡眠列表失败：" + t.getMessage()));
        }
    }

    @UniJSMethod(uiThread = true)
    public void queryHistorySleep(JSONObject params, final UniJSCallback callback) {
        final CRPBleConnection conn = requireConnection(callback);
        if (conn == null) {
            return;
        }
        final int daysAgo = params == null || params.getInteger("daysAgo") == null ? 0 : params.getIntValue("daysAgo");
        // 2026-11-13 修复（与 queryCurrentSteps 同一个坑）：
        // 原实现 queryHistorySleep() 发完指令就 push("睡眠查询已发出")，而数据要等 onHistorySleepChange 回调
        // -> JS 永远拿不到睡眠数据，后端从没收到过 sleep。现在先注册监听、拿到数据才回复。
        try {
            final boolean[] done = {false};
            conn.setSleepChangeListener(new CRPSleepChangeListener() {
                @Override
                public void onHistorySleepChange(CRPHistoryDay day, CRPSleepInfo info) {
                    if (done[0]) return;
                    // SDK 会先回调一次 info=null（数据还没准备好），忽略继续等有效值
                    if (info == null) {
                        android.util.Log.i("CRP", "onHistorySleepChange=null（忽略，继续等有效值）");
                        return;
                    }
                    done[0] = true;
                    android.util.Log.i("CRP", "onHistorySleepChange total=" + info.getTotalTime()
                            + " deep=" + info.getDeepTime() + " light=" + info.getLightTime()
                            + " awake=" + info.getAwakeTime() + " rem=" + info.getRemTime());
                    JSONObject o = ok("");
                    JSONObject data = sleepJson(info);
                    if (day != null) {
                        data.put("daysAgo", day.ordinal());
                    }
                    o.put("data", data);
                    reply(callback, o);
                }

                @Override
                public void onHistorySleepListChange(List<Long> list) {
                    // 本方法查"某一天的睡眠"，列表由 queryHistorySleepList 处理
                }

                @Override
                public void onSleepDetails(CRPSleepDetailsInfo info) {
                }

                @Override
                public void onSleepInfo(CRPSleepInfo info) {
                }

                @Override
                public void onSleepChronotype(CRPSleepChronotypeInfo info) {
                }

                @Override
                public void onSleepEnd(boolean ok) {
                    // 本方法只查"某一天的睡眠数据"，睡眠结束事件由 listenSleep 处理
                }
            });
            conn.queryHistorySleep(dayOf(daysAgo));
            final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.postDelayed(() -> {
                if (done[0]) return;
                done[0] = true;
                android.util.Log.w("CRP", "查询睡眠 8s 无回调");
                reply(callback, fail("查询睡眠超时（设备未回调）"));
            }, 8000);
        } catch (Throwable t) {
            reply(callback, fail("查询睡眠失败：" + t.getMessage()));
        }
    }

    @UniJSMethod(uiThread = true)
    public void querySleepDetails(JSONObject params, UniJSCallback callback) {
        CRPBleConnection conn = requireConnection(callback);
        if (conn == null) {
            return;
        }
        int id = params == null || params.getInteger("id") == null ? 0 : params.getIntValue("id");
        try {
            conn.querySleepDetails(id);
            push(callback, ok("睡眠详情查询已发出"));
        } catch (Throwable t) {
            reply(callback, fail("查询睡眠详情失败：" + t.getMessage()));
        }
    }

    @UniJSMethod(uiThread = true)
    public void querySleepChronotype(JSONObject params, UniJSCallback callback) {
        CRPBleConnection conn = requireConnection(callback);
        if (conn == null) {
            return;
        }
        try {
            conn.querySleepChronotype();
            push(callback, ok("睡眠时型查询已发出"));
        } catch (Throwable t) {
            reply(callback, fail("查询睡眠时型失败：" + t.getMessage()));
        }
    }

    // ---------------- 步数 / 活动 ----------------

    @UniJSMethod(uiThread = true)
    public void listenSteps(JSONObject params, final UniJSCallback callback) {
        final CRPBleConnection conn = requireConnection(callback);
        if (conn == null) {
            return;
        }
        try {
            conn.setStepsChangeListener(new CRPStepsChangeListener() {
                @Override
                public void onCurrentSteps(CRPStepsInfo info) {
                    pushEvent(callback, "currentSteps", stepsJson(info));
                }

                @Override
                public void onHistorySteps(CRPHistoryDay day, CRPStepsInfo info) {
                    JSONObject o = stepsJson(info);
                    if (day != null) {
                        o.put("daysAgo", day.ordinal());
                    }
                    pushEvent(callback, "historySteps", o);
                }

                @Override
                public void onHistoryStepsDetails(CRPStepsDetailsInfo info) {
                    JSONArray arr = new JSONArray();
                    if (info != null && info.getStepsList() != null) {
                        for (Integer v : info.getStepsList()) {
                            arr.add(v);
                        }
                    }
                    pushEvent(callback, "historyStepsDetails", arr);
                }
            });
            push(callback, ok("已开始监听步数"));
        } catch (Throwable t) {
            reply(callback, fail("监听步数失败：" + t.getMessage()));
        }
    }

    @UniJSMethod(uiThread = true)
    public void queryCurrentSteps(JSONObject params, final UniJSCallback callback) {
        final CRPBleConnection conn = requireConnection(callback);
        if (conn == null) {
            return;
        }
        // 2026-11-13 修复：原实现 conn.queryCurrentSteps() 只是"发指令"，数据要等
        // onCurrentSteps 回调 —— 但它紧接着就 reply("已发出")，导致 JS 永远拿不到步数
        // （表现为：运动页一直 0 步、后端永远收不到 steps）。
        // 现在先注册监听、拿到 onCurrentSteps 再回复；6 秒没回调才判超时。
        try {
            final boolean[] done = {false};
            conn.setStepsChangeListener(new CRPStepsChangeListener() {
                @Override
                public void onCurrentSteps(CRPStepsInfo info) {
                    if (done[0]) return;
                    // 2026-11-13 真机：SDK 会先回调一次 info=null（数据还没准备好），
                    // 把 null 当结果回复会让 JS 拿到空步数。这里忽略 null，等有效值。
                    if (info == null) {
                        android.util.Log.i("CRP", "onCurrentSteps=null（忽略，继续等有效值）");
                        return;
                    }
                    done[0] = true;
                    android.util.Log.i("CRP", "onCurrentSteps=" + info.getSteps() + " 步 / "
                            + info.getDistance() + "m / " + info.getCalories() + "kcal");
                    JSONObject o = ok("");
                    o.put("data", stepsJson(info));
                    reply(callback, o);
                }

                @Override
                public void onHistorySteps(CRPHistoryDay day, CRPStepsInfo info) {
                    // 本方法只查"当日"，历史数据由 queryHistorySteps 处理
                }

                @Override
                public void onHistoryStepsDetails(CRPStepsDetailsInfo info) {
                    // 同上
                }
            });
            conn.queryCurrentSteps();
            final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.postDelayed(() -> {
                if (done[0]) return;
                done[0] = true;
                android.util.Log.w("CRP", "查询当日步数 6s 无回调");
                reply(callback, fail("查询当日步数超时（设备未回调）"));
            }, 6000);
        } catch (Throwable t) {
            reply(callback, fail("查询当日步数失败：" + t.getMessage()));
        }
    }

    @UniJSMethod(uiThread = true)
    public void queryHistorySteps(JSONObject params, final UniJSCallback callback) {
        final CRPBleConnection conn = requireConnection(callback);
        if (conn == null) {
            return;
        }
        final int daysAgo = params == null || params.getInteger("daysAgo") == null ? 0 : params.getIntValue("daysAgo");
        // 2026-11-13 修复：原实现 queryHistorySteps() 发完指令就 push("已发出")，
        // 而真正的数据在 onHistorySteps(day, info) 回调里（官方 demo 也是靠这个回调取值）。
        try {
            final boolean[] done = {false};
            conn.setStepsChangeListener(new CRPStepsChangeListener() {
                @Override
                public void onCurrentSteps(CRPStepsInfo info) {
                    // 本方法查"某一天的历史步数"，实时步数由 queryCurrentSteps 处理
                }

                @Override
                public void onHistorySteps(CRPHistoryDay day, CRPStepsInfo info) {
                    if (done[0]) return;
                    if (info == null) {
                        android.util.Log.i("CRP", "onHistorySteps=null（忽略，继续等有效值）");
                        return;
                    }
                    done[0] = true;
                    android.util.Log.i("CRP", "onHistorySteps daysAgo=" + (day == null ? "?" : day.ordinal())
                            + " steps=" + info.getSteps() + " dist=" + info.getDistance() + " kcal=" + info.getCalories());
                    JSONObject out = ok("");
                    JSONObject data = stepsJson(info);
                    if (day != null) {
                        data.put("daysAgo", day.ordinal());
                    }
                    out.put("data", data);
                    reply(callback, out);
                }

                @Override
                public void onHistoryStepsDetails(CRPStepsDetailsInfo info) {
                    // 明细由 queryHistoryStepsDetails 处理
                }
            });
            conn.queryHistorySteps(dayOf(daysAgo));
            final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.postDelayed(() -> {
                if (done[0]) return;
                done[0] = true;
                android.util.Log.w("CRP", "查询历史步数 8s 无回调");
                reply(callback, fail("查询历史步数超时（设备未回调）"));
            }, 8000);
        } catch (Throwable t) {
            reply(callback, fail("查询历史步数失败：" + t.getMessage()));
        }
    }

    /**
     * 查询某一天的"步数明细"（2026-11-13 新增，对齐官方 demo 的 btn_sync_step：
     *   bleConnection.queryCurrentSteps();
     *   bleConnection.queryHistoryStepsDetails(CRPHistoryDay.TODAY);
     * ）。
     *
     * 为什么必须有它：戒指的 `queryCurrentSteps()` 只在"当前正在产生步数"时回调，
     * 用户今天走了路但当前没在走时，onCurrentSteps 会回调 null；
     * 而当日累计步数真正来自 queryHistoryStepsDetails(TODAY) 的 stepsList（按时间段分桶）。
     * JS 侧把 stepsList 求和即得当日总步数。
     *
     * @param params { daysAgo: 0=今天 ... 14 }
     * @return { data: { daysAgo, steps: number[] } }
     */
    @UniJSMethod(uiThread = true)
    public void queryHistoryStepsDetails(JSONObject params, final UniJSCallback callback) {
        final CRPBleConnection conn = requireConnection(callback);
        if (conn == null) {
            return;
        }
        final int daysAgo = params == null || params.getInteger("daysAgo") == null ? 0 : params.getIntValue("daysAgo");
        try {
            final boolean[] done = {false};
            conn.setStepsChangeListener(new CRPStepsChangeListener() {
                @Override
                public void onCurrentSteps(CRPStepsInfo info) {
                }

                @Override
                public void onHistorySteps(CRPHistoryDay day, CRPStepsInfo info) {
                }

                @Override
                public void onHistoryStepsDetails(CRPStepsDetailsInfo info) {
                    if (done[0]) return;
                    if (info == null) {
                        android.util.Log.i("CRP", "onHistoryStepsDetails=null（忽略，继续等有效值）");
                        return;
                    }
                    done[0] = true;
                    JSONArray arr = new JSONArray();
                    int sum = 0;
                    List<Integer> list = info.getStepsList();
                    if (list != null) {
                        for (Integer v : list) {
                            if (v == null) continue;
                            arr.add(v.intValue());
                            sum += v.intValue();
                        }
                    }
                    android.util.Log.i("CRP", "onHistoryStepsDetails daysAgo="
                            + (info.getHistoryDay() == null ? "?" : info.getHistoryDay().ordinal())
                            + " buckets=" + arr.size() + " sum=" + sum);
                    JSONObject data = new JSONObject();
                    data.put("daysAgo", info.getHistoryDay() == null ? daysAgo : info.getHistoryDay().ordinal());
                    data.put("steps", arr);
                    data.put("total", sum);
                    JSONObject out = ok("");
                    out.put("data", data);
                    reply(callback, out);
                }
            });
            conn.queryHistoryStepsDetails(dayOf(daysAgo));
            final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.postDelayed(() -> {
                if (done[0]) return;
                done[0] = true;
                android.util.Log.w("CRP", "查询步数明细 8s 无回调");
                reply(callback, fail("查询步数明细超时（设备未回调）"));
            }, 8000);
        } catch (Throwable t) {
            reply(callback, fail("查询步数明细失败：" + t.getMessage()));
        }
    }

    // ---------------- 固件 ----------------

    @UniJSMethod(uiThread = true)
    public void queryFirmwareVersion(JSONObject params, final UniJSCallback callback) {
        final CRPBleConnection conn = requireConnection(callback);
        if (conn == null) {
            return;
        }
        try {
            conn.queryFirmwareVersion(new CRPFirmwareVersionCallback() {
                @Override
                public void onVersion(String version) {
                    JSONObject out = ok("");
                    out.put("data", version);
                    reply(callback, out);
                }
            });
        } catch (Throwable t) {
            reply(callback, fail("查询固件版本失败：" + t.getMessage()));
        }
    }

    /** 统一的"事件推送"入口，避免各监听里重复拼 JSON */
    private void pushEvent(UniJSCallback callback, String event, Object data) {
        JSONObject out = ok("");
        out.put("event", event);
        if (data != null) {
            out.put("data", data);
        }
        push(callback, out);
    }
}

