package com.enterprise.llm.client;

import com.enterprise.llm.model.ChatCompletionChunk;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatCompletionResponse;

import java.util.function.Consumer;

public interface LlmClient {

    ChatCompletionResponse chatCompletion(ChatCompletionRequest request);

    StreamCancellationHandle streamChatCompletion(
            ChatCompletionRequest request,
            Consumer<ChatCompletionChunk> onChunk,
            Runnable onComplete,
            Consumer<Throwable> onError
    );
}
