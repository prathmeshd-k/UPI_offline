# 📱 UPI Offline Mesh - Complete Project Guide

---

## 🎯 Project Overview

**What is this?** A Spring Boot backend that simulates **offline UPI (Unified Payments Interface) payments** routed through a Bluetooth mesh network.

**Real-world scenario:** You're in a basement with zero connectivity. You send your friend ₹500. Your phone encrypts it, broadcasts to nearby phones, packet hops device-to-device until someone walks outside, gets 4G, and uploads it to this backend. The backend decrypts, checks for duplicates, and settles the payment.

**What we built:** The server side of that system + a full mesh simulator so you can demo everything on one laptop without real Bluetooth.

---

## ✨ Key Features

### 1. **Hybrid Encryption (RSA + AES-GCM)**
   - **Problem it solves:** Untrusted intermediaries can't read/tamper with payment data
   - **How it works:**
     - Sender creates a `PaymentInstruction` (JSON) + encrypts with AES-256-GCM (fast)
     - The AES key is then encrypted with server's public RSA-2048 key (OAEP-SHA256)
     - Wire format: `[256 bytes RSA-encrypted AES key][12 bytes GCM IV][ciphertext + 16-byte tag]`
   - **Why hybrid?** RSA can only encrypt ~245 bytes. Our payment + metadata > 245 bytes, so we use AES for payload

### 2. **Idempotency (Duplicate Detection)**
   - **Problem:** Same payment might reach backend via 3 different bridge nodes simultaneously
   - **Solution:** Hash the ciphertext → use `ConcurrentHashMap.putIfAbsent()` (atomic, thread-safe)
   - **Contract:** `claim(hash)` returns `true` for first call, `false` afterward (within TTL)
   - **Production:** Would use Redis SETNX + TTL (same semantics, distributed)

### 3. **Replay Protection & Freshness Check**
   - **Problem:** Attacker intercepts old ciphertext, replays it days later
   - **Solution:** Each payment has `signedAt` timestamp, server rejects if > 24 hours old
   - Also allows 300-second clock skew tolerance (small devices might have wrong time)

### 4. **Optimistic Locking (Concurrency Control)**
   - **Problem:** Two threads try to debit same account simultaneously
   - **Solution:** `@Version` column on Account → JPA throws `OptimisticLockException` if version bumped
   - This is a defense-in-depth fallback (idempotency layer should catch it first)

### 5. **Mesh Simulator**
   - Shows how payments hop device-to-device
   - TTL (Time-To-Live) decrements at each hop
   - Demonstrates **gossip protocol** (all-to-all packet sharing)

---

## 📊 Data Models & Database Schema

### **Account** (simulated bank ledger)
```java
@Entity
@Table(name = "accounts")
public class Account {
    @Id
    String vpa;              // "alice@demo", "bob@demo", etc.
    String holderName;       // "Alice", "Bob"
    BigDecimal balance;      // Current balance (₹)
    @Version Long version;   // Optimistic locking
}
```

### **Transaction** (permanent settlement record)
```java
@Entity
@Table(name = "transactions", 
       indexes = @Index(name = "idx_packet_hash", 
                        columnList = "packetHash", unique = true))
public class Transaction {
    String packetHash;       // SHA-256 hash of ciphertext (idempotency key)
    String senderVpa;
    String receiverVpa;
    BigDecimal amount;
    Instant signedAt;        // When sender originally signed (offline)
    Instant settledAt;       // When backend processed it
    String bridgeNodeId;     // Which mesh node delivered it
    int hopCount;            // How many devices it passed through
    Status status;           // SETTLED or REJECTED
}
```

### **PaymentInstruction** (decrypted payload)
```java
public class PaymentInstruction {
    String senderVpa;
    String receiverVpa;
    BigDecimal amount;
    String pinHash;          // UPI PIN verification hash
    String nonce;            // UUID, unique per payment (prevents duplicate encryption)
    Long signedAt;           // Epoch millis
}
```

### **MeshPacket** (encrypted wire format)
```java
public class MeshPacket {
    String packetId;         // UUID
    String ciphertext;       // Base64-encoded hybrid-encrypted payload
    int ttl;                 // Time-to-live, decrements at each hop
    Long createdAt;          // When packet was created
}
```

---

## 🏗️ Architecture & Data Flow

### **The Three-Layer Stack**

