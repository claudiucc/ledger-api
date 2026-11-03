package com.ledgerapi.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EntryRepository extends JpaRepository<Entry, String> {

    @Query("select e from Entry e where e.accountId = :accountId order by e.transaction.recordedAt desc, e.id desc")
    Page<Entry> findByAccountIdOrderByNewestFirst(@Param("accountId") String accountId, Pageable pageable);

    @Query("""
            select e from Entry e
            where e.accountId = :accountId and e.transaction.recordedAt <= :asOf
            order by e.transaction.recordedAt desc, e.id desc
            """)
    Page<Entry> findAsOf(@Param("accountId") String accountId, @Param("asOf") java.time.Instant asOf, Pageable pageable);
}

