package com.enterprise.llm.agent;

import java.util.Map;

@FunctionalInterface
public interface ToolHandler {
    String execute(Map<String, Object> arguments) throws Exception;
}
