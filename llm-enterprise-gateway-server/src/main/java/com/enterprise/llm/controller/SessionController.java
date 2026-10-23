package com.enterprise.llm.controller;

import com.enterprise.llm.session.ChatSession;
import com.enterprise.llm.session.SessionManager;
import com.enterprise.llm.trace.TraceContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/sessions")
public class SessionController {

    private final SessionManager sessionManager;

    public SessionController(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    private String getTenantId() {
        TraceContext ctx = TraceContext.getCurrent();
        if (ctx != null && ctx.tenantId() != null && !ctx.tenantId().isBlank()) {
            return ctx.tenantId();
        }
        return "default";
    }

    @GetMapping
    public ResponseEntity<List<ChatSession>> listSessions(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        List<ChatSession> sessions = sessionManager.listSessions(getTenantId(), limit, offset);
        return ResponseEntity.ok(sessions);
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<?> getSession(@PathVariable String sessionId) {
        return sessionManager.getSession(getTenantId(), sessionId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Session not found: " + sessionId)));
    }

    @PostMapping
    public ResponseEntity<ChatSession> createSession(@RequestBody(required = false) Map<String, Object> body) {
        String sessionId = null;
        String title = null;
        String userId = null;

        if (body != null) {
            sessionId = (String) body.get("session_id");
            title = (String) body.get("title");
            userId = (String) body.get("user_id");
        }
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = "sess-" + UUID.randomUUID().toString().substring(0, 8);
        }

        ChatSession session = sessionManager.getOrCreateSession(getTenantId(), sessionId, userId, title);
        return ResponseEntity.status(HttpStatus.CREATED).body(session);
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<?> deleteSession(@PathVariable String sessionId) {
        sessionManager.deleteSession(getTenantId(), sessionId);
        return ResponseEntity.ok(Map.of("deleted", true, "session_id", sessionId));
    }
}
