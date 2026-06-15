package com.demo.upimesh.model;

import java.math.BigDecimal;

/**
 This class represents the actual UPI payment request.
 Think of it as the real transaction data that is hidden inside the encrypted ciphertext of MeshPacket.
 


 PaymentInstruction is the secure business payload of the UPI Offline Mesh system.
 It contains sender and receiver VPAs, amount, PIN hash, nonce, and timestamp information, 
 which are encrypted and transported inside a MeshPacket before
  being validated and processed by the server."


  
 */
public class PaymentInstruction {
//PaymentInstruction contains the actual payment details that the bank/server needs to execute a transaction.
    

    private String senderVpa; //"alice@upi"
    private String receiverVpa; //"bob@upi"
    private BigDecimal amount;  // 100
    private String pinHash;      // "hashedPin"
    private String nonce;     // UUID, unique per payment intent ex 123, 556 etc
    private Long signedAt;    // epoch millis, when sender signed

    public PaymentInstruction() {}

    public PaymentInstruction(String senderVpa, String receiverVpa, BigDecimal amount,
                              String pinHash, String nonce, Long signedAt) {
        this.senderVpa = senderVpa;
        this.receiverVpa = receiverVpa;
        this.amount = amount;
        this.pinHash = pinHash;
        this.nonce = nonce;
        this.signedAt = signedAt;
    }

    public String getSenderVpa() { return senderVpa; }
    public void setSenderVpa(String senderVpa) { this.senderVpa = senderVpa; }

    public String getReceiverVpa() { return receiverVpa; }
    public void setReceiverVpa(String receiverVpa) { this.receiverVpa = receiverVpa; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getPinHash() { return pinHash; }
    public void setPinHash(String pinHash) { this.pinHash = pinHash; }

    public String getNonce() { return nonce; }
    public void setNonce(String nonce) { this.nonce = nonce; }

    public Long getSignedAt() { return signedAt; }
    public void setSignedAt(Long signedAt) { this.signedAt = signedAt; }
}
