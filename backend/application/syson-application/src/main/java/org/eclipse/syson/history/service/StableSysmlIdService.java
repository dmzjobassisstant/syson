package org.eclipse.syson.history.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.springframework.stereotype.Service;

/**
 * Generates deterministic, stable IDs for SysML model elements based on their
 * containment path, EClass name, and element name.
 *
 * @author Syson
 */
@Service
public class StableSysmlIdService {

    /**
     * Produces a deterministic stable ID by computing the SHA-256 hash of the
     * concatenated input parameters.
     *
     * @param documentId
     *            the document identifier
     * @param containmentPath
     *            the containment path of the element
     * @param eClassName
     *            the EClass name of the element
     * @param name
     *            the name of the element (may be null)
     * @return a hex-encoded SHA-256 hash string
     */
    public String stableIdFor(String documentId, String containmentPath, String eClassName, String name) {
        if (name != null && !name.isEmpty()) {
            return sha256(documentId + ":" + containmentPath + ":" + eClassName + ":" + name);
        }
        return sha256(documentId + ":" + containmentPath + ":" + eClassName);
    }

    /**
     * Produces a deterministic stable ID for an EMF EObject by reading its
     * EClass name and attempting to resolve a 'name' structural feature.
     *
     * @param documentId
     *            the document identifier
     * @param containmentPath
     *            the containment path of the element
     * @param eObject
     *            the EMF EObject
     * @return a hex-encoded SHA-256 hash string
     */
    public String stableIdForEObject(String documentId, String containmentPath, EObject eObject) {
        EClass eClass = eObject.eClass();
        String eClassName = eClass.getName();
        String name = resolveName(eObject);
        return stableIdFor(documentId, containmentPath, eClassName, name);
    }

    /**
     * Attempts to resolve the 'name' feature of an EObject.
     *
     * @param eObject
     *            the EMF EObject
     * @return the name value as a string, or null if no 'name' feature exists
     */
    private String resolveName(EObject eObject) {
        EStructuralFeature nameFeature = eObject.eClass().getEStructuralFeature("name");
        if (nameFeature != null) {
            Object value = eObject.eGet(nameFeature);
            if (value != null) {
                return value.toString();
            }
        }
        return null;
    }

    /**
     * Computes a SHA-256 hash of the given input string.
     *
     * @param input
     *            the string to hash
     * @return the hex-encoded hash
     */
    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Converts a byte array to a hex string.
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
