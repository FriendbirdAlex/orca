package com.orca.gateway.controller;

import com.orca.common.result.Result;
import com.orca.gateway.billing.ReconciliationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 计费对账手动触发接口(验证用, 生产可加权限)。
 * 注意: 此接口在 /v1/** 之外, 不受鉴权拦截器影响(便于测试)。
 */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class ReconciliationController {

    private final ReconciliationService reconciliationService;

    @PostMapping("/reconcile")
    public Result<ReconciliationService.ReconciliationResult> reconcile(
            @RequestParam(required = false) String period) {
        LocalDate date = (period == null || period.isBlank())
                ? LocalDate.now() : LocalDate.parse(period);
        return Result.success(reconciliationService.reconcileNow(date));
    }
}
