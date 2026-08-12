package com.modelrag.agent.trace;
public record ToolCallTrace(String traceId,String toolName,String params,String output,boolean success,String error,long latencyMs) {
    public ToolCallTrace(String traceId,String toolName,String params,boolean success,String error,long latencyMs){this(traceId,toolName,params,"{}",success,error,latencyMs);}
}