```
┌─────────────────────────────────────────────────────────┐
│                    REST API Layer                       │
│  /api/server-key, /api/demo/send, /api/bridge/ingest   │
└─────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────┐
│                  Business Logic Layer                   │
│  BridgeIngestionService (orchestrator)                 │
│    ├─ HybridCryptoService (RSA+AES decrypt)           │
│    ├─ IdempotencyService (duplicate detection)         │
│    ├─ SettlementService (ledger updates)              │
│    └─ MeshSimulatorService (gossip simulation)        │
└─────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────┐
│              Persistence Layer (H2 Database)            │
│  Accounts, Transactions, JPA Repositories              │
└─────────────────────────────────────────────────────────┘
```

### **Complete Data Flow (End-to-End)**

```
1. SENDER (offline in basement)
   ├─ Creates PaymentInstruction (senderVpa, receiverVpa, amount, nonce, signedAt)
   ├─ Serializes to JSON
   ├─ Generates fresh AES-256 session key
   ├─ Encrypts JSON with AES-GCM (output: IV + ciphertext + tag)
   ├─ Encrypts AES key with server's public RSA key
   └─ Creates MeshPacket(packetId, ciphertext, ttl=5) → injects into mesh

2. MESH GOSSIP (untrusted intermediaries)
   ├─ Packet hops device-to-device (TTL decrements)
   ├─ No one can read/modify ciphertext (RSA + AES-GCM protect it)
   └─ Gossip round completes, packet reaches "bridge node" (has 4G)

3. BRIDGE UPLOADS TO SERVER
   └─ POST /api/bridge/ingest { "packetId": "...", "ciphertext": "..." }

4. SERVER RECEIVES (BridgeIngestionService)
   ├─ Hash ciphertext → "abc123..."
   ├─ Call idempotency.claim("abc123...")
   │   ├─ If already seen → return DUPLICATE_DROPPED
   │   └─ If first time → continue (claim successful)
   ├─ Decrypt ciphertext with server's private RSA key
   ├─ Extract AES key from RSA decryption
   ├─ Decrypt payload with AES-GCM
   ├─ If GCM auth fails → return INVALID (tampered)
   ├─ Check freshness: (now - signedAt) < 24 hours?
   │   ├─ If too old → return INVALID
   │   └─ If future-dated > 5 min → return INVALID
   └─ Call SettlementService.settle()

5. SETTLEMENT (SettlementService, wrapped in @Transactional)
   ├─ loadSender() @ accounts table, lock it (optimistic locking)
   ├─ loadReceiver() @ accounts table
   ├─ Check sender has sufficient balance
   ├─ If insufficient → recordRejected(), return REJECTED
   ├─ Debit sender: sender.balance -= amount
   ├─ Credit receiver: receiver.balance += amount
   ├─ Save both accounts (with version bump)
   ├─ Create Transaction record (packetHash as unique key)
   ├─ Commit transaction (all-or-nothing)
   └─ Return SETTLED

6. IDEMPOTENT SAFETY
   ├─ If sender crashes after debit but before recording transaction
   ├─ ... same ciphertext arrives via different bridge later
   ├─ → packetHash lookup finds existing Transaction record
   ├─ → DB unique constraint prevents duplicate insert anyway
   └─ Transaction is replayed exactly once ✓
```

---

## 🔐 Security Details (3 Hard Problems Solved)

### **Problem 1: Untrusted Intermediaries**
**Q: How do we stop bridge nodes from stealing payment data?**  
**A:** Hybrid encryption. The packet is encrypted end-to-end. Bridge nodes see ciphertext but can't decrypt (don't have server's private key). AES-GCM authenticated encryption means any tampering is detected immediately (authentication tag fails).

### **Problem 2: Simultaneous Delivery via Multiple Bridges**
**Q: Same payment reaches backend from 3 bridges at once. How do we prevent processing 3 times?**  
**A:** Idempotency key = SHA-256(ciphertext). The ciphertext is deterministic (same payment always produces same ciphertext because nonce is part of it). So hash is also deterministic. We use `ConcurrentHashMap.putIfAbsent()` — atomic check-and-set. Only 1 thread wins the claim, others see it's a duplicate.

Backup defense: Transaction table has unique constraint on `packetHash`. If somehow two claims both suceed, DB constraint prevents duplicate insert.

### **Problem 3: Replay Attacks**
**Q: Attacker intercepts old ciphertext from 2023, replays in 2026?**  
**A:** Freshness check. Each PaymentInstruction has `signedAt` timestamp. Server rejects if > 24 hours old. Also rejects if future-dated > 5 min (clock skew tolerance).

---

## 💻 Code Structure & Key Classes

