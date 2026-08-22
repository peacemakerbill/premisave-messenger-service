package com.premisave.messenger.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
public class HealthController {

    private final HealthEndpoint healthEndpoint;
    private static final LocalDateTime START_TIME = LocalDateTime.now();

    /**
     * Full health check with component details
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        try {
            Health health = healthEndpoint.health();
            
            Map<String, Object> response = new HashMap<>();
            response.put("status", health.getStatus().toString());
            response.put("components", health.getComponents());
            response.put("timestamp", System.currentTimeMillis());
            response.put("uptime", calculateUptime());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error getting health status", e);
            return ResponseEntity.internalServerError().body(Map.of(
                "status", "DOWN",
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Readiness probe for Kubernetes
     * Used by readinessProbe in deployment
     */
    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, Object>> readiness() {
        try {
            Health health = healthEndpoint.health();
            
            // Service is ready if all components are UP
            boolean isReady = health.getStatus().toString().equals("UP");
            
            Map<String, Object> response = new HashMap<>();
            response.put("status", isReady ? "READY" : "NOT_READY");
            response.put("service", "premisave-messenger");
            response.put("timestamp", LocalDateTime.now());
            
            return isReady 
                ? ResponseEntity.ok(response)
                : ResponseEntity.status(503).body(response);
        } catch (Exception e) {
            log.error("Readiness check failed", e);
            return ResponseEntity.status(503).body(Map.of(
                "status", "NOT_READY",
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Liveness probe for Kubernetes
     * Used by livenessProbe in deployment
     */
    @GetMapping("/health/live")
    public ResponseEntity<Map<String, Object>> liveness() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "ALIVE");
        response.put("service", "premisave-messenger");
        response.put("timestamp", LocalDateTime.now());
        response.put("uptime", calculateUptime());
        
        return ResponseEntity.ok(response);
    }

    /**
     * Startup probe for Kubernetes
     * Used by startupProbe in deployment
     */
    @GetMapping("/health/startup")
    public ResponseEntity<Map<String, Object>> startup() {
        try {
            Health health = healthEndpoint.health();
            boolean isStarted = health.getStatus().toString().equals("UP");
            
            Map<String, Object> response = new HashMap<>();
            response.put("status", isStarted ? "STARTED" : "STARTING");
            response.put("timestamp", LocalDateTime.now());
            
            return isStarted 
                ? ResponseEntity.ok(response)
                : ResponseEntity.status(503).body(response);
        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of(
                "status", "STARTING"
            ));
        }
    }

    /**
     * Simplified info endpoint
     */
    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> info() {
        Map<String, Object> info = new HashMap<>();
        info.put("name", "Premisave Messenger Service");
        info.put("version", "1.0.0");
        info.put("environment", System.getProperty("spring.profiles.active", "default"));
        info.put("timestamp", LocalDateTime.now());
        info.put("uptime", calculateUptime() + " seconds");
        
        return ResponseEntity.ok(info);
    }

    // ===== HELPER METHODS =====

    private long calculateUptime() {
        return java.time.temporal.ChronoUnit.SECONDS.between(START_TIME, LocalDateTime.now());
    }
}