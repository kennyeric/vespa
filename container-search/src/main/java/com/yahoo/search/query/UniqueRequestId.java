package com.yahoo.search.query;

import com.yahoo.container.Server;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A query id which is unique across this cluster - consisting of
 * container runtime id + timestamp + serial.
 *
 * @author bratseth
 */
public class UniqueRequestId {

    private static final String serverId = Server.get().getServerDiscriminator();
    private static final AtomicLong sequenceCounter = new AtomicLong();

    private final String id;

    private UniqueRequestId(String serverId, long timestamp, long sequence) {
        this.id = serverId + "." + timestamp + "." + sequence;
    }

    @Override
    public String toString() { return id; }

    /**
     * Creates a session id which is unique across the cluster this runtime is a member of each time this is called.
     * Calling this causes synchronization.
     */
    public static UniqueRequestId next() {
        return new UniqueRequestId(serverId, System.currentTimeMillis(), sequenceCounter.getAndIncrement());
    }
}
