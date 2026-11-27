package com.lyreo.platform.config.application;

import java.util.UUID;

/**
 * Durable event-consumer deduplication receipt store.
 */
public interface ConsumerReceiptStore {
    /**
     * Records {@code (consumer, factId)} atomically inside the active transaction.
     *
     * @return {@code true} if this is the first time the fact is recorded for this consumer;
     *     {@code false} if it was already processed.
     */
    boolean tryRecord(String consumer, UUID factId);
}
