package io.ledger.ledger.account;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import io.ledger.common.error.LedgerErrorCode;
import io.ledger.common.error.LedgerException;
import io.ledger.ledger.Direction;
import io.ledger.ledger.account.Account.AccountType;
import io.ledger.ledger.account.AccountRepository.AccountWithBalance;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private final AccountRepository accounts;

    public AccountService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    public record CreateAccountRequest(
            @NotBlank @Size(max = 32) @Pattern(regexp = "^[A-Za-z0-9._-]+$") String code,
            @NotBlank @Size(max = 200) String name,
            @NotNull AccountType type,
            Direction normalBalance,
            @NotNull @Pattern(regexp = "^[A-Z]{3}$") String currency,
            @Size(max = 64) String category,
            Boolean allowNegative,
            @Size(max = 32) String parentCode,
            @Size(max = 128) String externalRef) {
    }

    public record AccountResponse(Long id, String code, String name, AccountType type, Direction normalBalance,
                                  String currency, String category, boolean allowNegative, boolean active,
                                  String parentCode, BigDecimal debitTotal, BigDecimal creditTotal,
                                  /* Balance in the account's normal direction (positive = normal). */
                                  BigDecimal balance) {
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> list() {
        List<AccountWithBalance> rows = accounts.findAllWithBalances();
        Map<Long, String> codes = rows.stream().collect(java.util.stream.Collectors.toMap(
                r -> r.account().getId(), r -> r.account().getCode()));
        return rows.stream().map(r -> toResponse(r.account(), r.balance(), codes.get(r.account().getParentId()))).toList();
    }

    @Transactional
    public AccountResponse create(CreateAccountRequest request) {
        if (accounts.existsByCode(request.code())) {
            throw codeExists(request.code());
        }
        Direction normal = request.normalBalance() != null ? request.normalBalance() : Account.defaultNormalBalance(request.type());
        Account account = new Account(request.code(), request.name(), request.type(), normal, request.currency());
        account.setCategory(request.category());
        account.setExternalRef(request.externalRef());
        if (request.allowNegative() != null) {
            account.setAllowNegative(request.allowNegative());
        }
        if (request.parentCode() != null) {
            Account parent = accounts.findByCode(request.parentCode())
                    .orElseThrow(() -> new LedgerException(LedgerErrorCode.ACCOUNT_NOT_FOUND,
                            "Parent account " + request.parentCode() + " does not exist"));
            account.setParentId(parent.getId());
        }
        try {
            account = accounts.saveAndFlush(account);
        } catch (DataIntegrityViolationException e) {
            throw codeExists(request.code());
        }
        return toResponse(account, null, request.parentCode());
    }

    private static LedgerException codeExists(String code) {
        return new LedgerException(LedgerErrorCode.ACCOUNT_CODE_EXISTS, "Account " + code + " already exists");
    }

    private static AccountResponse toResponse(Account a, AccountBalance b, String parentCode) {
        BigDecimal debit = b == null ? BigDecimal.ZERO : b.getDebitTotal();
        BigDecimal credit = b == null ? BigDecimal.ZERO : b.getCreditTotal();
        BigDecimal signed = debit.subtract(credit);
        return new AccountResponse(a.getId(), a.getCode(), a.getName(), a.getType(), a.getNormalBalance(),
                a.getCurrency(), a.getCategory(), a.isAllowNegative(), a.isActive(), parentCode, debit, credit,
                a.getNormalBalance() == Direction.DEBIT ? signed : signed.negate());
    }
}
