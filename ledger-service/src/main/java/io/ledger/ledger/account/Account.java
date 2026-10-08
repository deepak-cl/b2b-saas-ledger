package io.ledger.ledger.account;

import java.time.Instant;

import io.ledger.ledger.Direction;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;

/**
 * Chart-of-accounts entry. Lives in the tenant schema; Hibernate reaches it through the
 * schema-per-tenant connection provider, so the entity carries no tenant column.
 */
@Entity
@Table(name = "accounts")
public class Account {

    public enum AccountType { ASSET, LIABILITY, EQUITY, REVENUE, EXPENSE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccountType type;

    @Convert(converter = Direction.Converter.class)
    @Column(name = "normal_balance", nullable = false, columnDefinition = "char(1)")
    private Direction normalBalance;

    @Column(nullable = false, columnDefinition = "char(3)")
    private String currency;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(length = 64)
    private String category;

    @Column(name = "allow_negative", nullable = false)
    private boolean allowNegative = true;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "external_ref", length = 128)
    private String externalRef;

    @Generated
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected Account() {
    }

    public Account(String code, String name, AccountType type, Direction normalBalance, String currency) {
        this.code = code;
        this.name = name;
        this.type = type;
        this.normalBalance = normalBalance;
        this.currency = currency;
    }

    public static Direction defaultNormalBalance(AccountType type) {
        return type == AccountType.ASSET || type == AccountType.EXPENSE ? Direction.DEBIT : Direction.CREDIT;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public AccountType getType() { return type; }
    public Direction getNormalBalance() { return normalBalance; }
    public String getCurrency() { return currency; }
    public Long getParentId() { return parentId; }
    public String getCategory() { return category; }
    public boolean isAllowNegative() { return allowNegative; }
    public boolean isActive() { return active; }
    public String getExternalRef() { return externalRef; }
    public Instant getCreatedAt() { return createdAt; }

    public void setParentId(Long parentId) { this.parentId = parentId; }
    public void setCategory(String category) { this.category = category; }
    public void setAllowNegative(boolean allowNegative) { this.allowNegative = allowNegative; }
    public void setExternalRef(String externalRef) { this.externalRef = externalRef; }
}
