package com.inknote.pad.ink

/**
 * 一个采样点。
 *
 * 为什么全用 Float 不用 Double：
 * 一页可能有几十万个采样点，Double 让内存直接翻倍，而屏幕坐标
 * 1/256 px 的精度就已经远超手感和肉眼所需。
 *
 * 为什么带 pressure：
 * 压力是笔迹的一部分，不是"渲染时可选的装饰"。如果先做常量宽度、
 * 以后再补压力字段，所有几何代码都要改一遍，数据格式也要迁移。
 *
 * 为什么带 timeMillis：
 * 速度调制（写得快的地方自动变细）需要两点之间的时间差才能算速度。
 * 光有位移不够 —— 采样率会变（120Hz/240Hz 切换），位移大不代表写得快。
 * 给了默认值 0，是为了让只关心几何的调用方（比如单元测试）不必填。
 */
data class InkPoint(
    val x: Float,
    val y: Float,
    /** 归一化压感 0..1。手指输入时通常恒为 1.0 */
    val pressure: Float,
    /** 事件时间（ms），来自 MotionEvent.getEventTime() */
    val timeMillis: Long = 0L,
)
