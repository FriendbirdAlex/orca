package com.orca.gateway.quota;

/**
 * 配额管理接口。
 * 面试点: 预扣(reserve)防超卖 + 结算退回(refund)最终一致; Redis 热路径 + DB 源记录。
 */
public interface QuotaManager {

    /** 预扣 tokens 配额, 返回是否成功 + 剩余 */
    QuotaResult reserve(long tenantId, int tokens, long dailyLimit);

    /** 结算退回 tokens 配额(预扣多余时调用), dailyLimit 用于落 DB 源记录 */
    long refund(long tenantId, int tokens, long dailyLimit);

    /** 查询当前已消费(用于对账/展示) */
    long consumed(long tenantId);
}
