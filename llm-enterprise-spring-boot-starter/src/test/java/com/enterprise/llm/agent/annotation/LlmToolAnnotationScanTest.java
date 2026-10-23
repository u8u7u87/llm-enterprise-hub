package com.enterprise.llm.agent.annotation;

import com.enterprise.llm.agent.ToolDefinition;
import com.enterprise.llm.agent.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LlmToolAnnotationScanTest {

    private ToolRegistry toolRegistry;
    private LlmToolBeanPostProcessor processor;

    @Component
    static class SampleOrderService {

        @LlmTool(name = "query_order", description = "Query order status by order ID")
        public String queryOrder(
                @LlmToolParam(name = "order_id", description = "The unique order ID", required = true) String orderId
        ) {
            return "Order " + orderId + " has been delivered";
        }

        @LlmTool(description = "Calculate total price with discount")
        public double calculatePrice(
                @LlmToolParam(name = "unit_price", description = "Price per unit") double unitPrice,
                @LlmToolParam(name = "quantity", description = "Quantity") int quantity,
                @LlmToolParam(name = "is_vip", description = "Is VIP customer", required = false) boolean isVip
        ) {
            double total = unitPrice * quantity;
            return isVip ? total * 0.9 : total;
        }

        @LlmTool(name = "lookup_inventory")
        public Map<String, Object> lookupInventory(
                @LlmToolParam(name = "sku") String sku
        ) {
            return Map.of("sku", sku, "in_stock", true, "stock_count", 42);
        }
    }

    @BeforeEach
    void setUp() {
        toolRegistry = new ToolRegistry();
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("toolRegistry", toolRegistry);

        processor = new LlmToolBeanPostProcessor(beanFactory.getBeanProvider(ToolRegistry.class));
    }

    @Test
    @DisplayName("Should scan and register @LlmTool methods into ToolRegistry with JSON Schema")
    void testAnnotationScanningAndExecution() throws Exception {
        SampleOrderService service = new SampleOrderService();
        processor.postProcessAfterInitialization(service, "sampleOrderService");

        // Verify tool registration
        assertThat(toolRegistry.hasTool("query_order")).isTrue();
        assertThat(toolRegistry.hasTool("calculatePrice")).isTrue();
        assertThat(toolRegistry.hasTool("lookup_inventory")).isTrue();

        // 1. Verify 'query_order' schema & execution
        Optional<ToolRegistry.RegisteredTool> orderTool = toolRegistry.getTool("query_order");
        assertThat(orderTool).isPresent();
        ToolDefinition orderDef = orderTool.get().definition();
        assertThat(orderDef.description()).isEqualTo("Query order status by order ID");

        Map<String, Object> params = orderDef.parameters();
        assertThat(params.get("type")).isEqualTo("object");
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) params.get("properties");
        assertThat(props).containsKey("order_id");
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) params.get("required");
        assertThat(required).contains("order_id");

        String orderResult = orderTool.get().handler().execute(Map.of("order_id", "ORD-12345"));
        assertThat(orderResult).isEqualTo("Order ORD-12345 has been delivered");

        // 2. Verify 'calculatePrice' with type conversions
        Optional<ToolRegistry.RegisteredTool> calcTool = toolRegistry.getTool("calculatePrice");
        assertThat(calcTool).isPresent();
        String calcResult = calcTool.get().handler().execute(Map.of(
                "unit_price", 100.0,
                "quantity", 2,
                "is_vip", true
        ));
        // Total = 100 * 2 * 0.9 = 180.0
        assertThat(Double.parseDouble(calcResult)).isEqualTo(180.0);

        // 3. Verify 'lookup_inventory' returning complex object serialized to JSON
        Optional<ToolRegistry.RegisteredTool> invTool = toolRegistry.getTool("lookup_inventory");
        assertThat(invTool).isPresent();
        String invResult = invTool.get().handler().execute(Map.of("sku", "ITEM-99"));
        assertThat(invResult).contains("\"sku\":\"ITEM-99\"");
        assertThat(invResult).contains("\"stock_count\":42");
    }
}
