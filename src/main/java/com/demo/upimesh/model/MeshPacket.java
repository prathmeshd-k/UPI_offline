package com.demo.upimesh.model; 

import jakarta.validation.constraints.NotBlank;   // Used to validate incoming requests.
import jakarta.validation.constraints.NotNull;   //Used to validate incoming requests.
import jakarta.validation.constraints.Min;      // Used to validate incoming requests.
 
/**
 it represents the actual packet that travels through the mesh network (Bluetooth)
MeshPacket is the transport container used for forwarding payment requests through the mesh.

             joo bluetooth user hai usko  - they cant see ->  {sender ,receiver , amount , upiId} 
             it olly sees  { packetID , ttl , createdAt }   
 */
public class MeshPacket {

    @NotBlank  // it rejects {"" , "  ", null}
    private String packetId; // UUID, used by intermediates for gossip dedup

    @Min(0) // Checks minimum numeric value.
    private int ttl; // Hops remaining; intermediates decrement it

    @NotNull  // rejects null
    private Long createdAt; // epoch millis, when sender created the packet

    @NotBlank
    private String ciphertext; // base64(RSA-encrypted AES key + AES-GCM ciphertext)

    public MeshPacket() {}

    public String getPacketId() { return packetId; }
    public void setPacketId(String packetId) { this.packetId = packetId; }

    public int getTtl() { return ttl; }
    public void setTtl(int ttl) { this.ttl = ttl; }

    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }

    public String getCiphertext() { return ciphertext; }
    public void setCiphertext(String ciphertext) { this.ciphertext = ciphertext; }
}
