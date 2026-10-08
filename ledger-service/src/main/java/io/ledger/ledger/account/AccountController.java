package io.ledger.ledger.account;

import java.net.URI;
import java.util.List;

import io.ledger.ledger.account.AccountService.AccountResponse;
import io.ledger.ledger.account.AccountService.CreateAccountRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ledger/accounts")
@Tag(name = "Accounts", description = "Chart of accounts with live balances")
public class AccountController {

    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    @PreAuthorize("@tenantSecurity.has('LEDGER_READ')")
    @Operation(summary = "List accounts with current balances")
    public List<AccountResponse> list() {
        return accounts.list();
    }

    @PostMapping
    @PreAuthorize("@tenantSecurity.has('ACCOUNTS_MANAGE')")
    @Operation(summary = "Create an account")
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
        AccountResponse created = accounts.create(request);
        return ResponseEntity.created(URI.create("/api/v1/ledger/accounts/" + created.code())).body(created);
    }
}
