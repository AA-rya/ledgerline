package com.ledgerline.api;

import com.ledgerline.domain.Account;
import com.ledgerline.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
        Account account = accountService.createAccount(request);
        AccountResponse body = AccountResponse.from(account);
        return ResponseEntity.created(URI.create("/api/v1/accounts/" + account.getId())).body(body);
    }

    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable UUID id) {
        return AccountResponse.from(accountService.getAccount(id));
    }
}
