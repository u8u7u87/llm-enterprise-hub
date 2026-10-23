package com.sample.app.swarm;

import com.enterprise.llm.agent.ToolDefinition;
import com.enterprise.llm.agent.swarm.AgentRole;
import com.enterprise.llm.agent.swarm.AgentSharedContext;
import com.enterprise.llm.agent.swarm.AgentSwarmOrchestrator;
import com.enterprise.llm.client.LlmClient;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatCompletionResponse;
import com.enterprise.llm.model.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sample.app.service.OrderService;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Showcase service demonstrating Multi-Agent Swarm collaboration in a enterprise business app.
 * Automatically triages user customer support requests to specialized agents.
 */
@Service
public class CustomerSupportSwarmService {

    public static final String ROLE_TRIAGE = "TriageAgent";
    public static final String ROLE_LOGISTICS = "LogisticsAgent";

    private final AgentSwarmOrchestrator swarmOrchestrator;
    private final OrderService orderService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CustomerSupportSwarmService(AgentSwarmOrchestrator swarmOrchestrator, OrderService orderService) {
        this.swarmOrchestrator = swarmOrchestrator;
        this.orderService = orderService;
    }

    @PostConstruct
    public void initAgents() {
        // 1. Triage Agent: categorizes user issues and delegates
        AgentRole triageAgent = new AgentRole(
                ROLE_TRIAGE,
                "You are the frontline customer triage assistant. If the user asks about order shipping, tracking, or logistics, hand off to LogisticsAgent.",
                List.of(),
                List.of(ROLE_LOGISTICS)
        );

        // 2. Logistics Specialist Agent: has access to order & shipping tools
        ToolDefinition queryOrderTool = new ToolDefinition(
                "query_order_status",
                "Query current logistics, carrier, and payment status for an enterprise order",
                Map.of("orderId", "String")
        );
        ToolDefinition calcFeeTool = new ToolDefinition(
                "calculate_shipping_fee",
                "Calculate express shipping fee based on distance and weight",
                Map.of("originCity", "String", "destinationCity", "String", "weightKg", "Double")
        );

        AgentRole logisticsAgent = new AgentRole(
                ROLE_LOGISTICS,
                "You are the senior logistics specialist. Use tools to look up delivery status and calculate shipping costs.",
                List.of(queryOrderTool, calcFeeTool),
                List.of()
        );

        logisticsAgent.registerHandler("query_order_status", args -> {
            String orderId = String.valueOf(args.getOrDefault("orderId", "ORD-1001"));
            Map<String, Object> res = orderService.queryOrderStatus(orderId);
            return objectMapper.writeValueAsString(res);
        });

        logisticsAgent.registerHandler("calculate_shipping_fee", args -> {
            String origin = String.valueOf(args.getOrDefault("originCity", "Beijing"));
            String dest = String.valueOf(args.getOrDefault("destinationCity", "Shanghai"));
            Double weight = Double.valueOf(String.valueOf(args.getOrDefault("weightKg", 1.0)));
            Map<String, Object> res = orderService.calculateShippingFee(origin, dest, weight);
            return objectMapper.writeValueAsString(res);
        });

        swarmOrchestrator.registerRole(triageAgent);
        swarmOrchestrator.registerRole(logisticsAgent);
    }

    public ChatCompletionResponse handleInquiry(LlmClient llmClient, String userPrompt) {
        AgentSharedContext context = new AgentSharedContext();
        ChatCompletionRequest request = new ChatCompletionRequest(
                "qwen2.5:0.5b",
                List.of(ChatMessage.user(userPrompt)),
                false,
                0.7
        );
        return swarmOrchestrator.runSwarm(llmClient, ROLE_TRIAGE, request, context, "sample-tenant", 5);
    }
}
