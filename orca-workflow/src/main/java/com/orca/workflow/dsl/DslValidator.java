package com.orca.workflow.dsl;

import com.orca.common.exception.BizException;
import com.orca.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DSL 校验器: 节点非空、id 唯一、边端点存在、无环、无孤儿。
 *
 * 面试点(拓扑排序判环 — Kahn 算法):
 *  入度=0 的节点入队 BFS, 每出队一个节点, 其后继入度-1, 入度归 0 入队。
 *  若遍历节点数 != 总节点数 → 存在环(环内节点入度永远不为 0)。
 *  对比 DFS 三色标记法: Kahn 更直观, 还能输出拓扑序。
 */
@Component
public class DslValidator {

    public void validate(WorkflowDsl dsl) {
        if (dsl == null || dsl.getNodes() == null || dsl.getNodes().isEmpty()) {
            throw new BizException(ErrorCode.WF_INVALID_DSL, "节点为空");
        }
        // 节点 id 唯一性
        Set<String> ids = new HashSet<>();
        for (NodeDef n : dsl.getNodes()) {
            if (n.getId() == null || n.getId().isBlank()) {
                throw new BizException(ErrorCode.WF_INVALID_DSL, "节点 id 不能为空");
            }
            if (n.getType() == null) {
                throw new BizException(ErrorCode.WF_INVALID_DSL, "节点 type 不能为空: " + n.getId());
            }
            if (!ids.add(n.getId())) {
                throw new BizException(ErrorCode.WF_INVALID_DSL, "重复节点 id: " + n.getId());
            }
        }

        // 邻接表 + 入度
        Map<String, List<String>> adj = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        for (String id : ids) {
            adj.put(id, new ArrayList<>());
            inDegree.put(id, 0);
        }
        if (dsl.getEdges() != null) {
            for (EdgeDef e : dsl.getEdges()) {
                if (e.getSource() == null || e.getTarget() == null
                        || !ids.contains(e.getSource()) || !ids.contains(e.getTarget())) {
                    throw new BizException(ErrorCode.WF_INVALID_DSL,
                            "边端点不存在: " + (e.getSource() == null ? "null" : e.getSource()) + "->" + e.getTarget());
                }
                adj.get(e.getSource()).add(e.getTarget());
                inDegree.merge(e.getTarget(), 1, Integer::sum);
            }
        }

        // Kahn 拓扑排序判环
        Deque<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> en : inDegree.entrySet()) {
            if (en.getValue() == 0) queue.add(en.getKey());
        }
        int visited = 0;
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            visited++;
            for (String nb : adj.get(cur)) {
                if (inDegree.merge(nb, -1, Integer::sum) == 0) queue.add(nb);
            }
        }
        if (visited != ids.size()) {
            throw new BizException(ErrorCode.WF_INVALID_DSL, "DSL 存在环(无法拓扑排序)");
        }
    }
}
