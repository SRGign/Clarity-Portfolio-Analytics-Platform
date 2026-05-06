package com.pnltracker.ai;

public class AiAdvisorException extends RuntimeException {

    private final String errorCode;

    public AiAdvisorException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public AiAdvisorException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
