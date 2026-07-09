package com.orca.workflow.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.orca.workflow.dsl.NodeDef;
import com.orca.workflow.dsl.NodeType;
import com.orca.workflow.engine.InstanceContext;
import com.orca.workflow.engine.entity.NodeInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * HTTP 节点执行器: 调外部接口(RestTemplate)。
 * config: {url, method, headers, body, compensateUrl?}
 * 失败直接抛异常(由 DagExecutor 捕获触发 Saga 补偿; P3 不做节点级重试)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpNodeExecutor implements NodeExecutor {

    private final RestTemplate restTemplate;

    @Override
    public NodeType supportType() {
        return NodeType.HTTP;
    }

    @Override
    public Map<String, Object> execute(NodeDef node, InstanceContext ctx) throws Exception {
        JsonNode cfg = node.getConfig();
        String url = ctx.render(cfg.path("url").asText());
        String method = cfg.path("method").asText("GET").toUpperCase();
        String body = cfg.has("body") ? ctx.render(cfg.get("body").asText()) : null;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (cfg.has("headers") && cfg.get("headers").isObject()) {
            cfg.get("headers").fields().forEachRemaining(e ->
                    headers.add(e.getKey(), ctx.render(e.getValue().asText())));
        }

        HttpEntity<String> entity = new HttpEntity<>(body, headers);
        log.info("[http-node] 执行 node={} {} {}", node.getId(), method, url);
        ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.valueOf(method), entity, String.class);

        Map<String, Object> output = new HashMap<>();
        output.put("status", resp.getStatusCode().value());
        output.put("body", resp.getBody());
        if (resp.getStatusCode().isError()) {
            throw new RuntimeException("HTTP 节点返回错误状态: " + resp.getStatusCode().value());
        }
        return output;
    }

    @Override
    public void compensate(NodeDef node, InstanceContext ctx, NodeInstance ni) {
        // HTTP 补偿: 若 config 有 compensateUrl, 调用回滚接口; 否则仅记录
        JsonNode cfg = node.getConfig();
        if (cfg != null && cfg.has("compensateUrl")) {
            String url = ctx.render(cfg.get("compensateUrl").asText());
            log.info("[http-node] 补偿调回滚接口 node={} url={}", node.getId(), url);
            try {
                restTemplate.exchange(url, HttpMethod.DELETE, null, String.class);
            } catch (Exception e) {
                log.warn("[http-node] 补偿调用失败 node={} url={}", node.getId(), url, e);
            }
        }
    }
}
