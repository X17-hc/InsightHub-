package com.hechang.insighthub.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.hechang.insighthub.config.JwtProperties;

import io.jsonwebtoken.JwtException;

class JwtServiceTest {

    private static final String SECRET = "test-secret-that-is-at-least-thirty-two-bytes-long";

    @Test
    void accessTokenCarriesAndValidatesSecurityContext() {
        JwtService jwtService = new JwtService(properties("insighthub", "web"));

        var claims = jwtService.parseClaims(jwtService.createAccessToken("user-1", "chang"));

        assertEquals("user-1", claims.getSubject());
        assertEquals("chang", claims.get("username", String.class));
        assertEquals("access", claims.get("token_type", String.class));
        assertEquals("insighthub", claims.getIssuer());
        assertEquals("web", claims.getAudience().iterator().next());
    }

    @Test
    void tokenForAnotherAudienceIsRejected() {
        JwtService issuer = new JwtService(properties("insighthub", "another-client"));
        JwtService verifier = new JwtService(properties("insighthub", "web"));

        assertThrows(JwtException.class,
                () -> verifier.parseClaims(issuer.createAccessToken("user-1", "chang")));
    }

    @Test
    void tokenForAnotherIssuerIsRejected() {
        JwtService issuer = new JwtService(properties("another-system", "web"));
        JwtService verifier = new JwtService(properties("insighthub", "web"));

        assertThrows(JwtException.class,
                () -> verifier.parseClaims(issuer.createAccessToken("user-1", "chang")));
    }

    private static JwtProperties properties(String issuer, String audience) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(SECRET);
        properties.setIssuer(issuer);
        properties.setAudience(audience);
        properties.setAccessExpireMinutes(10);
        return properties;
    }
}
