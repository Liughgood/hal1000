package com.genhao.hal1000.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;

@Component
public class GitHubApiClient {

    private final WebClient api;
    private final ObjectMapper objectMapper;

    public GitHubApiClient() {
        this.api = WebClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.USER_AGENT, "HAL1000")
                .build();
        this.objectMapper = new ObjectMapper().findAndRegisterModules();
    }

    public GitHubUser fetchUser(String accessToken) {
        try {
            var json = api.get()
                    .uri("/user")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();
            if (json == null) throw new IllegalStateException("empty /user response");
            JsonNode root = objectMapper.readTree(json);
            long id = root.path("id").asLong(0);
            String login = root.path("login").asText("");
            String name = root.path("name").isNull() ? null : root.path("name").asText(null);
            String avatarUrl = root.path("avatar_url").isNull() ? null : root.path("avatar_url").asText(null);
            if (id <= 0 || login.isBlank()) {
                throw new IllegalStateException("invalid /user payload");
            }
            return new GitHubUser(id, login, name, avatarUrl);
        } catch (WebClientResponseException e) {
            throw new IllegalStateException("github /user failed: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            throw new IllegalStateException("github /user failed: " + e.getMessage(), e);
        }
    }

    public record GitHubUser(long id, String login, String name, String avatarUrl) {
    }
}

