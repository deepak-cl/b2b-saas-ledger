package io.ledger.ingestion;

import java.util.List;

import io.ledger.ingestion.paysim.PaySimIngestionService;
import io.ledger.ingestion.paysim.PaySimIngestionService.PaySimRun;
import io.ledger.ingestion.recon.ReconciliationService;
import io.ledger.ingestion.recon.ReconciliationService.Report;
import io.ledger.ingestion.sec.SecIngestionService;
import io.ledger.ingestion.sec.SecIngestionService.SecIngestResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Platform operations. {@code PLATFORM_ADMIN} only; the tenant lives in the body, not a header. */
@RestController
@RequestMapping("/api/v1/admin/ingestion")
@Tag(name = "Ingestion", description = "SEC EDGAR, PaySim and reconciliation (PLATFORM_ADMIN)")
public class IngestionController {

    public record SecRequest(
            @NotBlank @Pattern(regexp = "^[a-z][a-z0-9_]{2,47}$") String tenant,
            @NotBlank @Pattern(regexp = "^[0-9]{1,10}$") String cik,
            @Min(1) @Max(30) Integer years) {
    }

    private final SecIngestionService sec;
    private final PaySimIngestionService paysim;
    private final ReconciliationService reconciliation;

    public IngestionController(SecIngestionService sec, PaySimIngestionService paysim, ReconciliationService reconciliation) {
        this.sec = sec;
        this.paysim = paysim;
        this.reconciliation = reconciliation;
    }

    @PostMapping("/sec")
    @Operation(summary = "Import a company's 10-K balance sheets from SEC EDGAR into a tenant")
    public SecIngestResult sec(@Valid @RequestBody SecRequest request) {
        return sec.ingest(request.tenant(), request.cik(), request.years());
    }

    @PostMapping(value = "/paysim", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Start a partitioned PaySim CSV load. Poll the job endpoint for the result.")
    public ResponseEntity<PaySimRun> paysim(
            @RequestParam @Pattern(regexp = "^[a-z][a-z0-9_]{2,47}$") String tenant,
            @RequestPart("file") MultipartFile file) {
        PaySimRun run = paysim.start(tenant, paysim.store(file));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(run);
    }

    @GetMapping("/jobs/{id}")
    @Operation(summary = "Status of a PaySim job execution")
    public PaySimRun job(@PathVariable long id) {
        return paysim.status(id);
    }

    @PostMapping("/reconciliation")
    @Operation(summary = "Reconcile one tenant, or every active tenant when tenant is omitted")
    public List<Report> reconcile(@RequestParam(required = false) @Pattern(regexp = "^[a-z][a-z0-9_]{2,47}$") String tenant) {
        if (tenant == null || tenant.isBlank()) {
            return reconciliation.reconcileAll();
        }
        return List.of(reconciliation.reconcile(tenant));
    }
}
