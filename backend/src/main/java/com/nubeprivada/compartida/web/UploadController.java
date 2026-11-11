package com.nubeprivada.compartida.web;

import com.nubeprivada.compartida.domain.UploadSession;
import com.nubeprivada.compartida.repo.UploadSessionRepository;
import com.nubeprivada.compartida.service.ActivityService;
import com.nubeprivada.compartida.service.StorageService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/uploads")
public class UploadController {

    private final UploadSessionRepository sessions;
    private final StorageService storage;
    private final ActivityService activity;

    public UploadController(UploadSessionRepository sessions, StorageService storage, ActivityService activity) {
        this.sessions = sessions;
        this.storage = storage;
        this.activity = activity;
    }

    public record InitRequest(String parentId, String name, long size, String contentType, String fingerprint) {
    }

    public record InitResponse(String uploadId, long received) {
    }

    @PostMapping("/init")
    public ResponseEntity<?> init(@RequestBody InitRequest body, HttpServletRequest request) throws IOException {
        String owner = currentUser();
        String fingerprint = body.fingerprint() == null ? "" : body.fingerprint();
        var existing = sessions.findByOwnerAndFingerprintAndSize(owner, fingerprint, body.size());
        if (existing.isPresent()) {
            UploadSession found = existing.get();
            return ResponseEntity.ok(new InitResponse(found.getId(), found.getReceived()));
        }
        UploadSession session = new UploadSession();
        session.setId(UUID.randomUUID().toString().replace("-", ""));
        session.setParentId(body.parentId());
        session.setName(body.name() == null || body.name().isBlank() ? "fichero" : body.name());
        session.setOwner(owner);
        session.setSize(body.size());
        session.setContentType(body.contentType());
        session.setFingerprint(fingerprint);
        session.setTempName(session.getId() + ".part");
        session.setReceived(0);
        session.setCreatedAt(Instant.now());
        Files.createFile(storage.getUploadsDir().resolve(session.getTempName()));
        sessions.save(session);
        return ResponseEntity.ok(new InitResponse(session.getId(), 0));
    }

    @PostMapping("/{id}/chunk")
    public ResponseEntity<?> chunk(@PathVariable String id,
                                   @RequestParam long offset,
                                   HttpServletRequest request) throws IOException {
        UploadSession session = sessions.findById(id).orElse(null);
        if (session == null || !session.getOwner().equals(currentUser())) {
            return ResponseEntity.notFound().build();
        }
        if (offset != session.getReceived()) {
            return ResponseEntity.status(409).body(Map.of("received", session.getReceived()));
        }
        Path temp = storage.getUploadsDir().resolve(session.getTempName());
        long written;
        try (InputStream in = request.getInputStream();
             FileOutputStream out = new FileOutputStream(temp.toFile(), true)) {
            written = in.transferTo(out);
        }
        session.setReceived(session.getReceived() + written);
        sessions.save(session);
        return ResponseEntity.ok(Map.of("received", session.getReceived()));
    }

    @PostMapping("/{id}/complete")
    public ResponseEntity<?> complete(@PathVariable String id, HttpServletRequest request) throws IOException {
        UploadSession session = sessions.findById(id).orElse(null);
        if (session == null || !session.getOwner().equals(currentUser())) {
            return ResponseEntity.notFound().build();
        }
        Path temp = storage.getUploadsDir().resolve(session.getTempName());
        if (!Files.exists(temp)) {
            sessions.delete(session);
            return ResponseEntity.notFound().build();
        }
        var item = storage.storeFromTemp(session.getParentId(), session.getName(), session.getOwner(),
                session.getContentType(), temp, Files.size(temp));
        sessions.delete(session);
        activity.log(currentUser(), "SUBIDA", "Subió " + item.getName() + " (" + item.getSize() + " bytes)", request);
        return ResponseEntity.ok(Map.of("id", item.getId(), "name", item.getName(), "size", item.getSize()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> cancel(@PathVariable String id) throws IOException {
        UploadSession session = sessions.findById(id).orElse(null);
        if (session == null || !session.getOwner().equals(currentUser())) {
            return ResponseEntity.notFound().build();
        }
        Files.deleteIfExists(storage.getUploadsDir().resolve(session.getTempName()));
        sessions.delete(session);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "" : auth.getName();
    }
}
