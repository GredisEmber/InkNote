package com.inknote.pad.ink

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 手写笔侧键的点击事件。
 *
 * ============================ 为什么是"点击计数"而不是"按下状态" ============================
 * 侧键走的不是笔尖的 digitizer（NVTCapacitivePen 虽然在能力表里声明了
 * BTN_STYLUS / BTN_STYLUS2，但两万多行原始事件里一次都没发过），
 * 而是笔的蓝牙按键设备 "Lenovo Tab Pen Plus Consumer Control"。
 * 联想为它写了专属 keylayout：
 *
 *   /system/usr/keylayout/Vendor_17ef_Product_617f.kl
 *     key usage 0x0c0600   PEN_ONE_CLICK      → Android keyCode 600
 *     key usage 0x0c0601   PEN_TWO_CLICK      → 601
 *     key usage 0x0c0602   PEN_THREE_CLICK    → 602
 *     key usage 0x0c0603   PEN_LONG_CLICK     → 603
 *     key usage 0x0c0604   PEN_PRESS_CLICK    → 604
 *     key usage 0x0c0605   PEN_BT_DISCONNECT  → 605
 *
 * 实测（logcat 标签 InkPenKey）发现：**系统只把 ACTION_UP 交给应用**。
 * ACTION_DOWN 被联想的 BluetoothPenInputPolicy 在系统层消费掉了，
 * 它会发一个 lenovo.intent.action.INPUT_DEVICE_CLICK_STATE_CHANGED 广播。
 * 系统日志原文：
 *   D BluetoothPenInputPolicy: processStylusPenKeyEvent: keycode 600, ...
 *   D BluetoothPenInputPolicy: send broadcast stylus pen Click Event intent ...
 *
 * 结论：**"按住侧键临时切橡皮"在这台设备上做不到**（没有 DOWN 就没有"按住"），
 * 但"单击切换"完全可行。所以这里暴露的是点击计数而不是按下状态。
 */
object StylusButtonState {

    private val _clickCount = MutableStateFlow(0)

    /**
     * 累计点击次数。
     *
     * 用 StateFlow 而不是简单的 volatile 标志，是因为消费方是 Compose ——
     * 它需要"事件发生了"这个信号来驱动重组，而不是"当前是否按住"这个状态。
     */
    val clickCount: StateFlow<Int> = _clickCount.asStateFlow()

    fun onPenClick() {
        _clickCount.value += 1
    }
}
