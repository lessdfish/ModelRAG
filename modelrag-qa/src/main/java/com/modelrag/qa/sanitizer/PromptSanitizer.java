package com.modelrag.qa.sanitizer;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component public class PromptSanitizer {
    private static final Pattern ATTACK=Pattern.compile("(?i)(ignore\\s+(all|previous|above).{0,24}(instruction|prompt|rule)?|忽略.{0,16}(之前|前面|以上|所有).{0,12}(指示|指令|规则|提示)|你.{0,8}(现在|从现在开始).{0,8}是|system\\s*prompt|<\\|(?:SYSTEM|CONTEXT|IMPORTANT)\\|>|\\bDAN\\b|do\\s+anything\\s+now|开发者模式|越狱)");
    public String sanitize(String input){if(input==null)return "";return ATTACK.matcher(input).replaceAll("[已过滤的指令文本]").replace("<|","&lt;|");}
}
