/*
 * Copyright (c) 2021 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.gnmi.southbound.schema.loader.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.apache.commons.io.IOUtils;
import org.opendaylight.yangtools.concepts.SemVer;
import org.opendaylight.yangtools.openconfig.model.api.OpenConfigVersionStatement;
import org.opendaylight.yangtools.yang.common.Revision;
import org.opendaylight.yangtools.yang.ir.IRArgument.Single;
import org.opendaylight.yangtools.yang.ir.IRStatement;
import org.opendaylight.yangtools.yang.model.api.source.SourceSyntaxException;
import org.opendaylight.yangtools.yang.model.api.source.YangTextSource;
import org.opendaylight.yangtools.yang.model.spi.source.YangIRSource;
import org.opendaylight.yangtools.yang.parser.api.YangSyntaxErrorException;
import org.opendaylight.yangtools.yang.source.ir.DefaultYangTextToIRSourceTransformer;

public class YangLoadModelUtil {

    private static final String OPENCONFIG_VERSION = OpenConfigVersionStatement.DEF.simpleName();
    private final Revision modelRevision;
    private final SemVer modelSemVer;
    private final String modelBody;
    private final String modelName;

    public YangLoadModelUtil(final YangTextSource yangTextSchemaSource, final InputStream yangTextStream)
        throws YangSyntaxErrorException, IOException, SourceSyntaxException {

        // 1. Transform the string into the native AST (Replaces TextToIRTransformer)
        final YangIRSource irSchemaSource = new DefaultYangTextToIRSourceTransformer()
            .transformSource(yangTextSchemaSource);
        final IRStatement rootStatement = irSchemaSource.statement();
        final Optional<SemVer> semanticVersion = getSemVer(rootStatement);

        this.modelRevision = extractMaxRevision(rootStatement);
        this.modelSemVer = semanticVersion.orElse(null);
        this.modelBody = IOUtils.toString(yangTextStream, StandardCharsets.UTF_8);
        this.modelName = yangTextSchemaSource.sourceId().name().getLocalName();
    }

    public String getVersionToStore() {
        if (modelSemVer != null) {
            return modelSemVer.toString();
        } else if (modelRevision != null) {
            return modelRevision.toString();
        } else {
            return "";
        }
    }

    public Revision getModelRevision() {
        return modelRevision;
    }

    public SemVer getModelSemVer() {
        return modelSemVer;
    }

    public String getModelBody() {
        return modelBody;
    }

    public String getModelName() {
        return modelName;
    }

    private static Optional<SemVer> getSemVer(final IRStatement stmt) {
        for (final var substatement : stmt.statements()) {
            if (OPENCONFIG_VERSION.equals(substatement.keyword().identifier())) {
                final var argument = substatement.argument();
                if (argument instanceof Single) {
                    try {
                        return Optional.of(SemVer.valueOf(((Single) argument).string()));
                    } catch (IllegalArgumentException e) {
                        // Ignore malformed semvers
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Traverses the root IRStatement to find all "revision" declarations, returning the newest one.
     */
    private static Revision extractMaxRevision(final IRStatement rootStatement) {
        final List<Revision> revisions = new ArrayList<>();

        for (final IRStatement stmt : rootStatement.statements()) {
            if ("revision".equals(stmt.keyword().identifier())) {
                final var argument = stmt.argument();
                if (argument instanceof Single) {
                    final String revStr = ((Single) argument).string();
                    Revision.ofNullable(revStr).ifPresent(revisions::add);
                }
            }
        }

        // OpenDaylight's 'Revision' implements Comparable properly, so naturalOrder() finds the most recent date.
        return revisions.stream().max(Comparator.naturalOrder()).orElse(null);
    }
}