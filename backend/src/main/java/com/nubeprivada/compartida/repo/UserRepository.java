package com.nubeprivada.compartida.repo;

import com.nubeprivada.compartida.domain.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<UserAccount, String> {
}
