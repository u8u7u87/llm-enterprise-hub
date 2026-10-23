package com.enterprise.llm.context;

import com.enterprise.llm.model.ChatMessage;

import java.util.List;

public interface ContextDistiller {
    String distill(List<ChatMessage> expiredMessages);
}
