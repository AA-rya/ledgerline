package com.ledgerline.api;

import com.ledgerline.domain.LedgerTransaction;
import com.ledgerline.service.LedgerService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {

    private final LedgerService ledgerService;

    public TransactionController(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @PostMapping
    public ResponseEntity<TransactionResponse> post(@Valid @RequestBody PostTransactionRequest request) {
        LedgerTransaction tx = ledgerService.postTransaction(request);
        TransactionResponse body = TransactionResponse.from(tx);
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/api/v1/transactions/" + tx.getId()))
                .body(body);
    }

    @GetMapping("/{id}")
    public TransactionResponse get(@PathVariable UUID id) {
        return TransactionResponse.from(ledgerService.getTransaction(id));
    }
}
