/*******************************************************************************
 * Copyright (c) 2026 Damuza Consulting.
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v2.0 which accompanies this distribution.
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.syson.auth;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves i18n translation files for the Sirius Web frontend.
 *
 * <p>The bundled frontend uses i18next-http-backend to load translations from
 * {@code /api/locales/{language}/{namespace}.json}. Without this controller,
 * the frontend falls back to showing raw translation keys instead of user-facing text.</p>
 *
 * @author Damuza Consulting
 */
@RestController
public class LocaleController {

    @GetMapping(path = "/api/locales/{language}/{namespace}.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getLocale(@PathVariable String language, @PathVariable String namespace) {
        String normalizedLanguage = language;
        ClassPathResource resource = new ClassPathResource("i18n/%s/%s.json".formatted(normalizedLanguage, namespace));

        if (!resource.exists() && language != null && language.contains("-")) {
            normalizedLanguage = language.substring(0, language.indexOf('-'));
            resource = new ClassPathResource("i18n/%s/%s.json".formatted(normalizedLanguage, namespace));
        }
        if (!resource.exists()) {
            resource = new ClassPathResource("i18n/en/%s.json".formatted(namespace));
        }

        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }

        try (InputStream is = resource.getInputStream()) {
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(content);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
