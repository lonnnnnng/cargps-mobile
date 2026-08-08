package com.cargps.mobile

import com.cargps.DashboardRuntime

/**
 * 作者：long
 *
 * Service 的 Android 生命周期只负责编排；运行时、定位启停和权限快照通过这一组
 * 依赖边界注入，设备测试可以复用真实 DashboardRuntime 事件队列，同时隔离系统 GPS
 * 注册本身。生产路径仍由 Service 创建真实依赖，测试工厂只在 instrumentation 中启用。
 */
internal data class TripRecordingServiceDependencies(
    val runtime: DashboardRuntime,
    val startLocation: () -> Boolean,
    val stopLocation: () -> Unit,
    val closeLocation: () -> Unit,
    val readAccessState: () -> TripAccessState,
)
