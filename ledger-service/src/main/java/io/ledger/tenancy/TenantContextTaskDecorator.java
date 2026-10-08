package io.ledger.tenancy;

import java.util.Map;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

/**
 * Carries the submitting thread's tenant (and logging MDC) into async tasks, and clears
 * both when the task ends. Spring Boot applies TaskDecorator beans to its auto-configured
 * executors, including the virtual-thread executor.
 */
public class TenantContextTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable task) {
        TenantContext.TenantScope scope = TenantContext.current().orElse(null);
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previousMdc = MDC.getCopyOfContextMap();
            if (mdc != null) {
                MDC.setContextMap(mdc);
            }
            try {
                if (scope == null) {
                    task.run();
                } else {
                    TenantContext.runAs(scope, task);
                }
            } finally {
                if (previousMdc == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(previousMdc);
                }
            }
        };
    }
}
