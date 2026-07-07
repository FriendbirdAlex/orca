package com.orca.api.controller;

import com.orca.common.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查 / 冒烟接口。
 * /api/health 返回应用名+时间, 用于验证接入层闭环; actuator/health 用于 K8s 探针。
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("app", "orca-api");
        info.put("status", "UP");
        info.put("timestamp", LocalDateTime.now().toString());
        return Result.success(info);
    }
}
