package io.ledger.ai;

import javax.sql.DataSource;

import io.ledger.tenancy.TenancyProperties;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Default (no profile): the stub model, so boot and tests never call a paid API.
 * {@code openai}, {@code anthropic}, and {@code ollama} replace the stub by setting
 * {@code spring.ai.model.chat} / {@code embedding} away from {@code none}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiProperties.class)
public class AiConfiguration {

    @Bean
    @ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "none")
    ChatModel stubChatModel() {
        return new StubModels.Chat();
    }

    @Bean
    @ConditionalOnProperty(name = "spring.ai.model.embedding", havingValue = "none")
    EmbeddingModel stubEmbeddingModel(TenancyProperties tenancy) {
        return new StubModels.Embedding(tenancy.embeddingDimensions());
    }

    @Bean
    TenantVectorStore tenantVectorStore(@Qualifier("controlPlaneDataSource") DataSource controlPlane,
                                        EmbeddingModel embeddingModel, TenancyProperties tenancy) {
        return new TenantVectorStore(controlPlane, embeddingModel, tenancy.embeddingDimensions());
    }

    @Bean
    FinancialAiAuditService financialAiAuditService(org.springframework.ai.chat.client.ChatClient.Builder chatClientBuilder,
                                                    TenantVectorStore tenantVectorStore, EmbeddingModel embeddingModel,
                                                    org.springframework.jdbc.core.simple.JdbcClient jdbc,
                                                    PlatformTransactionManager transactionManager, AiProperties properties,
                                                    tools.jackson.databind.json.JsonMapper json) {
        return new FinancialAiAuditService(chatClientBuilder, tenantVectorStore, embeddingModel, jdbc,
                transactionManager, properties, json);
    }
}
