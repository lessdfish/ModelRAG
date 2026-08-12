package com.modelrag.search.rewrite;

import com.modelrag.search.dto.QueryExtensionResult;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class QueryRewriter {
    private static final Pattern LATIN_TOKEN = Pattern.compile("[a-zA-Z0-9_\\-]{2,}");
    private static final Set<String> STOP_PAIRS = Set.of("请问", "帮我", "一下", "是否", "可以", "能够", "什么", "多少", "几天", "如何", "怎么", "的是");

    public QueryExtensionResult expand(String query) {
        String original = query == null ? "" : query.trim();
        String rewritten = original.replaceAll("[\\s\\u00A0]+", " ").trim();
        LinkedHashSet<String> searches = new LinkedHashSet<>();
        add(searches, rewritten);
        String compact = compact(rewritten);
        add(searches, keywords(rewritten));
        add(searches, denseTerms(compact));
        add(searches, intentTerms(compact));
        add(searches, latinTokens(rewritten));
        add(searches, synonymTerms(compact));
        String keywords = keywords(rewritten);
        if (!keywords.isBlank() && !intentTerms(compact).isBlank()) add(searches, keywords + " " + intentTerms(compact));
        List<String> limited = searches.stream().filter(value -> !value.isBlank()).limit(5).toList();
        return new QueryExtensionResult(original, rewritten, limited.isEmpty() ? List.of(original) : limited, limited.isEmpty() ? rewritten : limited.get(0));
    }

    private void add(LinkedHashSet<String> values, String value) {
        if (value != null && !value.isBlank()) values.add(value.trim());
    }

    private String keywords(String query) {
        return query.replaceAll("(请问|请|帮我|一下|多少|几天|什么|如何|怎么|是否|可以|能够|吗|呢|的|了|一个|一下)", " ")
                .replaceAll("[\\s，。！？、：:；;（）()]+", " ")
                .trim();
    }

    private String compact(String query) {
        return query.replaceAll("[\\s，。！？、：:；;（）()【】\\[\\]#*`]+", "");
    }

    private String denseTerms(String compact) {
        LinkedHashSet<String> pairs = new LinkedHashSet<>();
        for (int i = 0; i + 1 < compact.length(); i++) {
            String pair = compact.substring(i, i + 2);
            if (!STOP_PAIRS.contains(pair)) pairs.add(pair);
        }
        return String.join(" ", pairs);
    }

    private String intentTerms(String compact) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        if (compact.matches(".*(几天|多少|多久|多长|期限|时长|有效期).*")) addAll(terms, "天数", "期限", "时长", "有效期", "最长");
        if (compact.matches(".*(审批|批准|确认|负责人|主管|谁批).*")) addAll(terms, "审批", "确认", "负责人", "主管", "批准");
        if (compact.matches(".*(删除|变更|修改|撤销|回收).*")) addAll(terms, "删除", "变更", "修改", "回收", "操作记录");
        if (compact.matches(".*(流程|步骤|怎么|如何|申请|办理).*")) addAll(terms, "流程", "步骤", "申请", "办理", "材料");
        return String.join(" ", terms);
    }

    private String synonymTerms(String compact) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        if (compact.contains("年假")) addAll(terms, "年假", "带薪休假", "休假", "年度假期");
        if (compact.contains("远程办公")) addAll(terms, "远程办公", "居家办公", "远程", "办公确认");
        if (compact.contains("临时访问") || compact.contains("机密")) addAll(terms, "临时访问", "机密级数据", "数据访问", "自动回收");
        return String.join(" ", terms);
    }

    private String latinTokens(String query) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        Matcher matcher = LATIN_TOKEN.matcher(query == null ? "" : query.toLowerCase());
        while (matcher.find()) tokens.add(matcher.group());
        return String.join(" ", tokens);
    }

    private void addAll(LinkedHashSet<String> values, String... terms) {
        for (String term : terms) values.add(term);
    }
}
