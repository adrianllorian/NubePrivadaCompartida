package com.nubeprivada.compartida.web;

import com.nubeprivada.compartida.domain.UserAccount;
import com.nubeprivada.compartida.repo.UserRepository;
import com.nubeprivada.compartida.service.ActivityService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final UserRepository users;
    private final ActivityService activity;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    public AuthController(AuthenticationManager authenticationManager, UserRepository users, ActivityService activity) {
        this.authenticationManager = authenticationManager;
        this.users = users;
        this.activity = activity;
    }

    public record LoginRequest(String username, String password) {
    }

    public record UserDto(String username, String role) {
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        if (body == null || body.username() == null || body.password() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "missing credentials"));
        }
        try {
            Authentication auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(body.username().trim(), body.password()));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);
            contextRepository.saveContext(context, request, response);
            activity.log(auth.getName(), "LOGIN", "Inicio de sesión", request);
            return ResponseEntity.ok(dto(auth.getName()));
        } catch (AuthenticationException e) {
            return ResponseEntity.status(401).body(Map.of("error", "bad credentials"));
        }
    }

    @GetMapping("/me")
    public ResponseEntity<?> me() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
        }
        return ResponseEntity.ok(dto(auth.getName()));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private UserDto dto(String username) {
        UserAccount user = users.findById(username).orElseThrow();
        return new UserDto(user.getUsername(), user.getRole());
    }
}
