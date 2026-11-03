package com.ledgerapi.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<Transaction, String> {

    boolean existsByReversalOfId(String reversalOfId);
}

