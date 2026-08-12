package com.modelrag.qa.sanitizer;

import org.springframework.stereotype.Component;

@Component public class ContextSanitizer {
    private final PromptSanitizer prompts;
    public ContextSanitizer(PromptSanitizer prompts){this.prompts=prompts;}
    public String sanitize(String context){return prompts.sanitize(context);}
}
