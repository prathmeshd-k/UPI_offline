package com.demo.upimesh.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
This service prevents the same payment packet from being processed multiple times.
In payment systems, duplicate processing is one of the most dangerous problems.


 */
@Service
public class IdempotencyService {

    private final Map<String, Instant> seen = new ConcurrentHashMap<>();
/*Stores:

Packet Hash → First Seen Time

Example:

3f8a9e5c...
    ↓
2026-06-16T10:15:00
 */


    @Value("${upi.mesh.idempotency-ttl-seconds:86400}")
    private long ttlSeconds;

    /**
     * Try to claim a hash. Returns true if this caller is the first; false if
     * someone else already claimed it (i.e. the packet is a duplicate).
     */
    public boolean claim(String packetHash) {
        Instant now = Instant.now(); //Stores current timestamp.
        Instant prev = seen.putIfAbsent(packetHash, now); //This is the heart of the service.
        return prev == null;
    }

    public int size() {
        return seen.size();
    }

    /** Periodically evict entries past their TTL so the map doesn't grow forever. */
    @Scheduled(fixedDelay = 60_000)
    public void evictExpired() {
        Instant cutoff = Instant.now().minusSeconds(ttlSeconds);
        seen.entrySet().removeIf(e -> e.getValue().isBefore(cutoff));
    }

    /** Test/demo helper. */
    public void clear() {
        seen.clear();
    }
}

/*idempotency layer to prevent duplicate settlement of offline mesh payment packets. 
The service uses atomic ConcurrentHashMap.putIfAbsent() semantics to guarantee that only 
the first arrival of a packet hash is processed, while duplicates are rejected. 
A scheduled TTL-based eviction mechanism prevents unbounded memory growth. 
In production, the same design can be implemented using Redis SETNX with expiration. 
*/