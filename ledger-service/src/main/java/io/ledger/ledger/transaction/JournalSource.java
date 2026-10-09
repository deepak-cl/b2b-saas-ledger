package io.ledger.ledger.transaction;

/** {@code journal_entries.source}. Must stay in lockstep with the database check constraint. */
public enum JournalSource {
    API,
    SEC_EDGAR,
    PAYSIM,
    RECONCILIATION,
    REVERSAL,
    SYSTEM
}
