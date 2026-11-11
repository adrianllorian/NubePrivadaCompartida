package com.nubeprivada.compartida.web;

import com.nubeprivada.compartida.domain.UserAccount;
import com.nubeprivada.compartida.repo.UserRepository;
import com.nubeprivada.compartida.service.ActivityService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AdminController {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final ActivityService activity;

    public AdminController(UserRepository users, PasswordEncoder encoder, ActivityService activity) {
        this.users = users;
        this.encoder = encoder;
        this.activity = activity;
    }

    public record UserDto(String username, String role) {
    }

    public record NewUser(String username, String password, String role) {
    }

    @GetMapping("/users")
    public ResponseEntity<?> list() {
        if (!isAdmin()) {
            return ResponseEntity.status(403).body(Map.of("error", "forbidden"));
        }
        List<UserDto> list = users.findAll().stream()
                .map(u -> new UserDto(u.getUsername(), u.getRole()))
                .sorted((a, b) -> a.username().compareToIgnoreCase(b.username()))
                .toList();
        return ResponseEntity.ok(list);
    }

    @PostMapping("/users")
    public ResponseEntity<?> create(@RequestBody NewUser body, HttpServletRequest request) {
        if (!isAdmin()) {
            return ResponseEntity.status(403).body(Map.of("error", "forbidden"));
        }
        String username = body.username() == null ? "" : body.username().trim();
        String password = body.password() == null ? "" : body.password();
        String role = body.role() == null || body.role().isBlank() ? "USER" : body.role().toUpperCase();

        if (!username.matches("[A-Za-z0-9._-]{3,32}")) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid username"));
        }
        if (password.length() < 4) {
            return ResponseEntity.badRequest().body(Map.of("error", "password too short"));
        }
        if (!role.equals("USER") && !role.equals("ADMIN")) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid role"));
        }
        if (users.findById(username).isPresent()) {
            return ResponseEntity.status(409).body(Map.of("error", "user exists"));
        }

        users.save(new UserAccount(username, encoder.encode(password), role));
        activity.log(currentUser(), "USUARIO", "Creó el usuario " + username + " (" + role + ")", request);
        return ResponseEntity.ok(new UserDto(username, role));
    }

    private boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }

    private String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "" : auth.getName();
    }
}
