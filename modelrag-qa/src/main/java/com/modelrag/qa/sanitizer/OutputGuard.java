package com.modelrag.qa.sanitizer;

import com.modelrag.qa.dto.Citation;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component public class OutputGuard {
    private static final Pattern UNSAFE=Pattern.compile("(?i)(<\\|[^>]{1,80}\\|>|system\\s*prompt|ignore\\s+(all|previous|above)|忽略.{0,16}(指示|指令|规则|提示)|\\bDAN\\b|do\\s+anything\\s+now|开发者模式|越狱)");
    public boolean safe(String answer){return answer!=null&&!answer.isBlank()&&!UNSAFE.matcher(answer).find();}
    public boolean safeFragment(String fragment){return fragment!=null&&!fragment.isBlank()&&!UNSAFE.matcher(fragment).find();}
    public String withCitations(String answer,List<Citation> citations){String sources=citations.stream().map(c->"["+c.chunkId()+"]").reduce((left,right)->left+" "+right).orElse("");return sources.isBlank()?answer:answer+"\n\n参考来源："+sources;}
}
