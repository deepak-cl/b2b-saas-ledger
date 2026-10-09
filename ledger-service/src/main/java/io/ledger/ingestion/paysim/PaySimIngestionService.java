package io.ledger.ingestion.paysim;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

import io.ledger.common.error.LedgerErrorCode;
import io.ledger.common.error.LedgerException;
import io.ledger.ingestion.IngestionAccounts;
import io.ledger.ingestion.IngestionProperties;
import io.ledger.ingestion.TenantWork;
import io.ledger.tenancy.TenantContext;
import io.ledger.tenancy.TenantContext.TenantScope;
import io.ledger.tenancy.TenantInfo;
import io.ledger.tenancy.TenantRegistry;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.explore.JobExplorer;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** Accepts a PaySim CSV under the import directory and runs {@code paysim} against one tenant. */
@Service
public class PaySimIngestionService {

    public record Scan(long lines, String fileHash, int minStep, int maxStep) {
    }

    public record PaySimRun(long jobExecutionId, String status, long posted, long replayed, long skipped, String detail) {
    }

    private final IngestionProperties properties;
    private final TenantRegistry registry;
    private final IngestionAccounts accounts;
    private final Job paysimJob;
    private final JobOperator jobOperator;
    private final JobOperator asyncJobOperator;
    private final JobExplorer jobExplorer;

    public PaySimIngestionService(IngestionProperties properties, TenantRegistry registry, IngestionAccounts accounts,
                                  Job paysimJob, JobOperator jobOperator,
                                  @Qualifier("asyncJobOperator") JobOperator asyncJobOperator, JobExplorer jobExplorer) {
        this.properties = properties;
        this.registry = registry;
        this.accounts = accounts;
        this.paysimJob = paysimJob;
        this.jobOperator = jobOperator;
        this.asyncJobOperator = asyncJobOperator;
        this.jobExplorer = jobExplorer;
    }

