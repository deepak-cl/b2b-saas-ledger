package io.ledger.ingestion;

import java.time.Duration;

import javax.sql.DataSource;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.ledger.ingestion.sec.SecRateLimits;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.batch.autoconfigure.BatchTransactionManager;
import org.springframework.boot.batch.jdbc.autoconfigure.BatchDataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(IngestionProperties.class)
public class IngestionConfiguration {

    /**
     * Defining the batch transaction manager suppresses Boot's own transaction auto-config,
     * so the application manager is declared here too. Posting and chunk steps use this one;
     * it joins the tenant routing DataSource. Batch job metadata uses {@link #batchTransactionManager}.
     */
    @Bean
    @Primary
    PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }

    /** Job metadata lives on the control plane. The primary DataSource routes per tenant and refuses connections at boot. */
    @Bean
    @BatchDataSource
    DataSource batchDataSource(@Qualifier("controlPlaneDataSource") DataSource controlPlane) {
        return controlPlane;
    }

    @Bean
    @BatchTransactionManager
    PlatformTransactionManager batchTransactionManager(@Qualifier("controlPlaneDataSource") DataSource controlPlane) {
        return new DataSourceTransactionManager(controlPlane);
    }

    @Bean
    RateLimiter secEdgarRateLimiter(IngestionProperties properties) {
        return SecRateLimits.create(properties.sec().rateLimitPerSecond(), Duration.ofSeconds(5));
    }
}
