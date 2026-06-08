package org.eclipse.syson.warehouse.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.sirius.components.core.api.IObjectService;
import org.springframework.stereotype.Service;

/**
 * Generates deterministic, stable IDs for EMF objects.
 * Prefers existing Sirius/EMF IDs; falls back to path-based hash.
 */
@Service
public class StableIdService {

    private final IObjectService objectService;

    public StableIdService(IObjectService objectService) {
        this.objectService = objectService;
    }

    /**
     * Returns a stable string ID for the given EObject.
     * Uses the Sirius IObjectService when available, otherwise hashes containment path.
     */
    public String stableId(EObject eObject) {
        // Try Sirius IObjectService first
        String existingId = objectService.getId(eObject);
        if (existingId != null && !existingId.isBlank()) {
            return existingId;
        }
        // Fallback: deterministic hash from containment path
        return hashPath(eObject);
    }

    private String hashPath(EObject eObject) {
        StringBuilder path = new StringBuilder();
        EObject current = eObject;
        while (current != null) {
            String className = current.eClass() != null ? current.eClass().getName() : "unknown";
            String name = "";
            try {
                var nameFeature = current.eClass().getEStructuralFeature("name");
                if (nameFeature != null) {
                    Object val = current.eGet(nameFeature);
                    if (val != null) name = val.toString();
                }
            } catch (Exception ignored) {}
            path.insert(0, "/" + className + ":" + name);
            current = current.eContainer();
        }
        return sha256(path.toString());
    }

    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
