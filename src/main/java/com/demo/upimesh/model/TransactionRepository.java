package com.demo.upimesh.model;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    List<Transaction> findTop20ByOrderByIdDesc();
    boolean existsByPacketHash(String packetHash);
}

/*
 TransactionRepository is the Spring Data JPA repository for the Transaction entity.
  It provides automatic CRUD operations, recent transaction retrieval,
    and packet-hash-based duplicate detection to enforce idempotent payment processing.
*/