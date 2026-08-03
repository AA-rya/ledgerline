package com.ledgerline.service;

import com.ledgerline.api.CreateAccountRequest;
import com.ledgerline.domain.Account;
import com.ledgerline.exception.AccountNotFoundException;
import com.ledgerline.repository.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Transactional
    public Account createAccount(CreateAccountRequest request) {
        Account account = new Account(UUID.randomUUID(), request.name(), request.accountType(), request.currency());
        return accountRepository.save(account);
    }

    @Transactional(readOnly = true)
    public Account getAccount(UUID id) {
        return accountRepository.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
    }
}
