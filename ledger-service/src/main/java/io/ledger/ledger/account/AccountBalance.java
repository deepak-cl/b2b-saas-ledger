package io.ledger.ledger.account;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/** Running balance maintained by the database trigger; read-only from the application. */
@Entity
@Immutable
@Table(name = "account_balances")
public class AccountBalance {

    @Id
    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "debit_total")
    private BigDecimal debitTotal;

    @Column(name = "credit_total")
    private BigDecimal creditTotal;

    /** Debit-positive: debit_total - credit_total. */
    private BigDecimal balance;

    private long version;

    @Column(name = "last_entry_at")
    private Instant lastEntryAt;

    protected AccountBalance() {
    }

    public Long getAccountId() { return accountId; }
    public BigDecimal getDebitTotal() { return debitTotal; }
    public BigDecimal getCreditTotal() { return creditTotal; }
    public BigDecimal getBalance() { return balance; }
    public long getVersion() { return version; }
    public Instant getLastEntryAt() { return lastEntryAt; }
}
