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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.eclipse.syson.auth.entity.SysonUser;
import org.eclipse.syson.auth.model.AuditEventType;
import org.eclipse.syson.auth.repository.MembershipRepository;
import org.eclipse.syson.auth.repository.UserRepository;
import org.eclipse.syson.auth.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing authentication endpoints at {@code /api/auth}.
 *
 * @author syson-team
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final SysonUserDetailsService userDetailsService;

    private final JwtService jwtService;

    private final PasswordEncoder passwordEncoder;

    private final MembershipRepository membershipRepository;

    private final UserRepository userRepository;

    private final AuditLogService auditLogService;

    private final AdminService adminService;

    public AuthController(SysonUserDetailsService userDetailsService,
                          JwtService jwtService,
                          PasswordEncoder passwordEncoder,
                          MembershipRepository membershipRepository,
                          UserRepository userRepository,
                          AuditLogService auditLogService,
                          AdminService adminService) {
        this.userDetailsService = userDetailsService;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.membershipRepository = membershipRepository;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
        this.adminService = adminService;
    }

    /**
     * Authenticates a user with email and password, returning a JWT.
     */
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        UserDetails userDetails = this.userDetailsService.loadUserByUsername(request.email());

        if (!this.passwordEncoder.matches(request.password(), userDetails.getPassword())) {
            this.adminService.logEventAs("login_failed", null, request.email(), "auth", null, request.email(), httpRequest);
            throw new BadCredentialsException("Invalid email or password");
        }

        // Look up the SysonUser to get the UUID for membership resolution
        SysonUser user = this.userRepository.findByEmail(request.email())
                .orElseThrow(() -> {
                    this.adminService.logEventAs("login_failed", null, request.email(), "auth", null, request.email(), httpRequest);
                    return new BadCredentialsException("Invalid email or password");
                });
        UUID userId = user.getId();

        // Pick the first membership's tenant
        UUID tenantId = this.membershipRepository
                .findByIdUserId(userId)
                .stream()
                .findFirst()
                .map(m -> m.getId().getTenantId())
                .orElse(UUID.fromString("00000000-0000-0000-0000-000000000001"));

        String token = this.jwtService.generateToken(userDetails, tenantId, userId);
        user.setLastLoginAt(OffsetDateTime.now());
        user.setFailedLoginAttempts(0);
        this.userRepository.save(user);
        this.auditLogService.recordAccountEvent(AuditEventType.AUTH_LOGIN_SUCCESS, userId, userId, user.getEmail());
        this.adminService.logEventAs("login_success", userId, user.getEmail(), "auth", userId.toString(), user.getEmail(), httpRequest);

        // Resolve roles
        List<String> roles = userDetails.getAuthorities().stream()
                .map(a -> a.getAuthority().replace("ROLE_", "").toLowerCase())
                .toList();

        return ResponseEntity.ok(new LoginResponse(token, userDetails.getUsername(), roles));
    }

    /**
     * Refreshes a still-valid JWT by issuing a new one.
     */
    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(@RequestHeader("Authorization") String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new BadCredentialsException("Missing or invalid Authorization header");
        }

        String token = authHeader.substring(7);
        String username = this.jwtService.extractUsername(token);
        UserDetails userDetails = this.userDetailsService.loadUserByUsername(username);

        if (!this.jwtService.isTokenValid(token, userDetails)) {
            throw new BadCredentialsException("Token is expired or invalid");
        }

        UUID tenantId = this.jwtService.extractTenantId(token);
        String userIdStr = this.jwtService.extractUserId(token);
        UUID userId = UUID.fromString(userIdStr);
        String newToken = this.jwtService.generateToken(userDetails, tenantId, userId);

        List<String> roles = userDetails.getAuthorities().stream()
                .map(a -> a.getAuthority().replace("ROLE_", "").toLowerCase())
                .toList();

        return ResponseEntity.ok(new LoginResponse(newToken, userDetails.getUsername(), roles));
    }

    /**
     * Logout is a no-op in stateless JWT auth (client discards the token).
     */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout() {
        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }

    // --- DTO records ---

    public record LoginRequest(String email, String password) {
    }

    public record LoginResponse(String token, String email, List<String> roles) {
    }
}
