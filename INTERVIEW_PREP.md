# 🎤 Interview Questions & Answers - UPI Offline Mesh

## Minimum Viable Explanations (30-60 seconds)

### Q1: What does this project do?
**A:** It simulates offline UPI payments. When you're offline in a basement, your encrypted payment hops device-to-device via Bluetooth mesh until someone's phone gets 4G and uploads it to our backend. We decrypt, deduplicate (check if we've seen it before), and settle the payment by debiting the sender and crediting the receiver.

---

### Q2: What's the biggest technical challenge here?
**A:** **Idempotency.** If the same encrypted packet reaches our backend simultaneously via 3 different bridge nodes, we must settle it exactly once. We solve this with a hash of the ciphertext as an idempotency key:
1. First bridge requests → we claim the hash (atomic, using ConcurrentHashMap.putIfAbsent)
2. Other 2 bridges → we detect duplicate, send back "already processed"
3. Backup: DB unique constraint prevents double insertion if somehow both threads succeed

---

### Q3: Why hybrid encryption (RSA + AES)?
**A:** RSA can only encrypt ~245 bytes. Our payment JSON is >245 bytes. So:
- Generate fresh AES key per packet
- Encrypt JSON with AES-GCM (fast, no size limit, authenticated)
- Encrypt just the AES key with server's public RSA-2048 key
- Wire: [RSA-encrypted AES key (256B)][GCM IV (12B)][AES ciphertext + auth tag]

This is how TLS, PGP, Signal, and most real systems do it. RSA for key exchange, AES for payload.

---

### Q4: What happens if someone modifies the ciphertext?
**A:** AES-GCM has an authentication tag (16 bytes). Any modification —even 1 bit— makes the tag invalid. Decryption throws an exception. We catch it and return "INVALID:decryption_failed". Money never moves.

---

### Q5: What stops replay attacks?
**A:** Each PaymentInstruction has a `signedAt` timestamp. Server rejects if:
- Too old: `now - signedAt > 24 hours` → reject
- Too new: `signedAt - now > 5 minutes` → reject (clock skew tolerance)

Also, nonce (UUID per payment) ensures even if Alice legitimately sends Bob ₹100 twice, the plaintext differs (nonce differs) → ciphertext differs → hash differs → not a duplicate.

---

## Intermediate Level (2-5 minutes)

### Q6: Walk through the complete settlement process.
**A:**
1. **Receive:** POST /api/bridge/ingest receives { packetId, ciphertext, ttl }
2. **Hash:** packetHash = SHA-256(ciphertext)
3. **Idempotency:** idempotency.claim(packetHash)
   - putIfAbsent returns null (first time) → continue
   - putIfAbsent returns non-null (duplicate) → return DUPLICATE_DROPPED
4. **Decrypt:** 
   - Extract RSA-encrypted AES key from ciphertext prefix
   - Decrypt with server's private key → get AES session key
   - Extract GCM IV and ciphertext remainder
   - Decrypt with AES-GCM → get plaintext PaymentInstruction
   - (If GCM fails → return INVALID)
5. **Freshness:** Check signedAt is within [-300s, +24h]
6. **Settlement (in @Transactional context):**
   - Load sender, receiver accounts (with version lock)
   - Validate sender has balance
   - Debit sender, credit receiver
   - Increment version on both (optimistic locking)
   - Save accounts
   - Create Transaction record (packetHash as unique key)
   - Commit (all-or-nothing)
7. **Return:** IngestResult(outcome="SETTLED", transactionId)

---

### Q7: What's @Transactional doing?
**A:** It wraps the entire settlement method in a database transaction:
- If ANY exception occurs anywhere → entire transaction rolls back
- If success → commit
- So debit + credit happen atomically. No partial states (debited but not credited).

Example:
```java
@Transactional
public Transaction settle(...) {
    sender.balance -= amount;     // Line 1
    receiver.balance += amount;   // Line 2
    accounts.save(sender);        // Line 3
    accounts.save(receiver);      // Line 4 → if this fails, all 4 lines rollback
}
```

---

### Q8: What's @Version doing?
**A:** Optimistic locking. Prevents lost updates on concurrent access.

Scenario without @Version:
```
Thread 1: SELECT balance = 1000, version = 5
Thread 2: SELECT balance = 1000, version = 5
Thread 1: UPDATE balance = 900, version = 6
Thread 2: UPDATE balance = 900, version = 6
Result: Both transactions reduced balance by 100 (should be 800)
```

Scenario with @Version:
```
Thread 1: SELECT balance = 1000, version = 5
Thread 2: SELECT balance = 1000, version = 5
Thread 1: UPDATE WHERE version = 5 SET balance = 900, version = 6 ✓
Thread 2: UPDATE WHERE version = 5 SET balance = 900, version = 6
        → WHERE clause fails (version is now 6, not 5)
        → OptimisticLockException thrown
Result: Thread 1 succeeds, Thread 2 fails (no lost update)
```

---

### Q9: Walk through the mesh gossip process.
**A:**
1. Alice creates packet, injects into mesh at her phone (phone-alice)
2. One round of gossip: Every device shares with every other device, TTL -= 1
   - phone-alice holds [packet TTL=5]
   - After round 1: all devices have [packet TTL=4]
   - After round 2: all devices have [packet TTL=3]
   - ... until round 5: all devices have [packet TTL=0]
3. When phone-bridge holds the packet, call /api/mesh/flush
   - phone-bridge POST the packet to /api/bridge/ingest
   - Backend decrypts and settles

Real Bluetooth Mesh: Would be pairwise (pair up when near), not all-to-all. Our simulator is fast-forward of multiple rounds.

---

### Q10: If the same packet arrives from bridges simultaenously, walk through what happens (thread-level).
**A:**
```
Thread 1 (Bridge A):                    Thread 2 (Bridge B):
----------------------------------      ------------------------------------
receives packet X                       receives packet X (same)
hash = SHA256(X.ciphertext)             hash = SHA256(X.ciphertext)
                                        (same hash, since ciphertext identical)
idempotency.claim(hash)                 idempotency.claim(hash)
  → putIfAbsent(hash, now)                 (waits for Thread 1 to complete)
  → returns null                          → putIfAbsent(hash, now)
  → Thread 1 wins!                        → returns the Instant from Thread 1
  → returns true                          → returns false
[Continue to settlement]                [Return DUPLICATE_DROPPED]

        ↓                                   
   settlement() runs                   [Response sent, method exits]
   accounts updated
   transaction inserted
   ↓
[Response sent: SETTLED]
```

Key: `ConcurrentHashMap.putIfAbsent()` is atomic. Can't have a race condition. Only 1 thread wins.

---

## Advanced Level (5+ minutes)

### Q11: What's the complete cryptographic flow?

**SENDER (Offline, encrypting):**
```
PaymentInstruction plaintext = {
  senderVpa: "alice@demo",
  receiverVpa: "bob@demo",
  amount: 500.00,
  pinHash: SHA256("1234"),
  nonce: UUID.randomUUID(),
  signedAt: System.currentTimeMillis()
}

json = ObjectMapper.writeValueAsBytes(plaintext)  // ~200 bytes

// Step 1: Generate per-packet AES key
aesKey = KeyGenerator.getInstance("AES").generateKey()  // 256-bit random

// Step 2: Encrypt payload with AES-GCM
iv = new byte[12]
secureRandom.nextBytes(iv)

cipher = Cipher.getInstance("AES/GCM/NoPadding")
cipher.init(ENCRYPT_MODE, aesKey, new GCMParameterSpec(128, iv))
aes_ciphertext = cipher.doFinal(json)  // includes auth tag

aesCiphertextWithIv = concat(iv, aes_ciphertext)  // [12 + encrypted]

// Step 3: Encrypt AES key with server's public RSA key
rsaCiphertext = cipher.getInstance("RSA/ECB/OAEPWithSHA256...")
rsaCiphertext.init(ENCRYPT_MODE, serverPublicKey)
rsa_encrypted_aes = rsaCiphertext.doFinal(aesKey.getEncoded())  // 256 bytes

// Step 4: Combine & wrap in MeshPacket
wireFormat = concat(rsa_encrypted_aes, aesCiphertextWithIv)  // total ~500 bytes
base64Wire = Base64.encode(wireFormat)

meshPacket = new MeshPacket()
meshPacket.ciphertext = base64Wire
meshPacket.ttl = 5
```

**SERVER (Online, decrypting):**
```
// Receive ciphertext from bridge
wireFormatBinary = Base64.decode(meshPacket.ciphertext)

// Step 1: Extract RSA-encrypted AES key (first 256 bytes)
rsa_encrypted_aes = wireFormatBinary[0:256]

// Step 2: Decrypt with server's private key
rsaCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA256...")
rsaCipher.init(DECRYPT_MODE, serverPrivateKey)
aesKeyBytes = rsaCipher.doFinal(rsa_encrypted_aes)
aesKey = new SecretKeySpec(aesKeyBytes, 0, aesKeyBytes.length, "AES")

// Step 3: Extract GCM components
iv = wireFormatBinary[256:268]
aesCiphertext = wireFormatBinary[268:]

// Step 4: Decrypt with AES-GCM
aesCipher = Cipher.getInstance("AES/GCM/NoPadding")
aesCipher.init(DECRYPT_MODE, aesKey, new GCMParameterSpec(128, iv))
plaintext = aesCipher.doFinal(aesCiphertext)  // throws exception if tampered

instructionJson = new String(plaintext)
instruction = ObjectMapper.readValue(instructionJson, PaymentInstruction.class)
```

**Key insight:** The ciphertext itself (from Base64 decode onwards) is deterministic. Same plaintext (including nonce) → same ciphertext → same hash → idempotency works.

---

### Q12: What if the server's private key is compromised?
**A:** Attacker can:
1. Decrypt all historical payments ✗
2. Forge new payments (sign with private key) ✗

Mitigations:
- **Production:** Store private key in HSM (Hardware Security Module), never in-memory
- **Rotation:** Periodic key rotation, old ciphertexts might temporarily become readable but not forgeable
- **Audit:** Every decryption logged + monitored
- **Signatures:** Add digital signature layer (not just encryption) so forgery is detected

---

### Q13: What if the idempotency cache fails (e.g., OOM, corruption)?
**A:** We have fallback layers:
1. **Layer 1 falls off:** Packet reaches settlement code twice
2. **Layer 2: Optimistic locking** → Second attempt's `accounts.save()` throws OptimisticLockException (version mismatch)
3. **Layer 3: DB unique constraint** → Second `transactions.save(tx)` violates unique(packetHash) → DB exception

Either way, payment settles exactly once. This is "defense in depth."

---

### Q14: How would you scale this to million TPS?
**A:**
1. **Idempotency cache:** Replace ConcurrentHashMap with Redis Cluster (distributed, sub-millisecond)
   - `SETNX key 1 EX 86400` (Redis SET-if-Not-eXists)
2. **Database:** Sharding by (packetHash % N), PostgreSQL replicas for reads
3. **Encryption:** Move RSA decryption to dedicated crypto service (possibly HSM)
4. **Settlement:** Connection pooling (HikariCP, max 100 connections), batch insert transactions
5. **Events:** Publish "SETTLED" event to Kafka for downstream consumption (balance updates, notifications)
6. **Monitoring:** Per-endpoint latency, per-bridge success rate, idempotency hit rate

In reality, payments are I/O bound (DB latency ~10ms), so you'd max out at ~100 TPS per server before needing sharding.

---

### Q15: What's the weakest link in the system?
**A:** The **nonce**. If two legitimate payments had the same nonce:
```
Payment 1: (alice, bob, 500, nonce=uuid-X, signedAt=T1)
Payment 2: (alice, bob, 500, nonce=uuid-X, signedAt=T1)  // Same nonce!
```
→ Both encrypt to identical ciphertext
→ Same hash
→ Second one rejected as duplicate ✗

**Solution:** UUID is globally unique (timestamp + MAC + random), so collision probability is ~1 in 2^128. In practice, zero. But if you're paranoid:
- Prepend server's counter: `nonce = serverCounter++ + "_" + UUID`
- Server guarantees monotonicity

---

### Q16: Can a bridge node do selective censorship?
**A:** Yes. Bridge node can:
1. Decide not to upload certain packets ("I won't deliver from Alice")
2. Delay packets (hold them)
3. Duplicate packets (upload same packet 3 times)

Our system **can't prevent 1 & 2** (bridge is trusted intermediary). But we **prevent harm from 3:**
- Duplicate uploads → idempotency deduplicates

In real BLE mesh:
- Multiple bridges → packet likely reaches one honest bridge
- End-to-end acknowledgment ("payment settled" message back to sender)

---

### Q17: What if `signedAt` is in the future by days?
**A:** Server rejects with "future_dated". This prevents:
- Clock-skewed device sending payment 10 days in the future
- Spoofed timestamp attacks

The 5-minute tolerance is for small clock errors (NTP drift, etc.).

---

### Q18: What's the difference between encryption, authentication, and non-repudiation?

| Property | Handled By | Purpose |
|----------|-----------|---------|
| **Privacy** (can't read) | RSA + AES encryption | Bridge nodes can't read payment |
| **Authentication** (not tampered) | AES-GCM tag | If bridge modifies ciphertext, tag fails |
| **Non-repudiation** (can't deny) | Digital signature (SHA-256 HMAC or RSA sign) | Sender can't deny they sent it |

Our system has 1 & 2, but NOT 3 (no signature, just encryption).

**To add non-repudiation:**
```java
// Sender
signature = RSA_Sign(plaintext, senderPrivateKey)

// Wire format
[RSA_Encrypted_AES_Key][IV][AES_Ciphertext][Signature]

// Server
plaintext = decrypt(...)
isValid = RSA_Verify(plaintext, signature, senderPublicKey)
```

Then sender can't deny they sent it (only their key could create that signature).

---

### Q19: What happens if two accounts merge or one account is deleted?
**A:** 
- **Account deleted:** FK constraint prevents deletion if transactions reference it
- **Account merged:** Create new VPA, migrate transactions (but old ones reference old VPA, that's fine for audit)

Real banks handle this with:
1. Account versioning (vpa v1, vpa v2, etc.)
2. Immutable transaction ledger (never modify, only append)
3. Soft deletes (mark deleted, don't actually remove)

---

### Q20: Give me a failure scenario and walk through recovery.
**A:** **Scenario:** Bridge uploads packet at T1, server crashes at T2 after decrypt but before settlement.

```
T1: Bridge POST /api/bridge/ingest
T2: BridgeIngestionService decrypts successfully
T3: IdempotencyService.claim() succeeds → true (claim recorded)
T4: SettlementService.settle() starts
T5: Loads sender, updates balance
T6: Loads receiver, updates balance
T7: Creates Transaction record
T8: CRASH (before @Transactional commits)

RESULT: 
- Idempotency cache has claim (in-memory, lost on crash)
- Settlement never happened (no DB changes, transaction rolled back)
- Sender balance unchanged
- Transaction record not inserted

RECOVERY:
- T+10s: Bridge retries (after timeout)
- claim(hash) called again
  - Cache is empty (server restarted) → returns true
  - Wait, this allows double-settlement!

FIX:
- Have persistent idempotency cache (Redis)
- Even after server restart, Redis still has the claim → returns false
- Bridge gets "DUPLICATE_DROPPED" → knows to stop retrying
OR
- Transaction table has unique constraint on packetHash
  - If settlement somehow ran twice, 2nd INSERT fails → no double debit/credit
```

**Lesson:** Idempotency must be durable (survive crashes). In-memory cache is not enough for production.

---

## Tough Questions

### Q21: "Your system assumes the server's clock is correct. What if someone runs the server with wrong system time?"
**A:** Good catch. If server time is 2 days in future:
- All legitimate payments (signedAt = now) appear to be 2 days old
- Rejected as stale

Similarly, if server time is 2 days in past, payments from real time appear future-dated.

**Fix:**
- NTP-sync (OS should have this)
- Monitoring: alert if server time ever drifts > 60s
- Client-side tolerance: "Accept payments from past 7 days to future 7 days" (relaxed window)

---

### Q22: "What if the encryption algorithm gets broken?"
**A:** RSA-2048 and AES-256 are currently considered secure (as of 2025).
- AES-256: Even with quantum computers, would require 2^128 operations (brute force)
- RSA-2048: Vulnerable to Shor's algorithm on mature quantum computer (probably 10+ years away)

**Mitigation:**
- Transition to post-quantum cryptography (lattice-based, etc.)
- Periodically re-encrypt old payments with new algorithm
- Hybrid encryption: use RSA-2048 + post-quantum key exchange together

---

### Q23: "What if database constraints are accidentally disabled?"
**A:** Then we lose layer 3 of defense. But we're not helpless:
- Layer 1 still catches most duplicates (idempotency cache)
- Layer 2 still prevents concurrent updates (optimistic locking)
- Only if idempotency cache fails AND optimistic locking fails would we have a problem

**Fix:**
- Tests that verify constraints are in place
- Enable foreign key constraints in H2/Postgres config
- Regular audits: `SELECT COUNT(DISTINCT packetHash) FROM transactions WHERE status='SETTLED'` should equal number of settled transactions

---

## Final Wisdom

**Q: In one sentence, what's the core insight of this system?**

**A:** *Idempotency is a property of the hash, not the request. As long as hashing is deterministic, sending the same encrypted packet 100 times results in the same hash 100 times, allowing deduplication even across retries and failures.*

---

**Q: What would you do differently in production?**

**A:**
1. Redis for idempotency (not in-memory)
2. HSM for private key (not in-memory)
3. PostgreSQL with replicas (not H2)
4. Digital signatures in addition to encryption (non-repudiation)
5. Rate limiting (prevent abuse)
6. Audit logging (every transaction logged immutably)
7. Real Bluetooth stack (not simulator)
8. End-to-end packet acknowledgment (sender gets proof of settlement)
9. Multi-signature (e.g., bridge authorizes payment on behalf of sender)
10. Monitoring + alerting (latency, error rate, duplicate rate)

