package io.ledger.tenancy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.core.env.Environment;

/**
 * Resolves {@code tenant_datasources.secret_ref} pointers. The control plane stores only
 * references, never passwords:
 * <ul>
 *   <li>{@code env:NAME} - environment variable / Spring property {@code NAME}, falling back
 *       to {@code ledger.secrets.NAME} (local development defaults)</li>
 *   <li>{@code file:/run/secrets/x} - file content, e.g. Docker/Kubernetes secrets</li>
 * </ul>
 */
public class SecretResolver {

    private final Environment environment;

    public SecretResolver(Environment environment) {
        this.environment = environment;
    }

    public String resolve(String secretRef) {
        int colon = secretRef.indexOf(':');
        if (colon < 1) {
            throw new IllegalArgumentException("Malformed secret reference");
        }
        String scheme = secretRef.substring(0, colon);
        String target = secretRef.substring(colon + 1);
        return switch (scheme) {
            case "env" -> {
                String value = environment.getProperty(target, environment.getProperty("ledger.secrets." + target));
                if (value == null) {
                    throw new IllegalStateException("Secret " + target + " is not set");
                }
                yield value;
            }
            case "file" -> {
                try {
                    yield Files.readString(Path.of(target)).strip();
                } catch (IOException e) {
                    throw new UncheckedIOException("Cannot read secret file " + target, e);
                }
            }
            default -> throw new IllegalStateException("Unsupported secret scheme: " + scheme);
        };
    }
}
