package com.enterprise.llm.session;

import com.enterprise.llm.agent.ToolCall;
import com.enterprise.llm.model.ChatMessage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class SqliteSessionStorageProvider implements SessionStorageProvider {

    private static final Logger log = LoggerFactory.getLogger(SqliteSessionStorageProvider.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final java.util.concurrent.locks.ReentrantLock writeLock = new java.util.concurrent.locks.ReentrantLock();

    public SqliteSessionStorageProvider(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = new ObjectMapper();
    }

    @jakarta.annotation.PostConstruct
    public void initPragmas() {
        try {
            jdbcTemplate.execute("PRAGMA journal_mode = WAL;");
            jdbcTemplate.execute("PRAGMA busy_timeout = 5000;");
            jdbcTemplate.execute("PRAGMA synchronous = NORMAL;");
            log.info("SQLite connection tuned with WAL mode, busy_timeout=5000ms, and synchronous=NORMAL");
        } catch (Exception e) {
            log.warn("Could not set SQLite pragmas: {}", e.getMessage());
        }
    }

    @Override
    public void saveSession(ChatSession session) {
        if (session == null || session.sessionId() == null) {
            return;
        }
        writeLock.lock();
        try {
            String tenantId = session.tenantId() != null ? session.tenantId() : "default";
            String metadataJson = "{}";
            try {
                if (session.metadata() != null && !session.metadata().isEmpty()) {
                    metadataJson = objectMapper.writeValueAsString(session.metadata());
                }
            } catch (Exception e) {
                log.warn("Failed to serialize session metadata", e);
            }

            String sql = """
                    INSERT INTO chat_sessions (session_id, tenant_id, user_id, title, metadata_json, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(session_id) DO UPDATE SET
                        title = excluded.title,
                        user_id = excluded.user_id,
                        metadata_json = excluded.metadata_json,
                        updated_at = excluded.updated_at
                    """;

            Timestamp createdAt = Timestamp.from(session.createdAt() != null ? session.createdAt() : Instant.now());
            Timestamp updatedAt = Timestamp.from(session.updatedAt() != null ? session.updatedAt() : Instant.now());

            jdbcTemplate.update(
                    sql,
                    session.sessionId(),
                    tenantId,
                    session.userId(),
                    session.title(),
                    metadataJson,
                    createdAt,
                    updatedAt
            );

            if (session.messages() != null && !session.messages().isEmpty()) {
                doAppendMessages(tenantId, session.sessionId(), session.messages());
            }
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public Optional<ChatSession> getSession(String tenantId, String sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        String tid = tenantId != null ? tenantId : "default";
        String sessionSql = "SELECT * FROM chat_sessions WHERE tenant_id = ? AND session_id = ?";

        try {
            ChatSession session = jdbcTemplate.queryForObject(sessionSql, (rs, rowNum) -> {
                String metaJson = rs.getString("metadata_json");
                Map<String, String> metadata = Collections.emptyMap();
                if (metaJson != null && !metaJson.isBlank()) {
                    try {
                        metadata = objectMapper.readValue(metaJson, new TypeReference<Map<String, String>>() {});
                    } catch (Exception e) {
                        log.warn("Failed to deserialize session metadata for {}", sessionId, e);
                    }
                }

                Timestamp crTs = rs.getTimestamp("created_at");
                Timestamp upTs = rs.getTimestamp("updated_at");

                List<ChatMessage> messages = loadMessages(tid, sessionId);

                return new ChatSession(
                        rs.getString("session_id"),
                        rs.getString("tenant_id"),
                        rs.getString("user_id"),
                        rs.getString("title"),
                        metadata,
                        crTs != null ? crTs.toInstant() : Instant.now(),
                        upTs != null ? upTs.toInstant() : Instant.now(),
                        messages
                );
            }, tid, sessionId);
            return Optional.ofNullable(session);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private List<ChatMessage> loadMessages(String tenantId, String sessionId) {
        String msgSql = "SELECT * FROM chat_session_messages WHERE tenant_id = ? AND session_id = ? ORDER BY id ASC";
        return jdbcTemplate.query(msgSql, (rs, rowNum) -> {
            String role = rs.getString("role");
            String content = rs.getString("content");
            String toolCallId = rs.getString("tool_call_id");
            String toolCallsJson = rs.getString("tool_calls_json");

            List<ToolCall> toolCalls = null;
            if (toolCallsJson != null && !toolCallsJson.isBlank()) {
                try {
                    toolCalls = objectMapper.readValue(toolCallsJson, new TypeReference<List<ToolCall>>() {});
                } catch (Exception e) {
                    log.warn("Failed to deserialize tool_calls for session {}", sessionId, e);
                }
            }
            return new ChatMessage(role, content, toolCalls, toolCallId);
        }, tenantId, sessionId);
    }

    @Override
    public List<ChatSession> listSessions(String tenantId, int limit, int offset) {
        String tid = tenantId != null ? tenantId : "default";
        int actualLimit = limit > 0 ? limit : 20;
        int actualOffset = Math.max(0, offset);

        String sql = """
                SELECT * FROM chat_sessions
                WHERE tenant_id = ?
                ORDER BY updated_at DESC
                LIMIT ? OFFSET ?
                """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            String metaJson = rs.getString("metadata_json");
            Map<String, String> metadata = Collections.emptyMap();
            if (metaJson != null && !metaJson.isBlank()) {
                try {
                    metadata = objectMapper.readValue(metaJson, new TypeReference<Map<String, String>>() {});
                } catch (Exception ignored) {}
            }
            Timestamp crTs = rs.getTimestamp("created_at");
            Timestamp upTs = rs.getTimestamp("updated_at");

            return new ChatSession(
                    rs.getString("session_id"),
                    rs.getString("tenant_id"),
                    rs.getString("user_id"),
                    rs.getString("title"),
                    metadata,
                    crTs != null ? crTs.toInstant() : Instant.now(),
                    upTs != null ? upTs.toInstant() : Instant.now(),
                    Collections.emptyList()
            );
        }, tid, actualLimit, actualOffset);
    }

    @Override
    public void deleteSession(String tenantId, String sessionId) {
        if (sessionId == null) {
            return;
        }
        writeLock.lock();
        try {
            String tid = tenantId != null ? tenantId : "default";
            jdbcTemplate.update("DELETE FROM chat_session_messages WHERE tenant_id = ? AND session_id = ?", tid, sessionId);
            jdbcTemplate.update("DELETE FROM chat_sessions WHERE tenant_id = ? AND session_id = ?", tid, sessionId);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void appendMessages(String tenantId, String sessionId, List<ChatMessage> messages) {
        if (sessionId == null || messages == null || messages.isEmpty()) {
            return;
        }
        writeLock.lock();
        try {
            doAppendMessages(tenantId, sessionId, messages);
        } finally {
            writeLock.unlock();
        }
    }

    private void doAppendMessages(String tenantId, String sessionId, List<ChatMessage> messages) {
        String tid = tenantId != null ? tenantId : "default";

        // Ensure session record exists
        String ensureSql = """
                INSERT OR IGNORE INTO chat_sessions (session_id, tenant_id, title, created_at, updated_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """;
        jdbcTemplate.update(ensureSql, sessionId, tid, "Session " + sessionId);

        String insertMsgSql = """
                INSERT INTO chat_session_messages (session_id, tenant_id, role, content, tool_calls_json, tool_call_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """;

        List<Object[]> batchArgs = new ArrayList<>();
        for (ChatMessage msg : messages) {
            String toolCallsJson = null;
            if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                try {
                    toolCallsJson = objectMapper.writeValueAsString(msg.toolCalls());
                } catch (Exception e) {
                    log.warn("Failed to serialize tool calls in appendMessages", e);
                }
            }
            batchArgs.add(new Object[]{
                    sessionId,
                    tid,
                    msg.role(),
                    msg.content(),
                    toolCallsJson,
                    msg.toolCallId()
            });
        }

        jdbcTemplate.batchUpdate(insertMsgSql, batchArgs);

        // Update updated_at on session
        jdbcTemplate.update("UPDATE chat_sessions SET updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ? AND session_id = ?", tid, sessionId);
    }
}
