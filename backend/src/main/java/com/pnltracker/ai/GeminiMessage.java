package com.pnltracker.ai;

import java.util.Locale;

public record GeminiMessage(String role, String text) {

    public GeminiMessage {
        role = role == null ? "" : role.trim().toLowerCase(Locale.ROOT);
        text = text == null ? "" : text;
        if (!role.equals("user") && !role.equals("model")) {
            throw new IllegalArgumentException("Gemini message role must be user or model");
        }
    }
}
