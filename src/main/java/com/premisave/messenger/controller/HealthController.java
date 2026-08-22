package com.premisave.messenger.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
public class HealthController {

    private final MongoTemplate mongoTemplate;
    private final RedisTemplate<String, Object> redisTemplate;
    private static final LocalDateTime START_TIME = LocalDateTime.now();

    public HealthController(MongoTemplate mongoTemplate, RedisTemplate<String, Object> redisTemplate) {
        this.mongoTemplate = mongoTemplate;
        this.redisTemplate = redisTemplate;
    }

    /**
     * Full health check with component details
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        try {
            boolean mongoOk = checkMongoDB();
            boolean redisOk = checkRedis();
            boolean allOk = mongoOk && redisOk;
            
            Map<String, Object> response = new HashMap<>();
            response.put("status", allOk ? "UP" : "PARTIAL");
            response.put("timestamp", System.currentTimeMillis());
            response.put("uptime", calculateUptime());
            
            Map<String, Object> components = new HashMap<>();
            components.put("mongodb", mongoOk ? "UP" : "DOWN");
            components.put("redis", redisOk ? "UP" : "DOWN");
            response.put("components", components);
            
            return allOk ? ResponseEntity.ok(response) : ResponseEntity.status(503).body(response);
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
            boolean mongoOk = checkMongoDB();
            boolean redisOk = checkRedis();
            boolean isReady = mongoOk && redisOk;
            
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
            boolean mongoOk = checkMongoDB();
            boolean redisOk = checkRedis();
            boolean isStarted = mongoOk && redisOk;
            
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

    private boolean checkMongoDB() {
        try {
            mongoTemplate.executeCommand("{ ping: 1 }");
            return true;
        } catch (Exception e) {
            log.warn("MongoDB health check failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean checkRedis() {
        try {
            var conn = redisTemplate.getConnectionFactory().getConnection();
            conn.ping();
            conn.close();
            return true;
        } catch (Exception e) {
            log.warn("Redis health check failed: {}", e.getMessage());
            return false;
        }
    }

    private long calculateUptime() {
        return java.time.temporal.ChronoUnit.SECONDS.between(START_TIME, LocalDateTime.now());
    }
}