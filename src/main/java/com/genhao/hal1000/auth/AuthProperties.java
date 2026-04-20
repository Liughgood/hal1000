package com.genhao.hal1000.auth;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "hal1000.auth")
public class AuthProperties {

    private String jwtSecret;
    private String jwtIssuer = "hal1000";
    private String frontendRedirectUrl = "http://localhost:5173/";

    private String githubClientId;
    private String githubClientSecret;
}

