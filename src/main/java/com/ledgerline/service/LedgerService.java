package com.ledgerline.service;

import com.ledgerline.api.PostEntryRequest;
import com.ledgerline.api.PostTransactionRequest;
import com.ledgerline.config.BalanceConvention;
import com.ledgerline.domain.*;
import com.ledgerline.event.TransactionPostedEvent;
import com.ledgerline.exception.AccountNotFoundException;
import com.ledgerline.exception.IdempotencyConflictException;
import com.ledgerline.exception.TransactionNotFoundException;
import com.ledgerline.exception.UnbalancedTransactionException;
import com.ledgerline.repository.AccountRepository;
import com.ledgerline.repository.IdempotencyRecordRepository;
import com.ledgerline.repository.LedgerTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Core posting engine: validates double-entry balance, enforces
 * idempotency, locks and updates account balances, and persists the
 * transaction -- all inside one DB transaction. Event publication is
 * deferred to *after* commit (see LedgerEventCommitListener) so a
 * Kafka-publish failure can never roll back an already-good ledger
 * entry, and a crash can never publish an event for a transaction that
 * didn't actually commit.
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final AccountRepository accountRepository;
    private final LedgerTransactionRepository transactionRepository;
    private final IdempotencyRecordRepository idempotencyRepository;
    private final IdempotencyKeyReservationService reservationService;
    private final ApplicationEventPublisher eventPublisher;

    public LedgerService(AccountRepository accountRepository,
                          LedgerTransactionRepository transactionRepository,
                          IdempotencyRecordRepository idempotencyRepository,
                          IdempotencyKeyReservationService reservationService,
                          ApplicationEventPublisher eventPublisher) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.reservationService = reservationService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public LedgerTransaction postTransaction(PostTransactionRequest request) {
        // 1. Balance invariant checked before touching any storage --
        //    a malformed request should never consume an idempotency key.
        validateBalanced(request.entries());

        String canonical = canonicalize(request);
        String requestHash = RequestHasher.sha256Hex(canonical);

        // 2. Idempotency: look for a prior attempt with this key.
        Optional<IdempotencyRecord> existing =
                idempotencyRepository.findByIdempotencyKey(request.idempotencyKey());

        if (existing.isPresent()) {
            return handleExistingRecord(request, existing.get(), requestHash);
        }

        // Never-before-seen key: reserve it in its own transaction so a
        // concurrent racer trying the same brand-new key fails fast and
        // falls back to reading whichever of us actually won (see
        // IdempotencyKeyReservationService for why this can't just be
        // another save() in this transaction).
        boolean reserved = reservationService.tryReserve(request.idempotencyKey(), requestHash);
        if (!reserved) {
            IdempotencyRecord winner = idempotencyRepository.findByIdempotencyKey(request.idempotencyKey())
                    .orElseThrow(() -> new IllegalStateException(
                            "Idempotency record for key '" + request.idempotencyKey()
                                    + "' vanished after a reservation conflict"));
            return handleExistingRecord(request, winner, requestHash);
        }

        IdempotencyRecord record = idempotencyRepository.findByIdempotencyKey(request.idempotencyKey())
                .orElseThrow(() -> new IllegalStateException("Just-reserved idempotency record not found"));
        return postAndComplete(request, record);
    }

    @Transactional(readOnly = true)
    public LedgerTransaction getTransaction(UUID id) {
        return transactionRepository.findById(id)
                .orElseThrow(() -> new TransactionNotFoundException(id));
    }

    private LedgerTransaction handleExistingRecord(PostTransactionRequest request,
                                                    IdempotencyRecord record,
                                                    String requestHash) {
        if (!record.getRequestHash().equals(requestHash)) {
            throw new IdempotencyConflictException(
                    "Idempotency key '" + request.idempotencyKey()
                            + "' was already used with a different request body");
        }
        return switch (record.getStatus()) {
            case COMPLETED -> transactionRepository.findById(record.getTransactionId())
                    .orElseThrow(() -> new TransactionNotFoundException(record.getTransactionId()));
            case PENDING -> throw new IdempotencyConflictException(
                    "A request with idempotency key '" + request.idempotencyKey()
                            + "' is already being processed");
            // A previous attempt with this exact key+body failed before
            // completion (e.g. an account lookup failed mid-transaction);
            // since that failure rolled back with it, it's safe to retry
            // under the same key rather than force the client to mint a
            // new one. Note: if two retries of the same failed key race
            // concurrently, both may re-attempt doPost() -- the UNIQUE
            // constraint on ledger_transactions.idempotency_key rejects
            // the loser at commit, which surfaces as a 500 rather than a
            // clean 409. Accepted as a rare v1 edge case (see ADR 0004)
            // rather than adding a second reservation hop for a retry
            // path that only triggers after a prior failure.
            case FAILED -> postAndComplete(request, record);
        };
    }

    private LedgerTransaction postAndComplete(PostTransactionRequest request, IdempotencyRecord record) {
        try {
            LedgerTransaction tx = doPost(request);
            record.markCompleted(tx.getId());
            idempotencyRepository.save(record);

            eventPublisher.publishEvent(new TransactionPostedEvent(
                    tx.getId(), tx.getIdempotencyKey(), tx.getCreatedAt()));
            return tx;
        } catch (RuntimeException e) {
            record.markFailed();
            idempotencyRepository.save(record);
            throw e;
        }
    }

    private LedgerTransaction doPost(PostTransactionRequest request) {
        // Lock accounts in a deterministic (sorted-by-id) order across
        // ALL concurrent postings, regardless of the order entries
        // appear in any given request -- this is what prevents two
        // transactions that touch the same two accounts in opposite
        // orders from deadlocking on each other's pessimistic locks.
        List<UUID> accountIds = request.entries().stream()
                .map(PostEntryRequest::accountId)
                .distinct()
                .sorted()
                .toList();

        Map<UUID, Account> accounts = new LinkedHashMap<>();
        for (UUID id : accountIds) {
            Account account = accountRepository.findWithLockById(id)
                    .orElseThrow(() -> new AccountNotFoundException(id));
            accounts.put(id, account);
        }

        LedgerTransaction tx = new LedgerTransaction(
                UUID.randomUUID(), request.idempotencyKey(), request.description());

        for (PostEntryRequest e : request.entries()) {
            Account account = accounts.get(e.accountId());
            LedgerEntry entry = new LedgerEntry(
                    UUID.randomUUID(), tx, e.accountId(), e.direction(), e.amountMinor());
            tx.getEntries().add(entry);

            long delta = BalanceConvention.signedDelta(account.getAccountType(), e.direction(), e.amountMinor());
            account.applyDelta(delta);
        }

        transactionRepository.save(tx);
        accountRepository.saveAll(accounts.values());
        return tx;
    }

    private void validateBalanced(List<PostEntryRequest> entries) {
        long debitTotal = entries.stream()
                .filter(e -> e.direction() == EntryDirection.DEBIT)
                .mapToLong(PostEntryRequest::amountMinor)
                .sum();
        long creditTotal = entries.stream()
                .filter(e -> e.direction() == EntryDirection.CREDIT)
                .mapToLong(PostEntryRequest::amountMinor)
                .sum();
        if (debitTotal != creditTotal) {
            throw new UnbalancedTransactionException(debitTotal, creditTotal);
        }
    }

    private String canonicalize(PostTransactionRequest request) {
        String entriesPart = request.entries().stream()
                .map(e -> e.accountId() + ":" + e.direction() + ":" + e.amountMinor())
                .sorted()
                .collect(Collectors.joining(","));
        return request.idempotencyKey() + "|"
                + Objects.requireNonNullElse(request.description(), "") + "|"
                + entriesPart;
    }
}
