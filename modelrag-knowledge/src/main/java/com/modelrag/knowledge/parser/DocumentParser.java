package com.modelrag.knowledge.parser;
public interface DocumentParser { boolean supports(String fileName); String parse(byte[] content) throws Exception; String name(); }
