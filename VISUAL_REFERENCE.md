# 📋 UPI Offline Mesh - Quick Visual Reference

## System Architecture

```
┌────────────────┐          ┌─────────────────┐          ┌──────────────────┐
│   SENDER       │          │  MESH NETWORK   │          │   BRIDGE NODE    │
│   (Offline)    │ encrypt  │  (Untrusted)    │ gossip   │  (Has 4G)        │
│   phone-alice  │─────────▶│  hop-hop-hop    │─────────▶│  phone-bridge    │
│                │  packet  │  TTL decrement  │          │                  │
└────────────────┘          └─────────────────┘          └──────────────────┘
                                                                   │
                                                                   │ upload
                                                                   ▼
                                            ┌──────────────────────────────┐
                                            │      BACKEND SERVER          │
                                            │                              │
                                            │  /api/bridge/ingest endpoint │
                                            │                              │
                                            │  ┌────────────────────────┐  │
                                            │  │ 1. Hash ciphertext     │  │
                                            │  │ 2. Check idempotency   │  │
                                            │  │ 3. Decrypt (RSA+AES)   │  │
                                            │  │ 4. Validate freshness  │  │
                                            │  │ 5. Settlement          │  │
                                            │  └────────────────────────┘  │
                                            │                              │
                                            │  ┌────────────────────────┐  │
                                            │  │  Database (H2/Postgre) │  │
                                            │  │ • Accounts (ledger)    │  │
                                            │  │ • Transactions (log)   │  │
                                            │  └────────────────────────┘  │
                                            └──────────────────────────────┘
```

---

## Payment Settlement Pipeline (Detailed)

```
┌─────────────────────────────────────────────────────────────┐
│ 1. RECEIVE PACKET                                           │
│ POST /api/bridge/ingest                                     │
└─────────────────────┬───────────────────────────────────────┘
                      │ MeshPacket { packetId, ciphertext, ttl }
                      ▼
┌─────────────────────────────────────────────────────────────┐
│ 2. GENERATE IDEMPOTENCY KEY                                 │
│ packetHash = SHA256(ciphertext)                             │
└─────────────────────┬───────────────────────────────────────┘
                      │ e.g., "abc123def456..."
                      ▼
┌─────────────────────────────────────────────────────────────┐
│ 3. CHECK IDEMPOTENCY CACHE                                  │
│ idempotency.claim(packetHash)                               │
└────────┬────────────────────────────────────┬───────────────┘
         │ true (first time)                  │ false (duplicate)
         │                                     │
         ▼                                     ▼
    [Continue]                         [Return DUPLICATE_DROPPED]
         │
         ▼
┌─────────────────────────────────────────────────────────────┐
│ 4. DECRYPT PAYLOAD                                          │
│ RSA private key decrypts AES-256 key                        │
│ AES-256-GCM decrypts and authenticates plaintext           │
│ Extract PaymentInstruction { sender, receiver, amount ...}  │
└────────┬────────────────────────────────────┬───────────────┘
         │ Success                            │ Tampered/Bad
         │                                     │
         ▼                                     ▼
    [Continue]                    [Return INVALID:decryption_failed]
         │
         ▼
┌─────────────────────────────────────────────────────────────┐
│ 5. FRESHNESS CHECK (Replay Protection)                      │
│ ageSeconds = (now - instruction.signedAt)                   │
│ Is ageSeconds between -300 and +86400 seconds?              │
└────────┬────────────────────────────────────┬───────────────┘
         │ Valid time range                   │ Too old/future
         │                                     │
         ▼                                     ▼
    [Continue]                [Return INVALID:stale_packet|future_dated]
         │
         ▼
┌─────────────────────────────────────────────────────────────┐
│ 6. SETTLEMENT (TRANSACTION CONTEXT START)                   │
│ @Transactional wrapper                                      │
└─────────────────┬───────────────────────────────────────────┘
                  │
                  ▼
         ┌────────────────────┐
         │ Load sender account │
         │ (with version lock) │
         └────────┬───────────┘
                  │
                  ▼
         ┌────────────────────────┐
         │ Load receiver account  │
         │ (with version lock)    │
         └────────┬───────────────┘
                  │
                  ▼
         ┌────────────────────────────────┐
         │ Validate amount > 0            │
         └────────┬─────────────┬─────────┘
                  │ OK          │ Bad
                  │             │
                  ▼             ▼
         [Continue]   [recordRejected()]
             │
             ▼
    ┌────────────────────────────┐
    │ sender.balance >= amount ? │
    └────────┬─────────┬─────────┘
             │ Yes      │ No
             │          │
             ▼          ▼
         [Continue] [recordRejected()]
             │
             ▼
    ┌──────────────────────────┐
    │ Debit:                   │
    │ sender.balance -= amount │
    │ Credit:                  │
    │ receiver.balance += amount│
    └────────┬─────────────────┘
             │
             ▼
    ┌──────────────────────────┐
    │ Save sender              │
    │ (version N → N+1)        │
    │ Save receiver            │
    │ (version M → M+1)        │
    └────────┬─────────────────┘
             │
             ▼
    ┌──────────────────────────────────┐
    │ Create Transaction record        │
    │ packetHash (unique constraint)   │
    │ status = SETTLED                 │
    └────────┬─────────────────────────┘
             │
             ▼
┌─────────────────────────────────────────────────────────────┐
│ 7. COMMIT TRANSACTION                                       │
│ All-or-nothing: everything succeeds or everything rolls back│
└─────────────────────────────────────────────────────────────┘
             │
             ▼
┌─────────────────────────────────────────────────────────────┐
│ 8. RETURN RESULT                                            │
│ IngestResult.settled(packetHash, transaction)              │
└─────────────────────────────────────────────────────────────┘
```

