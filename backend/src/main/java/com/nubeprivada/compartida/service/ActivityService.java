package com.nubeprivada.compartida.service;

import com.nubeprivada.compartida.domain.Activity;
import com.nubeprivada.compartida.repo.ActivityRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

@Service
public class ActivityService {

    private final ActivityRepository repo;

    public ActivityService(ActivityRepository repo) {
        this.repo = repo;
    }

    public void log(String username, String action, String detail, HttpServletRequest request) {
        String ip = request == null ? "" : clientIp(request);
        repo.save(new Activity(username, action, detail, ip));
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
