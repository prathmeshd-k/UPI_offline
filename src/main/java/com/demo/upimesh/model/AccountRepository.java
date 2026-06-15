package com.demo.upimesh.model;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, String> {
}
 /*
 AccountRepository is the Data Access Layer for Account entities.
  It allows the application to create, read, update, and delete account records 
  without writing SQL manually.
 */