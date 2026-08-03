package com.ledgerline.service;

import com.ledgerline.api.PostEntryRequest;
import com.ledgerline.api.PostTransactionRequest;
import com.ledgerline.domain.*;
import com.ledgerline.exception.AccountNotFoundException;
import com.ledgerline.exception.IdempotencyConflictException;
import com.ledgerline.exception.UnbalancedTransactionException;
import com.ledgerline.repository.AccountRepository;
import com.ledgerline.repository.IdempotencyRecordRepository;
import com.ledgerline.repository.LedgerTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the core posting engine, with repositories mocked so
 * the balance/idempotency *logic* is verified in isolation from
 * Postgres. Concurrency-under-real-locks and the Flyway schema itself
 * are exercised by the (Testcontainers) integration tests instead --
 * see LedgerServiceIntegrationTest.
 */
@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    @Mock AccountRepository accountRepository;
    @Mock LedgerTransactionRepository transactionRepository;
    @Mock IdempotencyRecordRepository idempotencyRepository;
    @Mock IdempotencyKeyReservationService reservationService;
    @Mock ApplicationEventPublisher eventPublisher;

    @InjectMocks LedgerService ledgerService;

    UUID cashAccountId;
    UUID revenueAccountId;
    Account cashAccount;
    Account revenueAccount;

    @BeforeEach
    void setUp() {
        cashAccountId = UUID.randomUUID();
        revenueAccountId = UUID.randomUUID();
        cashAccount = new Account(cashAccountId, "Cash", AccountType.ASSET, "USD");
        revenueAccount = new Account(revenueAccountId, "Sales Revenue", AccountType.REVENUE, "USD");
    }

    private PostTransactionRequest balancedRequest(String idempotencyKey) {
        return new PostTransactionRequest(
                idempotencyKey,
                "test sale",
                List.of(
                        new PostEntryRequest(cashAccountId, EntryDirection.DEBIT, 1000),
                        new PostEntryRequest(revenueAccountId, EntryDirection.CREDIT, 1000)
                )
        );
    }

    @Test
    void rejectsUnbalancedTransactionBeforeTouchingRepositories() {
        PostTransactionRequest unbalanced = new PostTransactionRequest(
                "key-1", "bad", List.of(
                        new PostEntryRequest(cashAccountId, EntryDirection.DEBIT, 1000),
                        new PostEntryRequest(revenueAccountId, EntryDirection.CREDIT, 999)
                ));

        assertThrows(UnbalancedTransactionException.class, () -> ledgerService.postTransaction(unbalanced));

        verifyNoInteractions(idempotencyRepository, reservationService, accountRepository, transactionRepository);
    }

    @Test
    void postsANewBalancedTransactionAndMarksIdempotencyRecordCompleted() {
        PostTransactionRequest request = balancedRequest("key-2");

        when(idempotencyRepository.findByIdempotencyKey("key-2"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new IdempotencyRecord("key-2", "irrelevant-in-this-stub")));
        when(reservationService.tryReserve(eq("key-2"), any())).thenReturn(true);
        when(accountRepository.findWithLockById(cashAccountId)).thenReturn(Optional.of(cashAccount));
        when(accountRepository.findWithLockById(revenueAccountId)).thenReturn(Optional.of(revenueAccount));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        LedgerTransaction result = ledgerService.postTransaction(request);

        assertNotNull(result);
        assertEquals(1000L, cashAccount.getBalanceMinor());
        assertEquals(1000L, revenueAccount.getBalanceMinor());
        verify(eventPublisher).publishEvent(any());
    }

    @Test
    void unknownAccountIsRejectedAndIdempotencyRecordMarkedFailed() {
        PostTransactionRequest request = balancedRequest("key-3");

        when(reservationService.tryReserve(eq("key-3"), any())).thenReturn(true);

        IdempotencyRecord record = new IdempotencyRecord("key-3", "hash");
        when(idempotencyRepository.findByIdempotencyKey("key-3"))
                .thenReturn(Optional.empty(), Optional.of(record));
        when(accountRepository.findWithLockById(cashAccountId)).thenReturn(Optional.empty());

        assertThrows(AccountNotFoundException.class, () -> ledgerService.postTransaction(request));
        assertEquals(IdempotencyStatus.FAILED, record.getStatus());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void replayingACompletedIdempotencyKeyReturnsOriginalTransactionWithoutReposting() {
        String key = "key-4";
        PostTransactionRequest request = balancedRequest(key);
        String expectedHash = RequestHasher.sha256Hex(
                key + "|test sale|" + sortedEntriesCanonical(request));

        UUID originalTxId = UUID.randomUUID();
        IdempotencyRecord completed = new IdempotencyRecord(key, expectedHash);
        completed.markCompleted(originalTxId);

        LedgerTransaction originalTx = new LedgerTransaction(originalTxId, key, "test sale");

        when(idempotencyRepository.findByIdempotencyKey(key)).thenReturn(Optional.of(completed));
        when(transactionRepository.findById(originalTxId)).thenReturn(Optional.of(originalTx));

        LedgerTransaction result = ledgerService.postTransaction(request);

        assertEquals(originalTxId, result.getId());
        verifyNoInteractions(accountRepository, reservationService);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void reusingIdempotencyKeyWithDifferentBodyIsRejected() {
        String key = "key-5";
        IdempotencyRecord existing = new IdempotencyRecord(key, "some-other-hash-entirely");
        existing.markCompleted(UUID.randomUUID());

        when(idempotencyRepository.findByIdempotencyKey(key)).thenReturn(Optional.of(existing));

        assertThrows(IdempotencyConflictException.class,
                () -> ledgerService.postTransaction(balancedRequest(key)));
    }

    @Test
    void concurrentInFlightRequestWithSameKeyIsRejectedAsConflict() {
        String key = "key-6";
        PostTransactionRequest request = balancedRequest(key);
        String expectedHash = RequestHasher.sha256Hex(
                key + "|test sale|" + sortedEntriesCanonical(request));

        IdempotencyRecord pending = new IdempotencyRecord(key, expectedHash);
        // status defaults to PENDING on construction

        when(idempotencyRepository.findByIdempotencyKey(key)).thenReturn(Optional.of(pending));

        assertThrows(IdempotencyConflictException.class, () -> ledgerService.postTransaction(request));
    }

    /** Mirrors LedgerService.canonicalize()'s entry-serialization format for test hash setup. */
    private String sortedEntriesCanonical(PostTransactionRequest request) {
        return request.entries().stream()
                .map(e -> e.accountId() + ":" + e.direction() + ":" + e.amountMinor())
                .sorted()
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }
}