---

## Hybrid Encryption Wire Format

```
BEFORE ENCRYPTION (plaintext):
┌─────────────────────────────────────────┐
│ PaymentInstruction (JSON)               │
│ {                                       │
│   "senderVpa": "alice@demo",           │
│   "receiverVpa": "bob@demo",           │
│   "amount": 500.00,                    │
│   "pinHash": "sha256(...)",            │
│   "nonce": "uuid-...",                 │
│   "signedAt": 1715500800000            │
│ }                                       │
│ Size: ~200-300 bytes                   │
└─────────────────────────────────────────┘

ENCRYPTION PROCESS:
┌──────────────────────────────────────────────────────────────┐
│ Step 1: Generate fresh AES-256 session key (per packet)      │
│ aesSessionKey = random 256 bits                              │
└──────────────────────────────────────────────────────────────┘
              │
              ▼
┌──────────────────────────────────────────────────────────────┐
│ Step 2: Encrypt payload with AES-256-GCM                     │
│ - Generate 12-byte random IV                                 │
│ - Encrypt plaintext with AES + IV                            │
│ - AES-GCM produces: IV + ciptext + 16-byte auth tag          │
│ Output: ~300+ bytes                                          │
└──────────────────────────────────────────────────────────────┘
              │
              ▼
┌──────────────────────────────────────────────────────────────┐
│ Step 3: Encrypt AES key with Server's RSA-2048 public key   │
│ - Use RSA-OAEP-SHA256 padding                                │
│ - Only encrypt the 32-byte AES key (not the ciphertext)      │
│ Output: 256 bytes                                            │
└──────────────────────────────────────────────────────────────┘

AFTER ENCRYPTION (wire format, before base64):
┌─────────────────────────────────────┬────────────┬────────────────────────┐
│ RSA-encrypted AES key (256 bytes)   │ GCM IV     │ AES ciphertext + tag   │
│                                     │ (12 bytes) │                        │
├─────────────────────────────────────┼────────────┼────────────────────────┤
│ Encrypted with server's public key  │ Random IV  │ Contains plaintext +   │
│ Only holder of private key can read │            │ auth tag               │
│ this section                        │            │                        │
└─────────────────────────────────────┴────────────┴────────────────────────┘
                                      └─────────────────────────────────────┘
                                              Encrypted with AES session key
                                              Only RSA-decrypted AES key holder
                                              can read this

FINAL WIRE FORMAT (base64 encoded):
┌──────────────────────────────────────────────────────────────┐
│ Base64(                                                      │
│   [256 RSA-encrypted AES key] +                             │
│   [12 GCM IV] +                                             │
│   [~300 AES ciphertext+tag]                                 │
│ )                                                            │
│                                                              │
│ Total after base64: ~600-700 bytes                          │
└──────────────────────────────────────────────────────────────┘
```

---

## Gossip Protocol (Mesh Hopping)

```
INITIAL STATE (TTL = 5):
  
  phone-alice        phone-stranger1     phone-stranger2       phone-stranger3
  [packet:5]              [ ]                  [ ]                   [ ]
       |
       └─ ownership by possession of packet
  
  phone-bridge (has 4G)
       [ ]

GOSSIP ROUND 1 (Broadcast from alice):
  
  phone-alice        phone-stranger1     phone-stranger2       phone-stranger3
  [packet:5]         [packet:4]          [packet:4]            [packet:4]
       ↓                  ↓                   ↓                      ↓
     keeps              got copy            got copy              got copy
  
  phone-bridge (has 4G)
  [packet:4]
       ↓
     got copy (can now UPLOAD to server)

GOSSIP ROUND 2 (Broadcast from everyone):
  
  phone-alice        phone-stranger1     phone-stranger2       phone-stranger3
  [packet:5]         [packet:4]          [packet:4]            [packet:4]
       ↓                  ↓                   ↓                      ↓
     no change     may get from others  may get from others  may get from others
  
  phone-bridge (has 4G)
  [packet:4]
       ↓
     already has, no change

EVENTUAL STATE (TTL → 0):
  
  All devices have packet, but TTL = 0, so no more forwarding.
  phone-bridge holds encrypted packet, uploads to /api/bridge/ingest.
```

---

## Defense in Depth (3 Layers)

