package com.dark.javaHarness.dto;

/**
 * 聊天接口响应体（POST /api/chat）。
 */
public record ChatResponse(
        String sessionId,
        boolean newSession,
        String goalId,
        String status,
        String reply,
        String error) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String sessionId;
        private boolean newSession;
        private String goalId;
        private String status;
        private String reply;
        private String error;

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder newSession(boolean newSession) {
            this.newSession = newSession;
            return this;
        }

        public Builder goalId(String goalId) {
            this.goalId = goalId;
            return this;
        }

        public Builder status(String status) {
            this.status = status;
            return this;
        }

        public Builder reply(String reply) {
            this.reply = reply;
            return this;
        }

        public Builder error(String error) {
            this.error = error;
            return this;
        }

        public ChatResponse build() {
            return new ChatResponse(sessionId, newSession, goalId, status, reply, error);
        }
    }
}