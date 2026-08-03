package com.ledgerline.repository;

import com.ledgerline.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    /**
     * Pessimistic write lock for the balance-mutation path: two
     * concurrent transactions posting against the same account must
     * serialize on this row, not race on a read-modify-write of
     * balanceMinor. Combined with @Version for defense in depth.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Account> findWithLockById(UUID id);

    List<Account> findAllByIdIn(List<UUID> ids);
}
