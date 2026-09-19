package com.toaster.noad.core.service.shizuku;

/**
 * Shizuku UserService 的进程间接口（R11 / 阶段 F2）。
 *
 * ## 为什么只有一个方法
 *
 * 用户服务进程以 shell 身份（UID 2000）运行，唯一职责是代替本应用
 * 执行特权 shell 命令（当前仅 `cmd appops ...`，见
 * [RestrictedSettingsOps]）。返回 stdout+stderr 合并文本
 * （redirectErrorStream），**解析在主进程的纯函数层完成**——
 * 服务内不做任何决策，保证匹配/解析逻辑可以在 JVM 单测中验证。
 *
 * ## 稳定性
 *
 * 本接口归本项目所有（非框架 AIDL），方法永不增删，
 * 不存在「跨 Android 版本事务码漂移」问题；
 * op 数值漂移问题则由「只传 op 字符串名」规避。
 */
interface IShellCommandService {

    /**
     * 以 shell 身份执行一条命令。
     *
     * 实现保证不抛异常、不超时挂死（内部 10s 超时兜底），
     * 失败信息以带标记的文本并入返回值。
     */
    String exec(in String[] args);
}
