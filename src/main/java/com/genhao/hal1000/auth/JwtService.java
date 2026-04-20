package com.genhao.hal1000.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class JwtService {

    private final AuthProperties props;
    private final Algorithm alg;
    private final JWTVerifier verifier;

    public JwtService(AuthProperties props) {
        this.props = props;
        if (props.getJwtSecret() == null || props.getJwtSecret().isBlank()) {
            throw new IllegalStateException("HAL1000_AUTH_JWT_SECRET is required");
        }
        this.alg = Algorithm.HMAC256(props.getJwtSecret());
        this.verifier = JWT.require(alg)
                .withIssuer(props.getJwtIssuer())
                .build();
    }

    public String issueToken(String userId, String githubLogin) {
        var now = Instant.now();
        return JWT.create()
                .withIssuer(props.getJwtIssuer())
                .withSubject(userId)
                .withClaim("github_login", githubLogin)
                .withIssuedAt(now)
                .withExpiresAt(now.plusSeconds(60L * 60L * 24L * 30L)) // 30 days
                .sign(alg);
    }

    public DecodedJWT verify(String token) throws JWTVerificationException {
        return verifier.verify(token);
    }
}

