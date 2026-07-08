package com.orca.gateway.provider;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 粗略 token 估算: 英文 ~4 chars/token。
 * 中文偏保守(实际 ~1.5 chars/token), 粗估偏大 → 预扣偏保守, 安全。
 */
@Primary
@Component
public class MockTokenEstimator implements TokenEstimator {

    private static final int CHARS_PER_TOKEN = 4;

    @Override
    public int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, text.length() / CHARS_PER_TOKEN);
    }
}
