package com.nubeprivada.compartida.web;

import com.nubeprivada.compartida.domain.Activity;
import com.nubeprivada.compartida.repo.ActivityRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ActivityController {

    private final ActivityRepository repo;

    public ActivityController(ActivityRepository repo) {
        this.repo = repo;
    }

    @GetMapping("/activity")
    public ResponseEntity<?> activity(@RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "25") int size,
                                      @RequestParam(required = false) String user,
                                      @RequestParam(required = false) String action) {
        boolean admin = isAdmin();
        String username = admin
                ? (user == null || user.isBlank() ? null : user)
                : currentUser();
        String filterAction = action == null || action.isBlank() ? null : action;

        int safeSize = Math.min(Math.max(size, 5), 200);
        Page<Activity> result = repo.search(username, filterAction, PageRequest.of(Math.max(page, 0), safeSize));

        List<Map<String, Object>> content = result.getContent().stream().map(a -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", a.getId());
            map.put("username", a.getUsername());
            map.put("action", a.getAction());
            map.put("detail", a.getDetail());
            map.put("ip", a.getIp());
            map.put("createdAt", a.getCreatedAt() == null ? "" : a.getCreatedAt().toString());
            return map;
        }).toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", content);
        payload.put("total", result.getTotalElements());
        payload.put("page", result.getNumber());
        payload.put("size", safeSize);
        payload.put("pages", result.getTotalPages());
        payload.put("actions", repo.distinctActions());
        return ResponseEntity.ok(payload);
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
