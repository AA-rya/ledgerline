package com.ledgerline.service;

import com.ledgerline.domain.IdempotencyRecord;
import com.ledgerline.repository.IdempotencyRecordRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reserves a brand-new idempotency key in its own, independent
 * transaction (REQUIRES_NEW).
 *
 * Why this needs to be a separate transaction rather than just another
 * repository.save() call inside LedgerService's main @Transactional
 * method: JPA/Hibernate typically defers the actual INSERT until flush
 * (often at commit), so a unique-constraint violation from two
 * concurrent requests racing on the same never-before-seen key would
 * only surface at the very end of the whole posting transaction --
 * too late to catch and handle gracefully, and by then Spring has
 * already marked the transaction rollback-only. Doing the reservation
 * as its own REQUIRES_NEW transaction with an explicit saveAndFlush
 * forces the INSERT (and any constraint violation) to happen
 * immediately, isolated from the rest of the posting logic, so the
 * loser of the race can cleanly detect "someone beat me to this key"
 * and fall back to reading what the winner wrote -- instead of the
 * whole request failing with an opaque constraint-violation 500.
 */
@Service
public class IdempotencyKeyReservationService {

    private final IdempotencyRecordRepository repository;

    public IdempotencyKeyReservationService(IdempotencyRecordRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryReserve(String idempotencyKey, String requestHash) {
        try {
            repository.saveAndFlush(new IdempotencyRecord(idempotencyKey, requestHash));
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }
}
