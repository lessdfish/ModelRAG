package com.modelrag.toolgateway.execution;

/** Inversion point for explicitly registered in-process tools. */
public interface InternalToolHandler {
    boolean supports(String toolName);

    String invoke(ToolInvocation invocation);
}
