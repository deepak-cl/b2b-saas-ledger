package io.ledger.ingestion.paysim;

import java.time.LocalDate;
import java.util.concurrent.ThreadPoolExecutor;

import io.ledger.ingestion.IngestionProperties;
import io.ledger.ledger.transaction.LedgerPostingService;
import io.ledger.tenancy.TenantRegistry;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.configuration.support.MapJobRegistry;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.support.TaskExecutorJobOperator;
import org.springframework.batch.core.partition.Partitioner;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemStreamReader;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Partitioned chunk job. Each worker streams its own line range (the reader is not shared) and
 * posts through {@link LedgerPostingService}, so heap stays proportional to the chunk, not the
 * file. The launcher thread and the worker threads are different pools: one pool for both
 * deadlocks, because the launcher waits for workers that can never start.
 */
@Configuration(proxyBeanMethods = false)
public class PaySimBatchConfiguration {

    public static final String JOB_NAME = "paysim";

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final IngestionProperties properties;

    public PaySimBatchConfiguration(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                                    IngestionProperties properties) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.properties = properties;
    }

    @Bean
    Job paysimJob(Step paysimManager) {
        return new JobBuilder(JOB_NAME, jobRepository).start(paysimManager).build();
    }

    @Bean
    Step paysimManager(Partitioner paysimPartitioner, Step paysimWorker, TaskExecutor paysimWorkers) {
        return new StepBuilder("paysim-manager", jobRepository)
                .partitioner("paysim-worker", paysimPartitioner)
                .step(paysimWorker)
                .gridSize(properties.paysim().partitions())
                .taskExecutor(paysimWorkers)
                .build();
    }

    @Bean
    Step paysimWorker(ItemStreamReader<PaySimCsv> paysimReader, PaySimWriter paysimWriter,
                      PaySimTenantListener paysimTenant) {
        return new StepBuilder("paysim-worker", jobRepository)
                .<PaySimCsv, PaySimCsv>chunk(properties.paysim().chunkSize())
                .transactionManager(transactionManager)
                .reader(paysimReader)
                .writer(paysimWriter)
                .stream(paysimReader)
                .stream(paysimWriter)
                .listener(paysimTenant)
                .build();
    }

    @Bean
    @StepScope
    Partitioner paysimPartitioner(
            @org.springframework.beans.factory.annotation.Value("#{jobParameters['file']}") String file,
            @org.springframework.beans.factory.annotation.Value("#{jobParameters['lineCount']}") Long lineCount) {
        return new PaySimPartitioner(file, lineCount == null ? 0 : lineCount);
    }

    @Bean
    @StepScope
    ItemStreamReader<PaySimCsv> paysimReader(
            @org.springframework.beans.factory.annotation.Value("#{stepExecutionContext['file']}") String file,
            @org.springframework.beans.factory.annotation.Value("#{stepExecutionContext['startLine']}") Long startLine,
            @org.springframework.beans.factory.annotation.Value("#{stepExecutionContext['lineCount']}") Long lineCount) {
        return new PaySimCsvReader(java.nio.file.Path.of(file), startLine, lineCount);
    }

    @Bean
    @StepScope
    PaySimWriter paysimWriter(LedgerPostingService posting,
                              @org.springframework.beans.factory.annotation.Value("#{jobParameters['currency']}") String currency,
                              @org.springframework.beans.factory.annotation.Value("#{jobParameters['fileHash']}") String fileHash,
                              @org.springframework.beans.factory.annotation.Value("#{jobParameters['epoch']}") String epoch) {
        return new PaySimWriter(posting, currency, fileHash, LocalDate.parse(epoch));
    }

    @Bean
    @StepScope
    PaySimTenantListener paysimTenant(TenantRegistry registry,
                                      @org.springframework.beans.factory.annotation.Value("#{jobParameters['tenant']}") String slug) {
        return new PaySimTenantListener(registry, slug);
    }

    @Bean
    TaskExecutor paysimWorkers() {
        return pool("paysim-", properties.paysim().partitions());
    }

    /** Runs the job off the request thread. Not the worker pool (see class note). */
    @Bean
    @Qualifier("asyncJobOperator")
    JobOperator asyncJobOperator(TaskExecutor paysimLauncher) throws Exception {
        TaskExecutorJobOperator operator = new TaskExecutorJobOperator();
        operator.setJobRepository(jobRepository);
        operator.setJobRegistry(new MapJobRegistry());
        operator.setTaskExecutor(paysimLauncher);
        operator.afterPropertiesSet();
        return operator;
    }

    @Bean
    TaskExecutor paysimLauncher() {
        return pool("paysim-job-", 2);
    }

    private static ThreadPoolTaskExecutor pool(String prefix, int size) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(prefix);
        executor.setCorePoolSize(size);
        executor.setMaxPoolSize(size);
        executor.setQueueCapacity(size * 2);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
