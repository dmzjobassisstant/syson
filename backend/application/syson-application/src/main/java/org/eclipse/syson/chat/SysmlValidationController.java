/*******************************************************************************
 * Copyright (c) 2026 Obeo.
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Obeo - initial API and implementation
 *******************************************************************************/
package org.eclipse.syson.chat;

import org.eclipse.syson.chat.dto.ValidateRequest;
import org.eclipse.syson.chat.dto.ValidateResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for SysML syntax validation.
 * <p>
 * Endpoint: {@code POST /api/v1/sysml/validate}
 * </p>
 *
 * @author syson-team
 */
@RestController
@RequestMapping("/api/v1/sysml")
public class SysmlValidationController {

    private final SysmlSyntaxValidator validator;

    public SysmlValidationController(SysmlSyntaxValidator validator) {
        this.validator = validator;
    }

    /**
     * Validates SysML v2 source text and returns diagnostics.
     *
     * @param request a {@link ValidateRequest} with {@code source} and optional {@code fileId}
     * @return a {@link ValidateResponse} with validation results
     */
    @PostMapping("/validate")
    public ResponseEntity<ValidateResponse> validate(@RequestBody ValidateRequest request) {
        String source = request.source() != null ? request.source() : "";
        String fileId = request.fileId() != null ? request.fileId() : "model.sysml";
        ValidateResponse result = this.validator.validate(source, fileId);
        return ResponseEntity.ok(result);
    }
}
