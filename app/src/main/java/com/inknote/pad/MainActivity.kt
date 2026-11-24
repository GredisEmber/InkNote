package com.inknote.pad

import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.inknote.pad.data.InkRepositoryProvider
import com.inknote.pad.ink.StylusButtonState
import com.inknote.pad.ui.nav.InkNavHost
import com.inknote.pad.ui.theme.InkNoteTheme
import kotlinx.coroutines.runBlocking

/**
 * 唯一的 Activity。
 *
 * 整个 App 只有这一个 Activity：手写画布是 Compose 里通过 AndroidView
 * 嵌入的自定义 View，不需要为它单开 Activity。少一层 Activity 切换，
 * 从列表进编辑页就没有「黑一下」的过渡，对手写 App 的体感很重要。
 *
 * 它还额外承担一件事：**接收手写笔侧键**。
 * 原因见 [dispatchKeyEvent]。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // 必须在 super.onCreate() 之前调用，否则第一帧会被系统栏盖住
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            InkNoteTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    InkNavHost()
                }
            }
        }
    }

    /**
     * 接收手写笔侧键。
     *
     * ============================ 为什么要绕到 Activity 层 ============================
     * 侧键**不走笔尖的 digitizer** —— NVTCapacitivePen 虽然在能力表里声明了
     * BTN_STYLUS / BTN_STYLUS2，但两万多行原始事件里一次都没发过。
     * 它实际走的是笔的蓝牙按键设备 "Lenovo Tab Pen Plus Consumer Control"，
     * 联想为它写了专属 keylayout（/system/usr/keylayout/Vendor_17ef_Product_617f.kl），
     * 把 HID usage 0x0c060N 映射成 Android keyCode 60N。
     *
     * ============================ 只处理 ACTION_UP ============================
     * 实测发现系统只把 UP 交给应用，DOWN 被联想的 BluetoothPenInputPolicy
     * 在系统层消费掉（它会发 INPUT_DEVICE_CLICK_STATE_CHANGED 广播）。
     * 所以这里只认 UP —— 写成"DOWN 置位、UP 清位"会永远等不到 DOWN，功能静默失效。
     *
     * 放在 Activity 而不是 View 里，是因为按键事件只会送到有焦点的窗口，
     * 我们的画布 View 并不持有焦点。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && shouldToggleTool(event)) {
            StylusButtonState.onPenClick()
            Log.d(
                TAG,
                "pen click: keyCode=${event.keyCode} deviceId=${event.deviceId}",
            )
        }
        // 刻意不吞掉事件：系统在 DOWN 时已经处理过一轮（BluetoothPenInputPolicy
        // 会发自己的广播）。我们再把 UP 吃掉，它对按键状态的跟踪会停在"按下"，
        // 有可能引发卡键。未知键码传到下面不会被任何东西消费，是安全的。
        return super.dispatchKeyEvent(event)
    }

    /**
     * 这次按键是否应该切换工具。
     *
     * 判断条件是"键码白名单 **或** 事件来自那支笔"，两条取或：
     *
     *  · **键码**：联想把 HID usage 0x0c060N 映射成 Android keyCode 60N
     *    （见 /system/usr/keylayout/Vendor_17ef_Product_617f.kl）。
     *    这些键码是这支笔独有的，别的设备不会产生。
     *  · **设备名**：事件来自名为 "...Pen..." 的输入设备。
     *
     * 为什么两条都要：只认键码的话，联想改固件/改映射就会静默失效；
     * 只认设备名的话，通过 adb 注入的合成事件（虚拟设备）没法验证。
     * 取或之后鲁棒性和可测性都拿到。
     *
     * 双击、三击、蓝牙断开刻意排除：用户手快连点两下时，
     * 两次切换会互相抵消，看起来像"按了没反应"。
     */
    private fun shouldToggleTool(event: KeyEvent): Boolean = when (event.keyCode) {
        KEY_PEN_TWO_CLICK, KEY_PEN_THREE_CLICK, KEY_PEN_BT_DISCONNECT -> false
        else -> isSideButtonKey(event.keyCode) || isFromPenDevice(event)
    }

    private fun isFromPenDevice(event: KeyEvent): Boolean {
        val device = InputDevice.getDevice(event.deviceId) ?: return false
        return device.name?.contains("Pen", ignoreCase = true) == true
    }

    override fun onStop() {
        super.onStop()
        // 尽力把还没落盘的笔迹写掉。
        //
        // 这里用 runBlocking 是刻意的：onStop 之后进程随时可能被系统杀掉，
        // 而 flushAsync 是"排个队就返回"，很可能还没轮到执行就没了。
        // 一页笔记只有几十到几百 KB，阻塞主线程几毫秒换"数据一定在"是划算的。
        runBlocking { InkRepositoryProvider.repository.flush() }
    }

    private companion object {
        const val TAG = "InkPenKey"

        /**
         * 侧键键码。来自联想 keylayout 的私有映射：
         * HID usage 0x0c060N → Android keyCode 60N。
         */
        const val KEY_PEN_ONE_CLICK = 600
        const val KEY_PEN_TWO_CLICK = 601
        const val KEY_PEN_THREE_CLICK = 602
        const val KEY_PEN_LONG_CLICK = 603
        const val KEY_PEN_PRESS_CLICK = 604
        const val KEY_PEN_BT_DISCONNECT = 605

        fun isSideButtonKey(keyCode: Int): Boolean = when (keyCode) {
            KEY_PEN_ONE_CLICK, KEY_PEN_LONG_CLICK, KEY_PEN_PRESS_CLICK -> true
            else -> false
        }
    }
}
