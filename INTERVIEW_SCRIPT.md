# Interview Script: UPI Offline Mesh

## 1-Minute Version

Hi, this project is a Spring Boot backend that demonstrates how an offline UPI-style payment could be routed through a mesh of nearby phones and settled later when one phone regains internet.

The idea is that if two users are in a place with no network, the sender's phone creates a payment instruction, encrypts it end to end, and wraps it into a packet. That packet is then passed device to device through a simulated Bluetooth mesh. When a bridge phone finally gets connectivity, it uploads the packet to the backend.

On the backend, I solve three main problems. First, confidentiality and tamper protection using hybrid encryption with RSA and AES-GCM. Second, duplicate prevention using a ciphertext hash and an atomic idempotency claim, so even if multiple bridge nodes upload the same packet at the same time, it settles only once. Third, replay protection using a signed timestamp and freshness checks.

So the main value of the project is showing secure deferred settlement, concurrency handling, and distributed-system thinking in a realistic payments scenario.

---

## 2-3 Minute Version

This project is called UPI Offline Mesh. It is a simulation of how an offline payment system could work when there is no mobile data or internet available.

The user flow is simple. A sender creates a payment for a receiver. In the demo, that happens through the backend for convenience, but conceptually it represents code running on the sender's phone. The payment details are stored in a `PaymentInstruction`, which includes sender, receiver, amount, a nonce, and a signed timestamp.

That instruction is encrypted using hybrid cryptography. I use AES-256-GCM to encrypt the actual payload, because AES is efficient for larger data, and then I encrypt the AES session key using the server's RSA public key. This means intermediary phones in the mesh can carry the packet, but they cannot read or modify the payment.

After encryption, the data is wrapped in a `MeshPacket` with metadata like packet ID, TTL, and creation time. The packet is injected into a virtual mesh network made of simulated phones. Each gossip round copies packets to nearby devices and decrements TTL, which represents limited hop propagation.

Once a bridge node gets internet, it sends the packet to the backend through `/api/bridge/ingest`. That is the core production-style endpoint in the system. The backend pipeline is:

1. Hash the ciphertext.
2. Try to atomically claim that hash in the idempotency service.
3. If the claim fails, the packet is dropped as a duplicate.
4. If the claim succeeds, decrypt the packet.
5. Validate freshness using the `signedAt` timestamp.
6. Settle the transaction inside a database transaction.

The most important engineering challenge here is duplicate prevention. In a mesh system, the same packet can reach the backend from multiple bridge phones at nearly the same time. I solve that by hashing the ciphertext and using `ConcurrentHashMap.putIfAbsent()` as an atomic idempotency gate. Only the first thread can claim the packet. The others are rejected as duplicates before any settlement happens.

For settlement, I use Spring transactions and optimistic locking. The sender account is debited, the receiver is credited, and a transaction ledger row is inserted. I also keep a unique database constraint on `packetHash` as a second safety layer.

So overall, the project combines cryptography, concurrency control, REST APIs, transaction management, and a mesh simulation in one end-to-end demo.

---

## 5-Minute Detailed Version

I built this project to explore a real systems problem: how to support payment intent creation in a no-connectivity environment and settle it safely once the network becomes available again.

The architecture has four major parts.

First is the API layer. `ApiController` exposes endpoints for the public key, demo packet creation, mesh state, gossip rounds, bridge flush, account listing, transaction listing, and the real ingest endpoint. This makes the project both demo-friendly and structured like a backend service.

Second is the cryptography layer. `HybridCryptoService` implements hybrid encryption. The payment payload is serialized to JSON, encrypted with AES-GCM, and the AES key is encrypted with RSA-OAEP. I chose this because RSA alone cannot safely handle larger payloads, while AES-GCM gives both confidentiality and integrity. If someone tampers with the ciphertext, GCM authentication fails during decryption.

Third is the mesh simulation layer. `MeshSimulatorService` creates virtual devices, including one bridge device with internet access. Packets are spread using a gossip model. This is not real Bluetooth code, but it is a good simulation of how a store-and-forward mesh behaves.

Fourth is the settlement pipeline. `BridgeIngestionService` orchestrates the backend flow. It hashes the ciphertext, checks idempotency, decrypts the payload, validates replay freshness, and then hands off to `SettlementService`.

`SettlementService` is where the ledger changes happen. It runs inside `@Transactional`, loads the sender and receiver accounts, validates the amount and balance, updates both balances, and writes a transaction record. The `Account` entity uses `@Version`, so optimistic locking helps prevent lost updates if there is any concurrency issue.

One of the strongest parts of the project is defense in depth.

For security:
- AES-GCM protects the payload from tampering.
- RSA ensures only the backend can unwrap the AES key.
- The nonce ensures two legitimate payments of the same amount are still unique.
- The signed timestamp prevents very old packets from being replayed.

For duplicate prevention:
- The idempotency service stops duplicates in memory before processing.
- The transaction table also has a unique constraint on `packetHash`.
- Optimistic locking provides an extra concurrency safeguard at the account level.

I also wrote tests for the most important scenarios. One test verifies encryption and decryption, one verifies tampered ciphertext is rejected, and the most important one simulates three bridge nodes delivering the same packet concurrently and confirms that only one settlement happens.

If I were taking this further toward production, I would replace the in-memory idempotency cache with Redis, replace H2 with PostgreSQL, move private key handling into an HSM or KMS, add bridge authentication, and move the sender-side packet creation logic into a mobile client.

So I would describe this project as a secure offline-payment settlement simulator that demonstrates cryptography, exactly-once style processing, and transaction safety under concurrency.

---

## Strong Closing Line

The key idea of the project is not real-time offline settlement, but secure deferred settlement: users can create and relay payment intent offline, and the backend guarantees that when the packet finally arrives, it is authentic, fresh, and settled at most once.

---

## If The Interviewer Asks "What Was Your Contribution?"

I designed the backend architecture, implemented the REST APIs, built the hybrid encryption flow, created the idempotency mechanism for duplicate packet delivery, implemented transactional settlement with optimistic locking, and added the mesh simulator and concurrency-focused tests to prove the design works end to end.

---

## If The Interviewer Asks "What Was The Hardest Part?"

The hardest part was handling duplicate delivery correctly. In a mesh network, the same payment can legitimately arrive from multiple bridge nodes at the same time. That means the system must behave correctly under concurrency, not just under normal sequential execution. I solved that by using the ciphertext hash as the idempotency key and claiming it atomically before decryption and settlement.

---

## If The Interviewer Asks "What Are The Limitations?"

This is a backend-heavy simulation, not a production offline payments product. The mesh is simulated rather than real Bluetooth, the idempotency cache is in-memory instead of Redis, the database is H2 instead of PostgreSQL, and the sender-side code is modeled on the server rather than running on Android. Also, in a real offline payments system, trust, compliance, and balance guarantees are much more complex.

---

## Simple Spoken Ending

So overall, this project helped me practice secure system design, concurrency control, and payment-style transaction processing in a way that is practical and easy to demonstrate live.
