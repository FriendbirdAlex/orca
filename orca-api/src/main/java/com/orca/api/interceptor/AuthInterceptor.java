package com.orca.api.interceptor;

import com.orca.common.context.TenantContext;
import com.orca.common.context.TenantInfo;
import com.orca.common.exception.BizException;
import com.orca.common.exception.ErrorCode;
import com.orca.gateway.service.TenantService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * API Key 鉴权拦截器。
 * 仅拦 /v1/**; preHandle 解析 X-API-Key → TenantContext.set; afterCompletion 清理。
 *
 * 面试点: ThreadLocal 生命周期 → 必须在 afterCompletion 清理, 否则线程池复用会串租户(内存泄漏 + 越权)。
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final TenantService tenantService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String apiKey = request.getHeader("X-API-Key");
        if (apiKey == null || apiKey.isBlank()) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "缺少 X-API-Key 请求头");
        }
        TenantInfo info = tenantService.resolveByApiKey(apiKey);
        TenantContext.set(info);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 无论成功失败都清理, 防线程池复用串租户
        TenantContext.clear();
    }
}
