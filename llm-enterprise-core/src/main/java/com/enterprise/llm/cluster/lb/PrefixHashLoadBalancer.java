package com.enterprise.llm.cluster.lb;

import com.enterprise.llm.cluster.UpstreamNode;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatMessage;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Prefix-hash load balancer for vLLM Automatic Prefix Caching (APC) optimization.
 * Computes a deterministic hash of the prompt's prefix to consistently route requests
 * with matching system prompts / prefix contexts to the same GPU worker node,
 * maximizing KV cache hit rates.
 */
public class PrefixHashLoadBalancer implements ClusterLoadBalancer {

    @Override
    public UpstreamNode choose(List<UpstreamNode> candidateNodes, ChatCompletionRequest request) {
        List<UpstreamNode> available = filterAvailable(candidateNodes);
        if (available.isEmpty()) {
            return null;
        }

        String key = extractPrefixKey(request);
        int hash = murmur3(key);
        int index = Math.floorMod(hash, available.size());
        return available.get(index);
    }

    /**
     * Extracts hash key from the request:
     * 1. Checks request.messages(): if first message exists, uses firstMessage.content();
     * 2. Falls back to request.model();
     * 3. Falls back to a random string.
     */
    public String extractPrefixKey(ChatCompletionRequest request) {
        if (request != null && request.messages() != null && !request.messages().isEmpty()) {
            ChatMessage firstMessage = request.messages().get(0);
            if (firstMessage != null && firstMessage.content() != null && !firstMessage.content().isBlank()) {
                return firstMessage.content();
            }
        }
        if (request != null && request.model() != null && !request.model().isBlank()) {
            return request.model();
        }
        return UUID.randomUUID().toString();
    }

    /**
     * 32-bit MurmurHash3 implementation for high-quality uniform distribution.
     */
    public static int murmur3(String key) {
        if (key == null) {
            return 0;
        }
        byte[] data = key.getBytes(StandardCharsets.UTF_8);
        int length = data.length;
        int c1 = 0xcc9e2d51;
        int c2 = 0x1b873593;
        int h1 = 0;
        int roundedEnd = (length & 0xfffffffc);

        for (int i = 0; i < roundedEnd; i += 4) {
            int k1 = (data[i] & 0xff)
                    | ((data[i + 1] & 0xff) << 8)
                    | ((data[i + 2] & 0xff) << 16)
                    | ((data[i + 3] & 0xff) << 24);
            k1 *= c1;
            k1 = Integer.rotateLeft(k1, 15);
            k1 *= c2;

            h1 ^= k1;
            h1 = Integer.rotateLeft(h1, 13);
            h1 = h1 * 5 + 0xe6546b64;
        }

        int k1 = 0;
        int tail = length & 0x03;
        if (tail == 3) {
            k1 ^= (data[roundedEnd + 2] & 0xff) << 16;
        }
        if (tail >= 2) {
            k1 ^= (data[roundedEnd + 1] & 0xff) << 8;
        }
        if (tail >= 1) {
            k1 ^= (data[roundedEnd] & 0xff);
            k1 *= c1;
            k1 = Integer.rotateLeft(k1, 15);
            k1 *= c2;
            h1 ^= k1;
        }

        h1 ^= length;
        h1 ^= h1 >>> 16;
        h1 *= 0x85ebca6b;
        h1 ^= h1 >>> 13;
        h1 *= 0xc2b2ae35;
        h1 ^= h1 >>> 16;

        return h1;
    }
}
