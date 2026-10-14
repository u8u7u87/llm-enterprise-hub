package com.enterprise.llm.rag;

import com.enterprise.llm.model.ChatMessage;
import com.enterprise.llm.rag.model.SearchResult;

import java.util.ArrayList;
import java.util.List;

public class PromptAugmentor {

    /**
     * Augments user query or conversation messages with retrieved knowledge chunks.
     *
     * @param originalMessages original list of chat messages
     * @param searchResults    retrieved Top-K knowledge chunks
     * @return augmented list of chat messages with reference context injected
     */
    public List<ChatMessage> augment(List<ChatMessage> originalMessages, List<SearchResult> searchResults) {
        if (originalMessages == null || originalMessages.isEmpty()) {
            return originalMessages;
        }

        if (searchResults == null || searchResults.isEmpty()) {
            return originalMessages;
        }

        StringBuilder contextBuilder = new StringBuilder();
        contextBuilder.append("【相关知识库参考资料】:\n");
        for (int i = 0; i < searchResults.size(); i++) {
            SearchResult res = searchResults.get(i);
            contextBuilder.append(String.format("[%d] 来源: %s\n内容: %s\n\n", i + 1, res.docName(), res.text()));
        }
        contextBuilder.append("请优先结合上述参考资料准确、客观地回答用户问题。若参考资料未提及，请如实说明。");

        List<ChatMessage> augmented = new ArrayList<>(originalMessages);

        // If there's an existing system message at index 0, append the context to it
        if (!augmented.isEmpty() && "system".equalsIgnoreCase(augmented.get(0).role())) {
            ChatMessage oldSystem = augmented.get(0);
            String newContent = oldSystem.content() + "\n\n" + contextBuilder;
            augmented.set(0, new ChatMessage("system", newContent));
        } else {
            // Otherwise prepend a new system message
            augmented.add(0, new ChatMessage("system", contextBuilder.toString()));
        }

        return augmented;
    }
}
