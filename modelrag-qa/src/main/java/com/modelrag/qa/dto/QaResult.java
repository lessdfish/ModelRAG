package com.modelrag.qa.dto;
import java.util.List;
public record QaResult(String answer, List<Citation> citations, double confidence, boolean refused, String traceId) { }
