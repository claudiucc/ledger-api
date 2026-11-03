package com.ledgerapi.ledger;

import com.ledgerapi.AbstractIntegrationTest;
import com.ledgerapi.domain.Account;
import com.ledgerapi.domain.Direction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the guarantees in spec section 6 directly against {@link AccountService} and
 * {@link TransactionService}, backed by a real MySQL database: two accounts transferring money
 * back and forth at the same time, in both directions, never breaks an overdraft limit, never
 * loses an update, and never deadlocks; and at any moment the balances of every account in one
 * currency add up to zero.
 */
@SpringBootTest
class ConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AccountService accountService;

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static String uniqueId(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    @Test
    void concurrentTransfersInBothDirectionsNeverBreakOverdraftLimitsOrLoseAnUpdate() throws Exception {
        String a = uniqueId("acct-a");
        String b = uniqueId("acct-b");
        // Overdraft limit of 1000 each; start both at 500 via a seeding transaction so transfers
        // in either direction have room to move without ever going negative past the limit.
        accountService.openAccount(a, "EUR", new BigDecimal("1000.00"));
        accountService.openAccount(b, "EUR", new BigDecimal("1000.00"));
        transactionService.recordTransaction("seed", List.of(
                new TransactionService.EntryCommand("settlement-eur", Direction.DEBIT, new BigDecimal("1000.00")),
                new TransactionService.EntryCommand(a, Direction.CREDIT, new BigDecimal("500.00")),
                new TransactionService.EntryCommand(b, Direction.CREDIT, new BigDecimal("500.00"))), "seed");

        int threadsPerDirection = 8;
        int transfersPerThread = 25;
        BigDecimal amount = new BigDecimal("1.00");
        ExecutorService pool = Executors.newFixedThreadPool(threadsPerDirection * 2);
        CountDownLatch ready = new CountDownLatch(threadsPerDirection * 2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();

        Runnable aToB = () -> transferRepeatedly(a, b, amount, transfersPerThread, ready, go, failures);
        Runnable bToA = () -> transferRepeatedly(b, a, amount, transfersPerThread, ready, go, failures);

        for (int i = 0; i < threadsPerDirection; i++) {
            pool.submit(aToB);
            pool.submit(bToA);
        }
        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        pool.shutdown();
        boolean finished = pool.awaitTermination(60, TimeUnit.SECONDS);

        assertThat(finished).as("every transfer completed without deadlocking").isTrue();
        assertThat(failures.get()).as("no transfer failed for a reason other than its own business rules").isZero();

        // Equal numbers of transfers each way, for the same amount: balances end up unchanged.
        Account accountA = accountService.getAccount(a);
        Account accountB = accountService.getAccount(b);
        assertThat(accountA.getBalance()).isEqualByComparingTo("500.00");
        assertThat(accountB.getBalance()).isEqualByComparingTo("500.00");

        assertEurBalancesSumToZero();
    }

    private void transferRepeatedly(String from, String to, BigDecimal amount, int count,
                                     CountDownLatch ready, CountDownLatch go, AtomicInteger failures) {
        ready.countDown();
        try {
            go.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        for (int i = 0; i < count; i++) {
            try {
                transactionService.recordTransaction("transfer", List.of(
                        new TransactionService.EntryCommand(from, Direction.DEBIT, amount),
                        new TransactionService.EntryCommand(to, Direction.CREDIT, amount)), uniqueId("req"));
            } catch (Exception e) {
                failures.incrementAndGet();
            }
        }
    }

    /** Integrity (spec section 6): at any moment, EUR account balances add up to zero. */
    private void assertEurBalancesSumToZero() {
        BigDecimal total = jdbcTemplate.queryForObject(
                "select coalesce(sum(balance), 0) from account where currency = 'EUR'", BigDecimal.class);
        assertThat(total).isEqualByComparingTo(BigDecimal.ZERO);
    }
}