```
ATTACK SCENARIO: Same encrypted payment reaches 3 bridges simultaneously

┌─────────────────────────────────────────────────────────────┐
│ LAYER 1: Idempotency Cache (In-Memory)                      │
│                                                             │
│ Hash = SHA256(ciphertext) = "abc123..."                    │
│ Thread 1: claim("abc123...") → true  ✓ wins               │
│ Thread 2: claim("abc123...") → false ✗ duplicate          │
│ Thread 3: claim("abc123...") → false ✗ duplicate          │
│                                                             │
│ Result: Only Thread 1 proceeds to settlement ✓             │
└─────────────────────────────────────────────────────────────┘
             │ If somehow idempotency fails (cache corruption, etc.)
             ▼
┌─────────────────────────────────────────────────────────────┐
│ LAYER 2: Optimistic Locking (@Version)                      │
│                                                             │
│ Thread 1: SELECT Account WHERE version=5                   │
│           UPDATE Account SET balance=X, version=6          │
│ Thread 2: SELECT Account WHERE version=5                   │
│           UPDATE Account SET balance=Y, version=6          │
│           → version is already 6, not 5 → ERROR ✓          │
│                                                             │
│ Result: Only Thread 1's debit/credit succeeds ✓            │
└─────────────────────────────────────────────────────────────┘
             │ If somehow both threads bypass version check
             ▼
┌─────────────────────────────────────────────────────────────┐
│ LAYER 3: Database Unique Constraint                         │
│                                                             │
│ CREATE TABLE transactions (                                │
│   packetHash VARCHAR(64) UNIQUE NOT NULL,                 │
│   ...                                                      │
│ )                                                          │
│                                                             │
│ Thread 1: INSERT INTO transactions VALUES (hash,  ...) ✓   │
│ Thread 2: INSERT INTO transactions VALUES (hash,  ...)    │
│           → UNIQUE constraint violation → ERROR ✓           │
│                                                             │
│ Result: Only 1 transaction record created ✓                │
└─────────────────────────────────────────────────────────────┘

BOTTOM LINE: 
  Payment settles EXACTLY ONCE, no matter how many 
  simultaneous or near-simultaneous attempts.
```

---

## Database Schema (Simplified)

```
TABLE accounts:
┌──────────┬──────────────┬────────────┬─────────┐
│ vpa (PK) │ holderName   │ balance    │ version │
├──────────┼──────────────┼────────────┼─────────┤
│ alice@.. │ Alice Kumar  │ 50000.00   │ 15      │
│ bob@..   │ Bob Singh    │ 75000.00   │ 8       │
│ charlie..│ Charlie Das  │ 120000.00  │ 3       │
│ dave@..  │ Dave Patel   │ 25000.00   │ 2       │
└──────────┴──────────────┴────────────┴─────────┘

TABLE transactions:
┌──────────────────┬────────────┬──────────────┬────────┬──────────────┬────────────┬──────────────┬─────────┐
│ id (PK)          │ packetHash │ senderVpa    │ rcvrVpa│ amount       │ signedAt   │ settledAt    │ status  │
├──────────────────┼────────────┼──────────────┼────────┼──────────────┼────────────┼──────────────┼─────────┤
│ 1                │ abc123...  │ alice@..     │ bob@.. │ 500.00       │ T1         │ T2           │ SETTLED │
│ 2                │ def456...  │ bob@..       │ charlie│ 1200.50      │ T3         │ T4           │ SETTLED │
│ 3                │ ghi789...  │ alice@..     │ dave@..│ -100.00      │ T5         │ T6           │ REJECTED│
└──────────────────┴────────────┴──────────────┴────────┴──────────────┴────────────┴──────────────┴─────────┘

TABLE idempotency_cache (In-Memory, not persisted):
┌────────────────┬──────────────────┐
│ packetHash     │ claimedAt        │
├────────────────┼──────────────────┤
│ abc123...      │ 2025-05-12 15:55 │
│ def456...      │ 2025-05-12 15:56 │
│ aaa000...      │ 2025-05-11 14:20 │ (expired, will be evicted)
└────────────────┴──────────────────┘

LIFECYCLE:
1. Payment created → hash → claim in cache
2. Decrypted → validated → settled in DB
3. Cache entry expires after 24 hours (TTL + periodic eviction)
4. Transaction record lives forever (append-only ledger)
```

---

## Key Insights for Interviews

**1. Idempotency is HARD**
- Naive approach: "Check if exists, then insert" ❌ (race condition)
- Correct approach: "Insert with unique key" ✓ (DB enforces)
- Optimized: "Cache + putIfAbsent before DB" ✓ (fast path, fallback to DB)

**2. Encryption is Not Just Privacy**
- RSA+AES: privacy from attackers
- AES-GCM: authentication (detects tampering)
- Hash: idempotency key (deterministic)

**3. Timestamps Are For Ordering, Not Identity**
- `signedAt`: When payment was created (offline)
- `settledAt`: When payment was processed (online)
- Freshness check: `signedAt` prevents replay, `settledAt` for audits

**4. @Transactional is All-or-Nothing**
- If ANY error → entire transaction rolls back
- Debit AND credit happen together, or neither
- No partial states possible

**5. Defense in Depth Matters**
- Never rely on single security check
- Idempotency cache might fail → DB unique constraint
- Encryption might fail → auth tag detection
- Version check might fail → unique constraint
- Multiple layers = system still works even if 1 fails
