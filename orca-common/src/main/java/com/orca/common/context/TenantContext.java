package com.orca.common.context;

/**
 * 租户上下文持有者(ThreadLocal)。
 *
 * 面试点: ThreadLocal 在 Reactor/异步线程下不传递
 *  → 同步段(拦截器/Service.chat)直接 get() 可用;
 *  → 流式段(Flux 内的 Reactor 线程)必须在返回 Flux 前把 tenantId 显式捕获进闭包, 不能在 Flux 内 get()。
 *  → 虚拟线程下 ThreadLocal 行为: 每个虚拟线程独立副本, 不跨虚拟线程, 需注意继承语义(CarrierThread 不自动 InheritableThreadLocal)。
 *
 * 生命周期: 拦截器 preHandle set → afterCompletion clear, 防内存泄漏(尤其线程池复用)。
 */
public final class TenantContext {

    private static final ThreadLocal<TenantInfo> HOLDER = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(TenantInfo info) {
        HOLDER.set(info);
    }

    public static TenantInfo get() {
        return HOLDER.get();
    }

    public static TenantInfo require() {
        TenantInfo info = HOLDER.get();
        if (info == null) {
            throw new IllegalStateException("TenantContext 未初始化, 请检查鉴权拦截器是否生效");
        }
        return info;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
