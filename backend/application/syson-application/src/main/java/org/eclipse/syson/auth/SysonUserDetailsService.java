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

import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.syson.auth.entity.SysonUser;
import org.eclipse.syson.auth.repository.MembershipRepository;
import org.eclipse.syson.auth.repository.UserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Spring Security {@link UserDetailsService} that loads users from {@link UserRepository}
 * and resolves their roles from {@link MembershipRepository}.
 *
 * @author syson-team
 */
@Service
public class SysonUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    private final MembershipRepository membershipRepository;

    public SysonUserDetailsService(UserRepository userRepository, MembershipRepository membershipRepository) {
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        SysonUser sysonUser = this.userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));

        // Derive Spring Security authorities from tenant memberships
        List<SimpleGrantedAuthority> authorities = this.membershipRepository
                .findByTenantMembershipIdUserId(sysonUser.getId())
                .stream()
                .map(m -> new SimpleGrantedAuthority("ROLE_" + m.getRole().toUpperCase()))
                .collect(Collectors.toList());

        // Fallback: everyone gets ROLE_USER
        if (authorities.isEmpty()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        }

        return User.builder()
                .username(sysonUser.getEmail())
                .password(sysonUser.getPasswordHash())
                .accountLocked(sysonUser.getLockedUntil() != null
                        && sysonUser.getLockedUntil().isAfter(java.time.OffsetDateTime.now()))
                .disabled(!sysonUser.isActive())
                .authorities(authorities)
                .build();
    }
}
