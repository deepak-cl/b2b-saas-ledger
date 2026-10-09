package io.ledger.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import reactor.core.publisher.Flux;

/**
 * Deterministic stand-in for a chat and embedding model. Tests and local runs use it so no
 * paid API is contacted. It narrates the whitelist tool result the service already ran.
 */
final class StubModels {

    static final String MODEL = "stub";
    static final int PROMPT_TOKENS = 48;
    static final int COMPLETION_TOKENS = 24;

    private StubModels() {
    }

    static final class Chat implements ChatModel {

        private final AtomicInteger calls = new AtomicInteger();

        int calls() {
            return calls.get();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            calls.incrementAndGet();
            String contents = prompt.getContents();
            int marker = contents.indexOf("Tool result:");
            String evidence = marker < 0 ? "" : contents.substring(marker + "Tool result:".length()).trim();
            String answer = evidence.isBlank()
                    ? "No ledger tool returned data for this question."
                    : "Based on this tenant's ledger:\n" + evidence;
            return response(new AssistantMessage(answer), PROMPT_TOKENS, COMPLETION_TOKENS);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }

        @Override
        public ChatOptions getOptions() {
            return ChatOptions.builder().model(MODEL).build();
        }

        private static ChatResponse response(AssistantMessage message, int promptTokens, int completionTokens) {
            ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                    .model(MODEL)
                    .usage(new DefaultUsage(promptTokens, completionTokens))
                    .build();
            return new ChatResponse(List.of(new Generation(message)), metadata);
        }
    }

    /** Same text always maps to the same unit vector, so a repeated query retrieves its own note. */
    static final class Embedding implements EmbeddingModel {

        private final int dimensions;

        Embedding(int dimensions) {
            this.dimensions = dimensions;
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<org.springframework.ai.embedding.Embedding> vectors = new java.util.ArrayList<>();
            List<String> inputs = request.getInstructions();
            for (int i = 0; i < inputs.size(); i++) {
                vectors.add(new org.springframework.ai.embedding.Embedding(vector(inputs.get(i)), i));
            }
            return new EmbeddingResponse(vectors);
        }

        @Override
        public float[] embed(org.springframework.ai.document.Document document) {
            return vector(document.getText() == null ? "" : document.getText());
        }

        @Override
        public int dimensions() {
            return dimensions;
        }

        private float[] vector(String text) {
            byte[] hash = sha256(text);
            float[] values = new float[dimensions];
            double norm = 0;
            for (int i = 0; i < dimensions; i++) {
                int mixed = (hash[i % hash.length] & 0xff) ^ (i * 31);
                values[i] = ((mixed & 0xff) - 128) / 128f;
                norm += values[i] * values[i];
            }
            float scale = (float) (1.0 / Math.sqrt(norm));
            for (int i = 0; i < dimensions; i++) {
                values[i] *= scale;
            }
            return values;
        }

        private static byte[] sha256(String text) {
            try {
                return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
