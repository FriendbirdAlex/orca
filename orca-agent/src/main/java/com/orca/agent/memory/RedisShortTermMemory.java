package com.orca.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orca.agent.react.ReActStep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Redis 短期记忆实现。
 * key: orca:agent:mem:{runId}, List 结构, TTL 1h。
 * Redis 不可用时降级(返回空历史), 不阻塞 ReAct(只是丢失历史 observation)。
 */
@Slf4j
@Primary
@Component
@RequiredArgsConstructor
public class RedisShortTermMemory implements ShortTermMemory {

    private static final String KEY_PREFIX = "orca:agent:mem:";
    private static final Duration TTL = Duration.ofHours(1);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Override
    public void append(String runId, ReActStep step) {
        try {
            redis.opsForList().rightPush(KEY_PREFIX + runId, objectMapper.writeValueAsString(step));
            redis.expire(KEY_PREFIX + runId, TTL);
        } catch (Exception e) {
            log.warn("[memory] Redis 写入失败, 降级(丢历史) runId={}", runId, e);
        }
    }

    @Override
    public List<ReActStep> load(String runId) {
        try {
            List<String> raw = redis.opsForList().range(KEY_PREFIX + runId, 0, -1);
            if (raw == null || raw.isEmpty()) return List.of();
            return raw.stream()
                    .map(s -> {
                        try { return objectMapper.readValue(s, ReActStep.class); }
                        catch (Exception e) { return null; }
                    })
                    .filter(java.util.Objects::nonNull)
                    .toList();
        } catch (Exception e) {
            log.warn("[memory] Redis 读取失败, 降级空历史 runId={}", runId, e);
            return List.of();
        }
    }

    @Override
    public void clear(String runId) {
        try { redis.delete(KEY_PREFIX + runId); }
        catch (Exception ignored) {}
    }
}
