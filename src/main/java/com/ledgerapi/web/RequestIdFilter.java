package com.ledgerapi.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Gives every HTTP request a request id - the caller's own {@code X-Request-Id} if it sent one,
 * otherwise a freshly generated one - and puts it in the logging MDC (see the "requestId" token
 * in the log pattern) and echoes it back as a response header. This is what lets one request be
 * followed through the logs, and on into the transaction it recorded and the event it announced:
 * the id flows from here into {@code TransactionService}, onto the {@code ledger_transaction.request_id}
 * column and into the outbox payload published to Kafka.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = request.getHeader(HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    public static String currentRequestId() {
        return MDC.get(MDC_KEY);
    }
}

