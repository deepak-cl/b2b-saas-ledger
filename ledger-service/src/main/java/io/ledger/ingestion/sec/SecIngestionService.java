package io.ledger.ingestion.sec;

import java.time.LocalDate;
import java.util.List;

import io.ledger.ingestion.IngestionAccounts;
import io.ledger.ingestion.IngestionProperties;
import io.ledger.ingestion.TenantWork;
import io.ledger.ingestion.sec.CompanyFactsParser.AnnualSnapshot;
import io.ledger.ingestion.sec.CompanyFactsParser.Parsed;
import io.ledger.ingestion.sec.SecJournalMapper.Mapped;
import io.ledger.ledger.transaction.JournalSource;
import io.ledger.ledger.transaction.LedgerPostingService;
import io.ledger.ledger.transaction.LedgerPostingService.PostingResult;
import io.ledger.tenancy.TenantContext;
import io.ledger.tenancy.TenantContext.TenantScope;
import io.ledger.tenancy.TenantInfo;
import io.ledger.tenancy.TenantRegistry;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Pulls one company's 10-K balance sheets into a tenant's ledger. Idempotent per CIK and year. */
@Service
public class SecIngestionService {

    public record SecIngestResult(String tenant, String cik, String entityName, int posted, int replayed, int skipped,
                                  List<String> periods) {
    }

    private final SecCompanyFactsClient client;
    private final IngestionProperties properties;
    private final TenantRegistry registry;
    private final IngestionAccounts accounts;
    private final LedgerPostingService posting;

    public SecIngestionService(SecCompanyFactsClient client, IngestionProperties properties, TenantRegistry registry,
                               IngestionAccounts accounts, LedgerPostingService posting) {
        this.client = client;
        this.properties = properties;
        this.registry = registry;
        this.accounts = accounts;
        this.posting = posting;
    }

    public SecIngestResult ingest(String tenantSlug, String cik, Integer years) {
        TenantInfo tenant = TenantWork.requireActive(registry, tenantSlug);
        int window = years == null ? properties.sec().maxYears() : years;
        JsonNode document = client.fetch(cik);
        Parsed parsed = CompanyFactsParser.parse(document, window);
        String cik10 = SecCompanyFactsClient.pad(cik);
        List<Mapped> journals = SecJournalMapper.journals(cik10, parsed.entityName(), parsed.years(), "USD");
        return TenantContext.callAs(TenantScope.system(tenant), () -> post(tenant.slug(), cik10, parsed, journals));
    }

    private SecIngestResult post(String slug, String cik10, Parsed parsed, List<Mapped> journals) {
        accounts.ensure(IngestionAccounts.SEC_ASSETS, "SEC Reported Assets", "ASSET", "USD", "us-gaap:Assets");
        accounts.ensure(IngestionAccounts.SEC_LIABILITIES, "SEC Reported Liabilities", "LIABILITY", "USD", "us-gaap:Liabilities");
        accounts.ensure(IngestionAccounts.SEC_EQUITY, "SEC Reported Equity", "EQUITY", "USD", "us-gaap:StockholdersEquity");
        accounts.ensure(IngestionAccounts.SEC_PLUG, "SEC Import Plug", "EQUITY", "USD", "sec:plug");
        if (!journals.isEmpty()) {
            LocalDate from = journals.getFirst().request().effectiveDate();
            LocalDate to = journals.getLast().request().effectiveDate();
            accounts.ensurePartitions(from, to);
        }
        int posted = 0;
        int replayed = 0;
        for (Mapped journal : journals) {
            PostingResult result = posting.post(journal.idempotencyKey(), journal.request(), null, JournalSource.SEC_EDGAR);
            if (result.replayed()) {
                replayed++;
            } else {
                posted++;
            }
        }
        List<String> periods = parsed.years().stream().map(y -> y.periodEnd().toString()).toList();
        int skipped = parsed.years().size() - journals.size();
        return new SecIngestResult(slug, cik10, parsed.entityName(), posted, replayed, skipped, periods);
    }
}
