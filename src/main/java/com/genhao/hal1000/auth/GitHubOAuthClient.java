package com.genhao.hal1000.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.Map;

@Component
public class GitHubOAuthClient {

    private final WebClient client;
    private final ObjectMapper objectMapper;

    public GitHubOAuthClient() {
        this.client = WebClient.builder()
                .baseUrl("https://github.com")
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.objectMapper = new ObjectMapper().findAndRegisterModules();
    }

    public String exchangeCodeForAccessToken(String clientId, String clientSecret, String code) {
        try {
            var json = client.post()
                    .uri("/login/oauth/access_token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of(
                            "client_id", clientId,
                            "client_secret", clientSecret,
                            "code", code
                    ))
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();
            if (json == null) throw new IllegalStateException("empty access_token response");
            JsonNode root = objectMapper.readTree(json);
            var token = root.path("access_token").asText("");
            if (token.isBlank()) {
                throw new IllegalStateException("missing access_token");
            }
            return token;
        } catch (WebClientResponseException e) {
            throw new IllegalStateException("github token exchange failed: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            throw new IllegalStateException("github token exchange failed: " + e.getMessage(), e);
        }
    }
}

