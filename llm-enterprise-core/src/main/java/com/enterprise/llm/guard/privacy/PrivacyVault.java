package com.enterprise.llm.guard.privacy;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 请求/会话级双向脱敏字典保险库 (Privacy Vault)
 * 保存真实敏感数据与语义占位符的双向映射关系，线程安全。
 */
public class PrivacyVault {

    private final Map<String, String> placeholderToOriginal = new ConcurrentHashMap<>();
    private final Map<String, String> originalToPlaceholder = new ConcurrentHashMap<>();

    private final AtomicInteger phoneCounter = new AtomicInteger(1);
    private final AtomicInteger emailCounter = new AtomicInteger(1);
    private final AtomicInteger idCardCounter = new AtomicInteger(1);
    private final AtomicInteger genericCounter = new AtomicInteger(1);

    /**
     * 获取已有占位符，或为原始敏感数据注册新的命名占位符
     *
     * @param original   原始敏感字符串
     * @param entityType 实体类型 (PHONE, EMAIL, ID_CARD, GENERIC)
     * @return 语义占位符 (如 <PHONE_1>)
     */
    public String getOrCreatePlaceholder(String original, String entityType) {
        if (original == null || original.isBlank()) {
            return original;
        }

        return originalToPlaceholder.computeIfAbsent(original, orig -> {
            String placeholder;
            if ("PHONE".equalsIgnoreCase(entityType)) {
                placeholder = "<PHONE_" + phoneCounter.getAndIncrement() + ">";
            } else if ("EMAIL".equalsIgnoreCase(entityType)) {
                placeholder = "<EMAIL_" + emailCounter.getAndIncrement() + ">";
            } else if ("ID_CARD".equalsIgnoreCase(entityType)) {
                placeholder = "<ID_CARD_" + idCardCounter.getAndIncrement() + ">";
            } else {
                placeholder = "<ENTITY_" + genericCounter.getAndIncrement() + ">";
            }
            placeholderToOriginal.put(placeholder, orig);
            return placeholder;
        });
    }

    /**
     * 将文本中的占位符反向还原为真实数据
     */
    public String restore(String text) {
        if (text == null || text.isBlank() || placeholderToOriginal.isEmpty()) {
            return text;
        }

        String result = text;
        for (Map.Entry<String, String> entry : placeholderToOriginal.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }

    public Map<String, String> getPlaceholderMappings() {
        return Collections.unmodifiableMap(placeholderToOriginal);
    }

    public boolean isEmpty() {
        return placeholderToOriginal.isEmpty();
    }

    public void clear() {
        placeholderToOriginal.clear();
        originalToPlaceholder.clear();
        phoneCounter.set(1);
        emailCounter.set(1);
        idCardCounter.set(1);
        genericCounter.set(1);
    }
}
