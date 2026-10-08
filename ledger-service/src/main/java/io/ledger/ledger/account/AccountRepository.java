package io.ledger.ledger.account;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByCode(String code);

    boolean existsByCode(String code);

    record AccountWithBalance(Account account, AccountBalance balance) {
    }

    @Query("""
            select new io.ledger.ledger.account.AccountRepository$AccountWithBalance(a, b)
            from Account a join AccountBalance b on b.accountId = a.id
            order by a.code
            """)
    List<AccountWithBalance> findAllWithBalances();
}
