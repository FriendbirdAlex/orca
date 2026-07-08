package com.orca.common.context;

/**
 * 租户信息(鉴权后注入 TenantContext)。
 *
 * 面试点: 为什么放 orca-common 而非 orca-gateway?
 *  → 拦截器在 orca-api 设置, 网关服务在 orca-gateway 读取, 放 common 避免两模块循环依赖。
 *
 * 为什么用 record? → 不可变值对象, 天然线程安全, Java21 亮点。
 */
public record TenantInfo(
        long tenantId,
        String tenantCode,
        String apiKey,
        Long apiKeyId,
        int rpm,         // 每分钟请求数上限
        int tpm,         // 每分钟 token 上限
        long dailyQuota  // 每日 token 配额
) {
}