### **1. HybridCryptoService** (Encryption/Decryption)
```java
// Encrypt plaintext JSON to hybrid ciphertext
byte[] encrypted = hybridCrypto.encrypt(paymentInstructionJson)

// Decrypt hybrid ciphertext back to plaintext
PaymentInstruction instruction = hybridCrypto.decrypt(ciphertext)

// Hash ciphertext for idempotency key
String hash = hybridCrypto.hashCiphertext(ciphertext)
```
**Tech:** RSA-2048-OAEP-SHA256, AES-256-GCM, SHA-256

---

### **2. BridgeIngestionService** (Orchestrator)
The main pipeline. Receives encrypted packet from bridge, orchestrates:
1. Hash the ciphertext
2. Check idempotency cache
3. Decrypt
4. Validate freshness
5. Settle
```java
public IngestResult ingest(MeshPacket packet, String bridgeNodeId, int hopCount) {
    // (see main flow above)
}
```

---

### **3. IdempotencyService** (Deduplication)
```java
@Scheduled(fixedDelay = 60_000)  // Cleanup every 60 seconds
public void evictExpired() {
    Instant cutoff = Instant.now().minusSeconds(ttlSeconds);  // 24 hours by default
    seen.entrySet().removeIf(e -> e.getValue().isBefore(cutoff));
}

public boolean claim(String packetHash) {
    Instant prev = seen.putIfAbsent(packetHash, Instant.now());
    return prev == null;  // true if first time, false if duplicate
}
```
**Key:** `ConcurrentHashMap.putIfAbsent()` is atomic (thread-safe).

---

### **4. SettlementService** (Ledger Updates)
```java
@Transactional  // All-or-nothing: both debit+credit succeed or both rollback
public Transaction settle(PaymentInstruction instruction, String packetHash, ...) {
    Account sender = accounts.findById(instruction.getSenderVpa())
        .orElseThrow();
    Account receiver = accounts.findById(instruction.getReceiverVpa())
        .orElseThrow();
    
    if (sender.getBalance() < instruction.getAmount()) {
        return recordRejected(...);  // Insufficient balance
    }
    
    // Debit AND credit (or neither if conflict)
    sender.setBalance(sender.getBalance().subtract(amount));
    receiver.setBalance(receiver.getBalance().add(amount));
    accounts.save(sender);  // @Version bump prevents lost updates
    accounts.save(receiver);
    
    Transaction tx = new Transaction();
    tx.setPacketHash(packetHash);  // Idempotency key
    tx.setStatus(SETTLED);
    transactions.save(tx);  // unique constraint on packetHash
    
    return tx;
}
```
**Key:** `@Transactional` + `@Version` + unique constraint = triple defense

---

### **5. MeshSimulatorService** (Gossip Protocol)**
Simulates Bluetooth mesh:
- 4 offline phones (basement)
- 1 bridge phone (has 4G)
- All hops together until TTL = 0

```java
public GossipResult gossipOnce() {
    // Take snapshot of what all devices hold
    // For each device: share packets with all other devices
    // TTL -= 1 per hop
    // Return gossip statistics
}
```

---

### **6. ApiController** (REST Endpoints)
```
GET  /api/server-key              → Fetch server's public RSA key
POST /api/demo/send               → Build & inject test packet
GET  /api/mesh/state              → Gossip network state
POST /api/mesh/gossip             → Execute one round of gossip
POST /api/mesh/flush              → Bridge uploads packets to server
GET  /api/accounts                → All accounts + balances
GET  /api/transactions            → All settled transactions
POST /api/bridge/ingest           → THE REAL PRODUCTION ENDPOINT
```

---

## 🐛 Problems Faced & Solutions

### **Problem 1: Java Record with Static Methods**
**Error:** `illegal start of expression` inside record
```java
// ❌ Wrong: Can't put static methods inside record body
public record IngestResult(...) {
    public static IngestResult settled() { ... }
}
```

**Solution:** Move static methods outside record
```java
// ✓ Correct: Record is simple, static methods at class level
public record IngestResult(...) { }
public static IngestResult settled(...) { ... }
```

---

### **Problem 2: Java 17 vs Java 8 Mismatch**
**Error:** `bad class file: class file has wrong version 61.0, should be 52.0`
- Spring Boot 3.3.5 requires Java 17
- But old Maven was using Java 8

**Solution:** Set `JAVA_HOME` to Java 17
```powershell
$env:JAVA_HOME = 'C:\Users\prath\.jdk\jdk-17.0.16'
```

---

