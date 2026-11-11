package com.nubeprivada.compartida.repo;

import com.nubeprivada.compartida.domain.UploadSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UploadSessionRepository extends JpaRepository<UploadSession, String> {

    Optional<UploadSession> findByOwnerAndFingerprintAndSize(String owner, String fingerprint, long size);
}
