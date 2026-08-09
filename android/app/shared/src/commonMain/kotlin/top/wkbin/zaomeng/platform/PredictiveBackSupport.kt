package top.wkbin.zaomeng.platform

/**
 * 当前平台是否支持"预测性返回手势"开关（参考 KernelSU）。
 *
 * 仅 Android 14+ 支持运行时切换；桌面/iOS 无此概念，恒为 false，设置页不显示该开关。
 */
expect fun predictiveBackSupported(): Boolean
