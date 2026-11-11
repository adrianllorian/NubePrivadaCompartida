package com.nubeprivada.compartida.web;

import com.nubeprivada.compartida.domain.UserAccount;
import com.nubeprivada.compartida.repo.UserRepository;
import com.nubeprivada.compartida.service.ActivityService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Configuración inicial del sistema: solo permite crear el primer administrador
 * mientras no exista ninguno. La contraseña se guarda cifrada con BCrypt.
 */
@RestController
@RequestMapping("/api/setup")
public class SetupController {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final ActivityService activity;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    public SetupController(UserRepository users, PasswordEncoder encoder, ActivityService activity,
                           AuthenticationManager authenticationManager) {
        this.users = users;
        this.encoder = encoder;
        this.activity = activity;
        this.authenticationManager = authenticationManager;
    }

    public record SetupStatus(boolean required) {
    }

    public record SetupRequest(String username, String password) {
    }

    public record UserDto(String username, String role) {
    }

    @GetMapping
    public ResponseEntity<SetupStatus> status() {
        return ResponseEntity.ok(new SetupStatus(!hasAdmin()));
    }

    @PostMapping
    public ResponseEntity<?> setup(@RequestBody SetupRequest body, HttpServletRequest request, HttpServletResponse response) {
        if (hasAdmin()) {
            return ResponseEntity.status(409).body(Map.of("error", "admin exists"));
        }
        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "missing credentials"));
        }

        String username = body.username() == null ? "" : body.username().trim();
        String password = body.password() == null ? "" : body.password();

        if (!username.matches("[A-Za-z0-9._-]{3,32}")) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid username"));
        }
        if (password.length() < 8) {
            return ResponseEntity.badRequest().body(Map.of("error", "password too short"));
        }
        if (users.findById(username).isPresent()) {
            return ResponseEntity.status(409).body(Map.of("error", "user exists"));
        }

        users.save(new UserAccount(username, encoder.encode(password), "ADMIN"));
        activity.log(username, "SETUP", "Creó la cuenta de administrador", request);

        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(username, password));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);

        return ResponseEntity.ok(new UserDto(username, "ADMIN"));
    }

    private boolean hasAdmin() {
        return users.findAll().stream().anyMatch(u -> "ADMIN".equalsIgnoreCase(u.getRole()));
    }
}