### **Problem 3: Port 8080 Already in Use**
**Error:** `Port 8080 was already in use`

**Solution:** Start on different port
```powershell
$env:SERVER_PORT = '8081'
.\mvnw spring-boot:run
```

---

## 🎓 Interview Questions & Answers

### **Basic Level**

**Q1: What does this project do?**  
A: It simulates offline UPI payments routed through a Bluetooth mesh network. When you're offline, your payment is encrypted and hops device-to-device until someone's phone gets 4G and uploads it to our backend. The backend decrypts, deduplicates, and settles it.

**Q2: What is a UPI VPA?**  
A: Virtual Payment Address. Like an email for payments. Example: "alice@demo", "bob@upi". It uniquely identifies a bank account holder.

**Q3: Why use hybrid encryption (RSA + AES)?**  
A: RSA can only encrypt ~245 bytes. Our payment JSON > 245 bytes. So we use AES (fast, no size limit) for the payload, then encrypt the AES key with RSA (small). This is how TLS, PGP, Signal all work.

**Q4: What is the `packetHash`?**  
A: SHA-256 hash of the ciphertext. Used as idempotency key. Since the ciphertext is deterministic (same payment = same hash), we can detect duplicates.

**Q5: What does `@Transactional` do?**  
A: Wraps method in a database transaction. If ANY error occurs, the entire transaction rolls back. So debit + credit either both happen or neither.

---

### **Intermediate Level**

**Q6: How do you handle the scenario where the same payment arrives via 3 bridges simultaneously?**  
A: 
1. All 3 calls try to `claim(packetHash)` at the same instant
2. `ConcurrentHashMap.putIfAbsent()` is atomic — only 1 returns true
3. The other 2 get duplicate detected, return early
4. Settlement only runs once
5. Backup defense: `Transaction` table has unique constraint on `packetHash`

**Q7: What is optimistic locking? How does it prevent lost updates?**  
A: 
- Each `Account` has a `@Version Long` column
- When you load an account, you get row + version
- When you save it back, JPA checks: "is version still the same?"
- If another thread modified it, version changed → `OptimisticLockException`
- Prevents lost updates where two threads overwrite each other

**Example:**
```
Thread 1: Load sender, version=5
Thread 2: Load sender, version=5
Thread 1: Debit, save → version=6
Thread 2: Debit, save → version was 5, now 6 → ERROR ✓
```

**Q8: What is the gossip protocol in the mesh?**  
A: Every device broadcasts everything it has to every other device. TTL decrements at each hop (prevents infinite loops). In our simulator, one "gossip round" = all devices exchange with all other devices (equivalent to N rounds of pairwise Bluetooth discovery).

**Q9: Why does the `PaymentInstruction` have a `nonce` field?**  
A: Suppose Alice legitimately sends Bob ₹100 twice (same sender, receiver, amount). Without nonce:
- Ciphertext would be identical (same plaintext)
- packetHash would be identical
- Second payment rejected as duplicate ✗

With nonce (UUID):
- Even though sender/receiver/amount are same, nonce differs
- → plaintext differs → ciphertext differs → packetHash differs ✓

**Q10: What's the difference between `signedAt` and `settledAt`?**  
A:
- `signedAt`: When sender created & signed the payment (offline, might be hours ago)
- `settledAt`: When server actually processed it and deducted/credited (now)
- Used to detect replay attacks (if signedAt > 24 hours ago, reject)

---

### **Advanced Level**

**Q11: What happens if the server decryption fails?**  
A: 
```java
try {
    instruction = crypto.decrypt(ciphertext);
} catch (Exception e) {
    return IngestResult.invalid(packetHash, "decryption_failed");
}
```
The packet is marked INVALID (not settled). The money never moves. Log includes error details.

**Q12: What if sender has insufficient balance?**  
A:
```java
if (sender.getBalance().compareTo(amount) < 0) {
    return recordRejected(instruction, packetHash, ...);
}
```
Transaction is recorded with status = REJECTED. Ledger unchanged.

**Q13: What is the difference between `IngestResult.DUPLICATE_DROPPED` and `INVALID` and `SETTLED`?**  
A:
- `DUPLICATE_DROPPED`: Packet already processed. Ledger unchanged. No new Transaction record (hash already in idempotency cache).
- `INVALID`: Packet tampered/replayed/stale. Transaction recorded with status=REJECTED. No ledger update.
- `SETTLED`: Packet processed successfully. Ledger updated (debit+credit). Transaction recorded with status=SETTLED.

