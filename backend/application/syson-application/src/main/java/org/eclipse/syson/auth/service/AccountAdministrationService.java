package org.eclipse.syson.auth.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.eclipse.syson.auth.entity.SysonUser;
import org.eclipse.syson.auth.entity.TenantMembership;
import org.eclipse.syson.auth.model.AuditEventType;
import org.eclipse.syson.auth.repository.MembershipRepository;
import org.eclipse.syson.auth.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountAdministrationService {
    private final UserRepository userRepository;
    private final MembershipRepository membershipRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;

    public AccountAdministrationService(UserRepository userRepository, MembershipRepository membershipRepository,
            PasswordEncoder passwordEncoder, AuditLogService auditLogService) {
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public SysonUser createUser(CreateUserCommand command) {
        if (this.userRepository.existsByEmail(command.email())) {
            throw new IllegalArgumentException("User already exists");
        }
        OffsetDateTime now = OffsetDateTime.now();
        SysonUser user = new SysonUser();
        user.setEmail(command.email().trim().toLowerCase());
        user.setName(command.name());
        user.setPasswordHash(this.passwordEncoder.encode(command.password()));
        user.setActive(true);
        user.setEmailVerified(false);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        user.setPasswordChangedAt(now);
        SysonUser saved = this.userRepository.save(user);
        if (command.tenantId() != null && command.tenantRole() != null) {
            TenantMembership membership = new TenantMembership(saved.getId(), command.tenantId(), command.tenantRole().dbValue());
            membership.setCreatedAt(now);
            this.membershipRepository.save(membership);
        }
        this.auditLogService.recordAccountEvent(AuditEventType.ADMIN_USER_CREATED, saved.getId(), saved.getId(), saved.getEmail());
        return saved;
    }

    @Transactional
    public void deactivateUser(UUID userId) {
        SysonUser user = this.userRepository.findById(userId).orElseThrow();
        user.setActive(false);
        user.setDeactivatedAt(OffsetDateTime.now());
        user.setUpdatedAt(OffsetDateTime.now());
        this.userRepository.save(user);
        this.auditLogService.recordAccountEvent(AuditEventType.ADMIN_USER_DEACTIVATED, userId, userId, user.getEmail());
    }

    @Transactional
    public void reactivateUser(UUID userId) {
        SysonUser user = this.userRepository.findById(userId).orElseThrow();
        user.setActive(true);
        user.setDeactivatedAt(null);
        user.setUpdatedAt(OffsetDateTime.now());
        this.userRepository.save(user);
        this.auditLogService.recordAccountEvent(AuditEventType.ADMIN_USER_REACTIVATED, userId, userId, user.getEmail());
    }

    public List<SysonUser> listUsers(UserSearchCriteria criteria) {
        return this.userRepository.findAll().stream()
                .filter(user -> criteria == null || criteria.active() == null || user.isActive() == criteria.active())
                .filter(user -> criteria == null || criteria.query() == null
                        || user.getEmail().toLowerCase().contains(criteria.query().toLowerCase())
                        || (user.getName() != null && user.getName().toLowerCase().contains(criteria.query().toLowerCase())))
                .toList();
    }
}
