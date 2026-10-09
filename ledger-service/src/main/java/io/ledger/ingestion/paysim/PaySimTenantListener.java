package io.ledger.ingestion.paysim;

import io.ledger.ingestion.TenantWork;
import io.ledger.tenancy.TenantContext;
import io.ledger.tenancy.TenantContext.Binding;
import io.ledger.tenancy.TenantContext.TenantScope;
import io.ledger.tenancy.TenantRegistry;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.step.StepExecution;

/** Binds the worker thread to the job's tenant before the chunk transaction asks for a connection. */
public class PaySimTenantListener implements StepExecutionListener {

    static final ThreadLocal<StepExecution> CURRENT = new ThreadLocal<>();

    private final TenantRegistry registry;
    private final String slug;
    private Binding binding;

    public PaySimTenantListener(TenantRegistry registry, String slug) {
        this.registry = registry;
        this.slug = slug;
    }

    @Override
    public void beforeStep(StepExecution stepExecution) {
        CURRENT.set(stepExecution);
        binding = TenantContext.open(TenantScope.system(TenantWork.requireActive(registry, slug)));
    }

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        if (binding != null) {
            binding.close();
            binding = null;
        }
        CURRENT.remove();
        return stepExecution.getExitStatus();
    }
}
