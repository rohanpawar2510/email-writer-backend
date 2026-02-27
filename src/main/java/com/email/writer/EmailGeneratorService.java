package com.email.writer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
public class EmailGeneratorService {

    private final WebClient webClient = WebClient.create();

    @Value("${gemini.api.url}")
    private String geminiApiUrl;

    @Value("${gemini.api.key}")
    private String geminiApiKey;

    @Value("${claude.api.url}")
    private String claudeApiUrl;

    @Value("${claude.api.key}")
    private String claudeApiKey;

    public String generateEmailReply(EmailRequest emailRequest) {
        if ("claude".equalsIgnoreCase(emailRequest.getAiModel())) {
            return generateWithClaude(emailRequest);
        }
        return generateWithGemini(emailRequest);
    }

    private String generateWithGemini(EmailRequest emailRequest) {
        String prompt = buildPrompt(emailRequest);

        // ✅ EXACT JSON BODY (Gemini-compatible)
        String requestBody = """
        {
          "contents": [
            {
              "parts": [
                {
                  "text": "%s"
                }
              ]
            }
          ]
        }
        """.formatted(prompt.replace("\"", "\\\""));

        String response = webClient.post()
                .uri(geminiApiUrl + "?key=" + geminiApiKey)
                .header("Content-Type", "application/json")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        if (response == null) {
            return "No response from Gemini API";
        }

        return extractGeminiResponseContent(response);
    }

    private String generateWithClaude(EmailRequest emailRequest) {
        String prompt = buildPrompt(emailRequest);

        ObjectMapper mapper = new ObjectMapper();
        String requestBody;
        try {
            requestBody = mapper.writeValueAsString(java.util.Map.of(
                    "model", "claude-3-5-sonnet-20241022",
                    "max_tokens", 1024,
                    "messages", java.util.List.of(
                            java.util.Map.of("role", "user", "content", prompt)
                    )
            ));
        } catch (Exception e) {
            return "Error building Claude request: " + e.getMessage();
        }

        String response = webClient.post()
                .uri(claudeApiUrl)
                .header("Content-Type", "application/json")
                .header("x-api-key", claudeApiKey)
                .header("anthropic-version", "2023-06-01")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        if (response == null) {
            return "No response from Claude API";
        }

        return extractClaudeResponseContent(response);
    }

    private String extractGeminiResponseContent(String response) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode rootNode = mapper.readTree(response);
            return rootNode.path("candidates")
                    .get(0)
                    .path("content")
                    .path("parts")
                    .get(0)
                    .path("text")
                    .asText();
        } catch (Exception e) {
            return "Error parsing Gemini response: " + e.getMessage();
        }
    }

    private String extractClaudeResponseContent(String response) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode rootNode = mapper.readTree(response);
            JsonNode contentArray = rootNode.path("content");
            if (!contentArray.isArray() || contentArray.isEmpty()) {
                return "No content in Claude response";
            }
            return contentArray.get(0).path("text").asText();
        } catch (Exception e) {
            return "Error parsing Claude response: " + e.getMessage();
        }
    }

    private String buildPrompt(EmailRequest emailRequest) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Generate a professional email reply. ");
        if (emailRequest.getTone() != null && !emailRequest.getTone().isEmpty()) {
            prompt.append("Use a ").append(emailRequest.getTone()).append(" tone. ");
        }
        prompt.append("\n\nOriginal email:\n")
                .append(emailRequest.getEmailContent());
        return prompt.toString();
    }
}
