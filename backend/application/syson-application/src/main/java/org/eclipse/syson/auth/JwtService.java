/*******************************************************************************
 * Copyright (c) 2026 Obeo.
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Obeo - initial API and implementation
 *******************************************************************************/
package org.eclipse.syson.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

/**
 * Service for generating and validating JSON Web Tokens (JWT).
 * Uses HMAC-SHA256 with a configurable secret key and 24-hour token expiry.
 *
 * @author syson-team
 */
@Service
public class JwtService {

    private static final long TOKEN_EXPIRY_MS = 24 * 60 * 60 * 1000; // 24 hours

    private static final String CLAIM_TENANT_ID = "tenantId";

    private final SecretKey signingKey;

    public JwtService(@Value("${syson.auth.jwt.secret}") String secret) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes());
    }

    /**
     * Generates a signed JWT for the given user and tenant.
     *
     * @param userDetails the authenticated user
     * @param tenantId    the tenant context for this token
     * @return a compact JWT string
     */
    public String generateToken(UserDetails userDetails, UUID tenantId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(CLAIM_TENANT_ID, tenantId.toString());
        return this.buildToken(claims, userDetails.getUsername());
    }

    /**
     * Extracts the username (subject) from a token.
     */
    public String extractUsername(String token) {
        return this.extractClaim(token, Claims::getSubject);
    }

    /**
     * Extracts the tenant ID from a token.
     */
    public UUID extractTenantId(String token) {
        String tenantIdStr = this.extractClaim(token, claims -> claims.get(CLAIM_TENANT_ID, String.class));
        return UUID.fromString(tenantIdStr);
    }

    /**
     * Validates that the token is unexpired and belongs to the given user.
     */
    public boolean isTokenValid(String token, UserDetails userDetails) {
        String username = this.extractUsername(token);
        return username.equals(userDetails.getUsername()) && !this.isTokenExpired(token);
    }

    private String buildToken(Map<String, Object> claims, String subject) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .claims(claims)
                .subject(subject)
                .issuedAt(new Date(now))
                .expiration(new Date(now + TOKEN_EXPIRY_MS))
                .signWith(this.signingKey)
                .compact();
    }

    private boolean isTokenExpired(String token) {
        return this.extractExpiration(token).before(new Date());
    }

    private Date extractExpiration(String token) {
        return this.extractClaim(token, Claims::getExpiration);
    }

    private <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        Claims claims = Jwts.parser()
                .verifyWith(this.signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claimsResolver.apply(claims);
    }
}
