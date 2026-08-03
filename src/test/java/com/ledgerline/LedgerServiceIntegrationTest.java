package com.ledgerline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerline.api.PostEntryRequest;
import com.ledgerline.api.PostTransactionRequest;
import com.ledgerline.domain.AccountType;
import com.ledgerline.domain.EntryDirection;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Full-stack integration test: real Postgres (via Testcontainers), real
 * Spring context, real HTTP layer via MockMvc. Exercises schema
 * migration (Flyway), the REST API, and the double-entry + idempotency
 * logic end to end.
 *
 * NOT executed in the sandbox this project was authored in: it requires
 * a Docker daemon (to launch the Postgres container) and network access
 * to Maven Central (neither of which this environment has -- see
 * README "Verification status"). Kept in the repo because it's the
 * right test to have and is meant to run in real CI (see
 * .github/workflows/ci.yml, which does have both).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("it")
class LedgerServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ledgerline_test")
            .withUsername("ledgerline")
            .withPassword("ledgerline");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Kafka isn't containerized here on purpose -- KafkaTemplate sends
        // are fire-and-forget with logged failure (see LedgerEventPublisher),
        // so this test verifies the ledger/idempotency behavior without
        // needing a broker up. A Kafka-specific integration test would add
        // an embedded/Testcontainers broker separately.
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void postingABalancedTransactionUpdatesBothAccountBalances() throws Exception {
        UUID cashId = createAccount("Cash", AccountType.ASSET);
        UUID revenueId = createAccount("Revenue", AccountType.REVENUE);

        PostTransactionRequest request = new PostTransactionRequest(
                "it-key-" + UUID.randomUUID(),
                "integration test sale",
                List.of(
                        new PostEntryRequest(cashId, EntryDirection.DEBIT, 5000),
                        new PostEntryRequest(revenueId, EntryDirection.CREDIT, 5000)
                ));

        mockMvc.perform(post("/api/v1/transactions")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("POSTED"));

        mockMvc.perform(get("/api/v1/accounts/" + cashId))
                .andExpect(jsonPath("$.balanceMinor").value(5000));
        mockMvc.perform(get("/api/v1/accounts/" + revenueId))
                .andExpect(jsonPath("$.balanceMinor").value(5000));
    }

    @Test
    void replayingTheSameIdempotencyKeyDoesNotDoublePostTheBalance() throws Exception {
        UUID cashId = createAccount("Cash 2", AccountType.ASSET);
        UUID revenueId = createAccount("Revenue 2", AccountType.REVENUE);
        String key = "it-replay-" + UUID.randomUUID();

        PostTransactionRequest request = new PostTransactionRequest(
                key, "replay test",
                List.of(
                        new PostEntryRequest(cashId, EntryDirection.DEBIT, 1200),
                        new PostEntryRequest(revenueId, EntryDirection.CREDIT, 1200)
                ));
        String body = objectMapper.writeValueAsString(request);

        mockMvc.perform(post("/api/v1/transactions").contentType("application/json").content(body))
                .andExpect(status().isCreated());
        // Same key, same body, sent again -- must return the SAME transaction,
        // not create a second one.
        mockMvc.perform(post("/api/v1/transactions").contentType("application/json").content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/accounts/" + cashId))
                .andExpect(jsonPath("$.balanceMinor").value(1200)); // NOT 2400
    }

    @Test
    void concurrentPostsAgainstTheSameAccountPairDoNotLoseUpdates() throws Exception {
        UUID cashId = createAccount("Cash Concurrent", AccountType.ASSET);
        UUID revenueId = createAccount("Revenue Concurrent", AccountType.REVENUE);
        int n = 20;
        CompletableFuture<?>[] tasks = new CompletableFuture[n];
        for (int i = 0; i < n; i++) {
            final int idx = i;
            tasks[i] = CompletableFuture.runAsync(() -> {
                try {
                    PostTransactionRequest request = new PostTransactionRequest(
                            "it-concurrent-" + idx + "-" + UUID.randomUUID(), "concurrent",
                            List.of(
                                    new PostEntryRequest(cashId, EntryDirection.DEBIT, 100),
                                    new PostEntryRequest(revenueId, EntryDirection.CREDIT, 100)
                            ));
                    mockMvc.perform(post("/api/v1/transactions")
                                    .contentType("application/json")
                                    .content(objectMapper.writeValueAsString(request)))
                            .andExpect(status().isCreated());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
        CompletableFuture.allOf(tasks).join();

        // Pessimistic locking (AccountRepository.findWithLockById) must
        // serialize these so no update is lost: total must be exactly
        // n * 100, not less.
        mockMvc.perform(get("/api/v1/accounts/" + cashId))
                .andExpect(jsonPath("$.balanceMinor").value(n * 100));
    }

    private UUID createAccount(String name, AccountType type) throws Exception {
        String body = objectMapper.writeValueAsString(new com.ledgerline.api.CreateAccountRequest(name, type, "USD"));
        String response = mockMvc.perform(post("/api/v1/accounts")
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).get("id").asText());
    }
}
