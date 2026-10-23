package com.sample.app.controller;

import com.enterprise.llm.agent.AgentToolOrchestrator;
import com.enterprise.llm.agent.ToolDefinition;
import com.enterprise.llm.agent.ToolRegistry;
import com.enterprise.llm.context.ContextManager;
import com.enterprise.llm.model.ChatCompletionRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.Map;

@RestController
@RequestMapping("/api/assistant")
public class DemoAssistantController {

    private final ToolRegistry toolRegistry;
    private final AgentToolOrchestrator orchestrator;
    private final ContextManager contextManager;

    public DemoAssistantController(
            ToolRegistry toolRegistry,
            AgentToolOrchestrator orchestrator,
            ContextManager contextManager
    ) {
        this.toolRegistry = toolRegistry;
        this.orchestrator = orchestrator;
        this.contextManager = contextManager;
    }

    @GetMapping("/tools")
    public ResponseEntity<Collection<ToolDefinition>> listTools() {
        return ResponseEntity.ok(toolRegistry.getAllDefinitions());
    }

    @PostMapping("/tool-execute")
    public ResponseEntity<Object> executeTool(
            @RequestParam String toolName,
            @RequestBody Map<String, Object> arguments
    ) throws Exception {
        var registered = toolRegistry.getTool(toolName);
        if (registered.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(registered.get().handler().execute(arguments));
    }

    @PostMapping("/compress-context")
    public ResponseEntity<ChatCompletionRequest> compressContext(
            @RequestBody ChatCompletionRequest request
    ) {
        ChatCompletionRequest result = contextManager.manageContext(request);
        return ResponseEntity.ok(result);
    }
}
