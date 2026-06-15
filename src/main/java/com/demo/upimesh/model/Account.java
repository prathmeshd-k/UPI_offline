package com.demo.upimesh.model;

import jakarta.persistence.*;

import java.math.BigDecimal;

/**
 * Simulated bank account. In a real system this would live in the bank's core,
 * not in our service. For the demo, we own the ledger.
 */
@Entity //This Java class is a Database Table
@Table(name = "accounts") //CREATE TABLE accounts
public class Account {

    @Id
    private String vpa; // Virtual Payment Address, e.g. "alice@demo"

    @Column(nullable = false) // holder_name cannot be NULL
    private String holderName; // ram sham etc

    @Column(nullable = false, precision = 19, scale = 2) // ₹1000.50  , ₹500.25  ,  ₹99999.99
    private BigDecimal balance;

    @Version 
   /* imagine bob had 1k in his bank. he spend 100 and now balence is 900 , but duplicate payment happns
   and the balence is 800 now.... money lostt okey?? for preventing this we used version over here.
   Read Version = 5
   Update Balance = 900
   Version → 6
   */
    private Long version;

    public Account() {}

    public Account(String vpa, String holderName, BigDecimal balance) {
        this.vpa = vpa;                     //"alice@demo"
        this.holderName = holderName;       // "alice"
        this.balance = balance;             // "10000"
    }

    public String getVpa() { return vpa; }
    public void setVpa(String vpa) { this.vpa = vpa; }

    public String getHolderName() { return holderName; }
    public void setHolderName(String holderName) { this.holderName = holderName; }

    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
