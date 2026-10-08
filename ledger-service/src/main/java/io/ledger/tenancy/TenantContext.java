package io.ledger.tenancy;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The tenant the current unit of work runs for. Bound per request by the tenant filter
 * and per task by {@link TenantContextTaskDecorator}; every tenant-scoped database
 * connection is resolved from it.
 *
 * <p>Always bind through {@link #open(TenantScope)} in a try-with-resources block so the
 * previous value is restored even on failure; pooled (and virtual) threads must never
 * carry a tenant beyond the work they were given.
 */
public final class TenantContext {

    private static final ThreadLocal<TenantScope> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    /** Who is acting, for which tenant, with which role. */
    public record TenantScope(TenantInfo tenant, TenantRole role, String principal) {
        public TenantScope {
            Objects.requireNonNull(tenant, "tenant");
            Objects.requireNonNull(role, "role");
        }

        public static TenantScope system(TenantInfo tenant) {
            return new TenantScope(tenant, TenantRole.SYSTEM, "system");
        }
    }

    @FunctionalInterface
    public interface Binding extends AutoCloseable {
        @Override
        void close();
    }

    public static Optional<TenantScope> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static TenantScope require() {
        TenantScope scope = CURRENT.get();
        if (scope == null) {
            throw new TenantContextMissingException();
        }
        return scope;
    }

    public static Binding open(TenantScope scope) {
        Objects.requireNonNull(scope, "scope");
        TenantScope previous = CURRENT.get();
        CURRENT.set(scope);
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    public static <T> T callAs(TenantScope scope, Supplier<T> work) {
        try (Binding ignored = open(scope)) {
            return work.get();
        }
    }

    public static void runAs(TenantScope scope, Runnable work) {
        try (Binding ignored = open(scope)) {
            work.run();
        }
    }

    public static final class TenantContextMissingException extends IllegalStateException {
        public TenantContextMissingException() {
            super("No tenant bound to the current thread; tenant-scoped data access requires a TenantContext");
        }
    }
}