**Q14: What if the bridge node lies about hopCount?**  
A: Current code trusts it. In production, you'd:
- Use digital signatures on the hop list
- Use authenticated timestamping at each hop
- Verify the chain of custody cryptographically

**Q15: How does the system scale?**  
A: 
- **Idempotency cache:** In production, use Redis (distributed, TTL built-in)
- **Settlement:** Use connection pooling (HikariCP configured in Spring)
- **Database:** H2 in-memory for demo; production would use PostgreSQL/MySQL
- **Encryption:** HSM for private key storage (not in-memory)
- **Mesh simulator:** Remove for production; use real Bluetooth protocol

**Q16: What's the worst-case scenario for security?**  
A:
1. Attacker intercepts ciphertext (can't read, AES-GCM prevents it)
2. Attacker tries to tamper with single bit → GCM auth fails → rejected ✓
3. Attacker tries to replace ciphertext with old one → freshness check fails (> 24h old) ✓
4. Attacker sends to 3 bridges simultaneously → idempotency deduplicates 2 → settled once ✓

**Q17: Why do we need both optimistic locking AND the idempotency layer?**  
A:
- **Idempotency layer** catches duplicates at HTTP level (before DB)
- **Optimistic locking** is a fallback if somehow idempotency fails
- **DB unique constraint** is another fallback
- This is "defense in depth" — multiple independent layers

**Q18: What if a bridge node goes offline after claiming idempotency but before the transaction settles?**  
A:
- Idempotency claim is stored (in-memory, or Redis)
- But settlement never completed
- Different bridge later delivers same packet
- → packetHash already in idempotency cache → duplicate detected ✓
- Money never moved twice

**Q19: How would you test this system?**  
A:
```java
@Test
public void testIdempotentDeliveryViaMultipleBridges() {
    // 1. Build & encrypt a payment
    // 2. Call ingest() from bridge-1
    // 3. Assert SETTLED
    // 4. Call ingest() from bridge-2 (same packet)
    // 5. Assert DUPLICATE_DROPPED
    // 6. Query ledger, assert balance changed exactly once
}

@Test
public void testReplayAttackRejected() {
    // 1. Build old payment (signedAt = 25 hours ago)
    // 2. Call ingest()
    // 3. Assert INVALID reason="stale_packet"
}

@Test
public void testConcurrentIngestSamePacket() {
    // 1. ExecutorService with 10 threads
    // 2. All 10 submit same packet to ingest()
    // 3. Assert exactly 1 returned SETTLED, 9 returned DUPLICATE_DROPPED
    // 4. Assert ledger changed exactly once
}
```

**Q20: What are the limitations of this demo?**  
A:
- **Encryption key held in-memory** (production: HSM)
- **Idempotency cache in-memory** (production: Redis)
- **No digital signatures** (just encryption, assume trust)
- **Gossip is all-to-all** (real Bluetooth Mesh uses proximity + forwarding hops)
- **No rate limiting** (production: add throttling)
- **No audit logs** (production: append-only ledger)
- **H2 in-memory database** (production: PostgreSQL, replicated, backed up)

---

## 🚀 Why This Project is Cool (for Interviews)

1. **Real-world problem:** Offline payments are a known problem in India (poor connectivity)
2. **Cryptography:** Shows understanding of RSA, AES-GCM, hashing
3. **Concurrency:** Demonstrates idempotency, optimistic locking, atomic operations
4. **System design:** Mesh networking, fault tolerance, defense in depth
5. **Spring Boot:** REST API, transactions, JPA, multi-layer architecture
6. **Problem-solving:** Three hard problems identified and solved elegantly

---

## 📝 Running the Application

```powershell
# Set Java 17
$env:JAVA_HOME = 'C:\Users\prath\.jdk\jdk-17.0.16'

# Run on port 8081
$env:SERVER_PORT = '8081'

# Start
cd c:\Users\prath\OneDrive\Desktop\projects.java\UPI_Without_Internet
.\mvnw spring-boot:run

# Open browser
# http://localhost:8081  (dashboard)
# http://localhost:8081/h2-console  (in-memory DB)
```

---

## 🎁 Key Takeaway

**This is a masterclass in:**
- Designing systems that tolerate failures (idempotency)
- Cryptographic protocols (hybrid encryption, authenticated encryption)
- Multi-threaded programming (ConcurrentHashMap, optimistic locking)
- Financial systems (ledger, transaction settlement, double-entry bookkeeping)

Think of it as "How would you build Stripe's payments API if your users had zero connectivity?"

