package com.pnltracker.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class GeminiApiClientTest {

    @Test
    void extractTextReturnsAllTextPartsFromSelectedCandidate() {
        GeminiApiClient client = new GeminiApiClient(new GeminiProperties(), new ObjectMapper());

        String text = client.extractText("""
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          { "text": "First part. " },
                          { "text": "Second part." }
                        ]
                      }
                    }
                  ]
                }
                """);

        assertThat(text).isEqualTo("First part. Second part.");
    }

    @Test
    void extractTextRejectsTruncatedResponses() {
        GeminiApiClient client = new GeminiApiClient(new GeminiProperties(), new ObjectMapper());

        assertThatThrownBy(() -> client.extractText("""
                {
                  "candidates": [
                    {
                      "finishReason": "MAX_TOKENS",
                      "content": {
                        "parts": [
                          { "text": "Options:\\n1. **Jupiter**\\n2. **" }
                        ]
                      }
                    }
                  ]
                }
                """))
                .isInstanceOf(AiAdvisorException.class)
                .hasMessageContaining("truncated");
    }

    @Test
    void geminiPropertiesDoNotSetAppSideOutputTokenLimitByDefault() {
        assertThat(new GeminiProperties().getMaxTokens()).isNull();
    }
}
