package com.genhao.hal1000.auth;

import com.genhao.hal1000.persistence.entity.AppUserEntity;
import com.genhao.hal1000.persistence.repo.AppUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.security.SecureRandom;
import java.util.HexFormat;

@RestController
@RequestMapping("/api/auth")
public class AuthApiController {

    private static final String SESSION_OAUTH_STATE = "hal1000_github_oauth_state";

    private final AuthProperties props;
    private final GitHubOAuthClient oauthClient;
    private final GitHubApiClient apiClient;
    private final AppUserRepository userRepository;
    private final JwtService jwtService;

    public AuthApiController(
            AuthProperties props,
            GitHubOAuthClient oauthClient,
            GitHubApiClient apiClient,
            AppUserRepository userRepository,
            JwtService jwtService
    ) {
        this.props = props;
        this.oauthClient = oauthClient;
        this.apiClient = apiClient;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
    }

    @GetMapping("/github/start")
    public void githubStart(HttpServletRequest req, jakarta.servlet.http.HttpServletResponse resp) {
        requireGithubConfig();
        var state = randomState();
        HttpSession session = req.getSession(true);
        session.setAttribute(SESSION_OAUTH_STATE, state);

        // callback endpoint is served by this backend
        String redirectUri = UriComponentsBuilder.fromUriString(baseUrl(req))
                .path("/api/auth/github/callback")
                .toUriString();

        String authorizeUrl = UriComponentsBuilder.fromUriString("https://github.com/login/oauth/authorize")
                .queryParam("client_id", props.getGithubClientId())
                .queryParam("redirect_uri", redirectUri)
                .queryParam("scope", "read:user")
                .queryParam("state", state)
                .build()
                .toUriString();
        try {
            resp.setStatus(302);
            resp.setHeader("Location", authorizeUrl);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @GetMapping("/github/callback")
    public void githubCallback(
            HttpServletRequest req,
            jakarta.servlet.http.HttpServletResponse resp,
            @RequestParam("code") String code,
            @RequestParam(value = "state", required = false) String state
    ) {
        requireGithubConfig();
        HttpSession session = req.getSession(false);
        var expectedState = session != null ? (String) session.getAttribute(SESSION_OAUTH_STATE) : null;
        if (expectedState == null || state == null || !expectedState.equals(state)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid oauth state");
        }

        String accessToken = oauthClient.exchangeCodeForAccessToken(props.getGithubClientId(), props.getGithubClientSecret(), code);
        var ghUser = apiClient.fetchUser(accessToken);

        AppUserEntity user = userRepository.findByGithubId(ghUser.id()).orElseGet(AppUserEntity::new);
        user.setGithubId(ghUser.id());
        user.setGithubLogin(ghUser.login());
        user.setName(ghUser.name());
        user.setAvatarUrl(ghUser.avatarUrl());
        userRepository.save(user);

        String jwt = jwtService.issueToken(user.getId(), user.getGithubLogin());
        String target = UriComponentsBuilder.fromUriString(props.getFrontendRedirectUrl())
                .replaceQueryParam("token", jwt)
                .build()
                .toUriString();
        try {
            resp.setStatus(302);
            resp.setHeader("Location", target);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @GetMapping("/me")
    public MeResponse me(Authentication auth) {
        if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "not authenticated");
        }
        var user = userRepository.findById(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "user not found"));
        return new MeResponse(user.getId(), user.getGithubLogin(), user.getName(), user.getAvatarUrl());
    }

    private void requireGithubConfig() {
        if (props.getGithubClientId() == null || props.getGithubClientId().isBlank()
                || props.getGithubClientSecret() == null || props.getGithubClientSecret().isBlank()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "GitHub OAuth not configured");
        }
    }

    private static String baseUrl(HttpServletRequest req) {
        var scheme = req.getHeader("X-Forwarded-Proto");
        if (scheme == null || scheme.isBlank()) {
            scheme = req.getScheme();
        }
        var host = req.getHeader("X-Forwarded-Host");
        if (host == null || host.isBlank()) {
            host = req.getServerName() + ":" + req.getServerPort();
        }
        return scheme + "://" + host;
    }

    private static String randomState() {
        byte[] buf = new byte[16];
        new SecureRandom().nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }

    public record MeResponse(String id, String githubLogin, String name, String avatarUrl) {
    }
}

