package com.ledgerapi.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, String> {

    /**
     * Locks the account row for the duration of the current transaction. Callers must always
     * lock the accounts touched by a transaction in a globally consistent order (e.g. sorted by
     * id) so that concurrent transfers in opposite directions (A->B and B->A) can never deadlock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> lockById(String id);
}