    public Path store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new LedgerException(LedgerErrorCode.VALIDATION_FAILED, "PaySim CSV is required");
        }
        String original = file.getOriginalFilename() == null ? "upload.csv" : Path.of(file.getOriginalFilename()).getFileName().toString();
        if (!original.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw new LedgerException(LedgerErrorCode.VALIDATION_FAILED, "PaySim upload must be a .csv file");
        }
        Path directory = properties.paysim().directory();
        Path dest = directory.resolve("paysim-" + java.util.UUID.randomUUID() + ".csv").normalize();
        if (!dest.startsWith(directory)) {
            throw new LedgerException(LedgerErrorCode.VALIDATION_FAILED, "Refusing a path outside the import directory");
        }
        try {
            Files.createDirectories(directory);
            file.transferTo(dest);
        } catch (IOException e) {
            throw new LedgerException(LedgerErrorCode.INTERNAL_ERROR, "Could not store the PaySim upload", java.util.Map.of(), e);
        }
        return dest;
    }

    /** Blocks until the job finishes. Used by tests and by anything that can wait. */
    public PaySimRun run(String tenantSlug, Path csv) {
        return launch(tenantSlug, csv, true);
    }

    /** Returns as soon as the execution exists. Poll {@link #status(long)}. */
    public PaySimRun start(String tenantSlug, Path csv) {
        return launch(tenantSlug, csv, false);
    }

    public PaySimRun status(long jobExecutionId) {
        JobExecution execution = jobExplorer.getJobExecution(jobExecutionId);
        if (execution == null || !PaySimBatchConfiguration.JOB_NAME.equals(execution.getJobInstance().getJobName())) {
            throw new LedgerException(LedgerErrorCode.RESOURCE_NOT_FOUND, "No PaySim job execution " + jobExecutionId);
        }
        return summarize(execution);
    }

    private PaySimRun launch(String tenantSlug, Path csv, boolean wait) {
        TenantInfo tenant = TenantWork.requireActive(registry, tenantSlug);
        Path file = csv.toAbsolutePath().normalize();
        if (!file.startsWith(properties.paysim().directory()) || !Files.isRegularFile(file)) {
            throw new LedgerException(LedgerErrorCode.VALIDATION_FAILED, "CSV must be a file inside " + properties.paysim().directory());
        }
        Scan scan = scan(file);
        if (scan.lines() > 0) {
            java.time.LocalDate epoch = properties.paysim().epoch();
            java.time.LocalDate from = epoch.plusDays((scan.minStep() - 1L) / 24);
            java.time.LocalDate to = epoch.plusDays((scan.maxStep() - 1L) / 24);
            TenantContext.runAs(TenantScope.system(tenant), () -> prepare(tenant.baseCurrency(), from, to));
        }
        try {
            JobOperator operator = wait ? jobOperator : asyncJobOperator;
            JobExecution execution = operator.start(paysimJob, new JobParametersBuilder()
                    .addString("tenant", tenant.slug())
                    .addString("file", file.toString())
                    .addString("fileHash", scan.fileHash())
                    .addString("currency", tenant.baseCurrency())
                    .addString("epoch", properties.paysim().epoch().toString())
                    .addLong("lineCount", scan.lines())
                    .addLong("run", System.nanoTime())
                    .toJobParameters());
            if (wait && execution.getStatus() != BatchStatus.COMPLETED) {
                throw new LedgerException(LedgerErrorCode.INTERNAL_ERROR, failure(execution));
            }
            JobExecution stored = jobExplorer.getJobExecution(execution.getId());
            return summarize(stored == null ? execution : stored);
        } catch (LedgerException e) {
            throw e;
        } catch (Exception e) {
            throw new LedgerException(LedgerErrorCode.INTERNAL_ERROR, "PaySim job failed to start", java.util.Map.of(), e);
        }
    }

    private void prepare(String currency, java.time.LocalDate from, java.time.LocalDate to) {
        accounts.ensure(IngestionAccounts.PAYSIM_CASH, "PaySim Settlement Cash", "ASSET", currency, "paysim:cash");
        accounts.ensure("4800", "PaySim Cash In", "REVENUE", currency, "paysim:cash-in");
        accounts.ensure("6800", "PaySim Transfers", "EXPENSE", currency, "paysim:transfer");
        accounts.ensure("6810", "PaySim Payments", "EXPENSE", currency, "paysim:payment");
        accounts.ensure("6820", "PaySim Cash Out", "EXPENSE", currency, "paysim:cash-out");
        accounts.ensure("6830", "PaySim Debit Card", "EXPENSE", currency, "paysim:debit");
        accounts.ensurePartitions(from, to);
    }

    static Scan scan(Path file) {
        MessageDigest digest = sha256();
        long lines = 0;
        int min = Integer.MAX_VALUE;
        int max = 0;
        try (var reader = Files.newBufferedReader(file)) {
            String header = reader.readLine();
            if (header == null || !header.toLowerCase(Locale.ROOT).startsWith("step,type,amount")) {
                throw new LedgerException(LedgerErrorCode.VALIDATION_FAILED,
                        "PaySim CSV must start with step,type,amount,nameOrig,...");
            }
            digest.update(header.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String line;
            long number = 0;
            while ((line = reader.readLine()) != null) {
                number++;
                digest.update((byte) '\n');
                digest.update(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                lines++;
                PaySimCsv row = PaySimCsv.parse(line, number);
                if (row != null && !row.skipped()) {
                    min = Math.min(min, row.step());
                    max = Math.max(max, row.step());
                }
            }
        } catch (IOException e) {
            throw new LedgerException(LedgerErrorCode.INTERNAL_ERROR, "Cannot read " + file, java.util.Map.of(), e);
        }
        if (lines > 0 && max == 0) {
            min = 1;
            max = 1;
        }
        return new Scan(lines, HexFormat.of().formatHex(digest.digest()).substring(0, 12), min, max);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PaySimRun summarize(JobExecution execution) {
        long posted = 0;
        long replayed = 0;
        long skipped = 0;
        for (StepExecution step : execution.getStepExecutions()) {
            if (!step.getStepName().startsWith("paysim-worker")) {
                continue;
            }
            var ctx = step.getExecutionContext();
            posted += ctx.getLong("posted", 0);
            replayed += ctx.getLong("replayed", 0);
            skipped += ctx.getLong("skipped", 0);
        }
        String detail = execution.getStatus() == BatchStatus.COMPLETED ? "" : failure(execution);
        return new PaySimRun(execution.getId(), execution.getStatus().name(), posted, replayed, skipped, detail);
    }

    private static String failure(JobExecution execution) {
        String message = execution.getExitStatus().getExitDescription();
        if (message == null || message.isBlank()) {
            message = execution.getStatus().name();
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
