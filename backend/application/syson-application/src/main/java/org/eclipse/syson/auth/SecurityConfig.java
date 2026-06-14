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

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security configuration for SysON.
 * <p>
 * Stateless JWT-based authentication that runs alongside Sirius Web.
 * CSRF is disabled (API only). Public paths are whitelisted; all other
 * {@code /api/v1/**} endpoints require authentication.
 * </p>
 *
 * @author syson-team
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    private final TenantFilter tenantFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          TenantFilter tenantFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.tenantFilter = tenantFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, authException) -> {
                            if (request.getRequestURI().startsWith("/api/")) {
                                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                                response.getWriter().write("{\"error\":\"unauthorized\"}");
                            } else {
                                response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                            }
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            if (request.getRequestURI().startsWith("/api/")) {
                                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                                response.getWriter().write("{\"error\":\"forbidden\"}");
                            } else {
                                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                            }
                        }))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/api/graphql").permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/api/v1/user/password/reset/**").permitAll()
                        .requestMatchers("/api/v1/user/admin/audit-trail", "/api/v1/user/admin/audit/**").hasAuthority("ROLE_SUPERUSER")
                        .requestMatchers("/api/v1/user/admin/**").hasAnyAuthority("ROLE_SUPERUSER", "ROLE_ADMIN")
                        .requestMatchers("/api/v1/user/**").authenticated()
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().permitAll()
                )
                .addFilterBefore(this.jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(this.tenantFilter, JwtAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
