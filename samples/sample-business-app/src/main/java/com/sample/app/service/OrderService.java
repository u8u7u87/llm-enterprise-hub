package com.sample.app.service;

import com.enterprise.llm.agent.annotation.LlmTool;
import com.enterprise.llm.agent.annotation.LlmToolParam;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class OrderService {

    @LlmTool(
            name = "query_order_status",
            description = "Query current logistics, carrier, and payment status for an enterprise order"
    )
    public Map<String, Object> queryOrderStatus(
            @LlmToolParam(name = "orderId", description = "The unique order ID (e.g. ORD-1001)", required = true)
            String orderId
    ) {
        Map<String, Object> result = new HashMap<>();
        result.put("orderId", orderId);
        result.put("status", "IN_TRANSIT");
        result.put("carrier", "SF-Express");
        result.put("estimatedDeliveryDays", 2);
        return result;
    }

    @LlmTool(
            name = "calculate_shipping_fee",
            description = "Calculate express shipping fee based on distance and weight"
    )
    public Map<String, Object> calculateShippingFee(
            @LlmToolParam(name = "originCity", description = "Origin city", required = true)
            String originCity,
            @LlmToolParam(name = "destinationCity", description = "Destination city", required = true)
            String destinationCity,
            @LlmToolParam(name = "weightKg", description = "Package weight in kilograms", required = true)
            Double weightKg
    ) {
        double baseFee = 12.0;
        double weightFee = (weightKg != null && weightKg > 1.0) ? (weightKg - 1.0) * 5.0 : 0.0;
        Map<String, Object> feeInfo = new HashMap<>();
        feeInfo.put("origin", originCity);
        feeInfo.put("destination", destinationCity);
        feeInfo.put("totalFeeCny", baseFee + weightFee);
        feeInfo.put("currency", "CNY");
        return feeInfo;
    }
}
