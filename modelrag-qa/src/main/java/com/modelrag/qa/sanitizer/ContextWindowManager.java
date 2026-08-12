package com.modelrag.qa.sanitizer;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component public class ContextWindowManager {
    private static final int CHARS_PER_TOKEN=4;
    public String fit(List<String> chunks,int maxTokens){int remaining=maxTokens*CHARS_PER_TOKEN;List<String> fitted=new ArrayList<>();for(String chunk:chunks){int separator=fitted.isEmpty()?0:5;if(remaining<=separator)break;remaining-=separator;String value=chunk.length()<=remaining?chunk:chunk.substring(0,remaining);fitted.add(value);remaining-=value.length();}return String.join("\n---\n",fitted);}
}
