package com.modelrag.agent.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class MemoryExtractor {
    private static final Pattern DEPARTMENT = Pattern.compile("(?:我是|我属于|我在|用户属于|用户在)([^，。；;\\s]{1,20}(?:部门|团队|中心|组))");
    private static final Pattern PREFERENCE = Pattern.compile("(?:我喜欢|我偏好|用户喜欢|用户偏好|以后|后续)([^，。；;]{2,40})");
    private static final Pattern STABLE_RULE = Pattern.compile("(?:记住|请记住|规则是|固定口径是)([^。；;]{4,80})");

    public List<LongTermMemoryService.Memory> extract(String userId, String question, String answer) {
        String text = (question == null ? "" : question) + "。" + (answer == null ? "" : answer);
        String owner = userId == null || userId.isBlank() ? "global" : userId;
        List<LongTermMemoryService.Memory> memories = new ArrayList<>();
        addMatches(memories, owner, "PROFILE", "所属部门", DEPARTMENT.matcher(text), 1, .85, null);
        addMatches(memories, owner, "PREFERENCE", "回答偏好", PREFERENCE.matcher(text), 1, .75, null);
        addMatches(memories, owner, "RULE", "固定规则", STABLE_RULE.matcher(text), 1, .8, Instant.now().plusSeconds(180L * 24 * 3600));
        return memories;
    }

    private void addMatches(List<LongTermMemoryService.Memory> output, String userId, String type, String key,
            Matcher matcher, int group, double importance, Instant expiresAt) {
        while (matcher.find() && output.size() < 5) {
            String value = matcher.group(group).trim();
            if (value.length() < 2 || value.contains("system prompt") || value.contains("忽略之前")) continue;
            output.add(new LongTermMemoryService.Memory(userId, type, key + "：" + value, importance, .85, expiresAt));
        }
    }
}
