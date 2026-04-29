/*
 * Copyright (c) 2021 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.gnmi.simulatordevice.utils;

import com.google.common.io.CharSource;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.jdt.annotation.Nullable;
import org.opendaylight.gnmi.commons.util.YangModelSanitizer;
import org.opendaylight.yangtools.binding.meta.YangModuleInfo;
import org.opendaylight.yangtools.yang.model.api.EffectiveModelContext;
import org.opendaylight.yangtools.yang.model.api.source.SourceIdentifier;
import org.opendaylight.yangtools.yang.model.api.source.YangTextSource;
import org.opendaylight.yangtools.yang.model.spi.source.StringYangTextSource;
import org.opendaylight.yangtools.yang.parser.api.YangParser;
import org.opendaylight.yangtools.yang.parser.api.YangParserException;
import org.opendaylight.yangtools.yang.parser.ri.DefaultYangParserFactory;

/**
 * EffectiveModelContextBuilder build {@link EffectiveModelContext} from provided path to folder which contains
 * yang models and also from provided instances of {@link YangModuleInfo}.
 */
public class EffectiveModelContextBuilder {

    private String yangModulesPath;
    private Set<YangModuleInfo> yangModulesInfo;

    /**
     * Add path to yang models folder. This models will be used for constructing {@link EffectiveModelContext}.
     *
     * @param path could be null but YangModuleInfo have to be added otherwise build method fail
     * @return {@link EffectiveModelContextBuilder}
     * @throws EffectiveModelContextBuilderException if path is nonnull and empty
     */
    public EffectiveModelContextBuilder addYangModulesPath(@Nullable final String path)
            throws EffectiveModelContextBuilderException {
        if (path != null && path.isEmpty()) {
            throw new EffectiveModelContextBuilderException("Provided path to YANG modules is empty");
        }
        this.yangModulesPath = path;
        return this;
    }

    /**
     * Add models YangModuleInfo. This models will be used for constructing {@link EffectiveModelContext}.
     *
     * @param yangModuleInfoSet could be null but yangModulesPath have to be added otherwise build method fail
     * @return {@link EffectiveModelContextBuilder}
     * @throws EffectiveModelContextBuilderException if yangModulesPath is nonnull and empty
     */
    public EffectiveModelContextBuilder addYangModulesInfo(@Nullable final Set<YangModuleInfo> yangModuleInfoSet)
            throws EffectiveModelContextBuilderException {
        if (yangModuleInfoSet != null && yangModuleInfoSet.isEmpty()) {
            throw new EffectiveModelContextBuilderException("Provided list of YangModuleInfo  is empty");
        }
        this.yangModulesInfo = yangModuleInfoSet;
        return this;
    }

    /**
     * Construct {@link EffectiveModelContext} from provided yang models in {@link #addYangModulesInfo(Set)} and {@link
     * #addYangModulesPath(String)}'.
     *
     * @return {@link EffectiveModelContext}
     * @throws EffectiveModelContextBuilderException if models information was not provided or occur any exception
     *                                               during construct EffectiveModelContext
     */
    public EffectiveModelContext build() throws EffectiveModelContextBuilderException {
        if (this.yangModulesPath == null && this.yangModulesInfo == null) {
            throw new EffectiveModelContextBuilderException("Cannot create EffectiveModelContext without "
                + "yangModulesPath or yangModulesInfo");
        }

        final YangParser parser = new DefaultYangParserFactory().createParser();
        try {
            if (this.yangModulesInfo != null) {
                for (final YangTextSource source : createYangSourcesFromYangModulesInfo(this.yangModulesInfo)) {
                    parser.addSource(source);
                }
            }

            if (this.yangModulesPath != null) {
                for (final YangTextSource source : createYangSourcesFromYangModulesPath(this.yangModulesPath)) {
                    parser.addSource(source);
                }
            }
            return parser.buildEffectiveModel();
        } catch (YangParserException | IOException e) {
            throw new EffectiveModelContextBuilderException("Failed to create EffectiveModelContext", e);
        }
    }

    private static List<YangTextSource> createYangSourcesFromYangModulesPath(final String path)
            throws EffectiveModelContextBuilderException {
        final List<YangTextSource> sources = new ArrayList<>();
        try (Stream<Path> pathStream = Files.walk(Path.of(path))) {
            final List<File> filesInFolder = pathStream
                    .filter(Files::isRegularFile)
                    .map(Path::toFile)
                    .collect(Collectors.toList());
            for (File file : filesInFolder) {
                final CharSource sanitizedYangByteSource = YangModelSanitizer
                        .removeRegexpPosix(com.google.common.io.Files.asCharSource(file, StandardCharsets.UTF_8));
                sources.add(new StringYangTextSource(
                    SourceIdentifier.ofYangFileName(file.getName()),
                    sanitizedYangByteSource.read()));
            }
        } catch (IOException e) {
            final String errorMsg = String.format("Failed to create YangTextSource from provided path: [%s]", path);
            throw new EffectiveModelContextBuilderException(errorMsg, e);
        }
        return sources;
    }

    private static List<YangTextSource> createYangSourcesFromYangModulesInfo(final Set<YangModuleInfo> yangModulesInfo)
            throws EffectiveModelContextBuilderException {
        final List<YangTextSource> sources = new ArrayList<>(yangModulesInfo.size());
        for (YangModuleInfo yangModuleInfo : yangModulesInfo) {
            try {
                final CharSource sanitizedYangByteSource = YangModelSanitizer
                        .removeRegexpPosix(yangModuleInfo.getYangTextCharSource());
                sources.add(new StringYangTextSource(
                        new SourceIdentifier(yangModuleInfo.getName().getLocalName()),
                        sanitizedYangByteSource.read()));
            } catch (IOException e) {
                final String errorMsg = String.format("Failed to create YangTextSource from "
                        + "provided YangModuleInfo: [%s]", yangModuleInfo);
                throw new EffectiveModelContextBuilderException(errorMsg, e);
            }
        }
        return sources;
    }

    public static class EffectiveModelContextBuilderException extends Exception {

        public EffectiveModelContextBuilderException(String message) {
            super(message);
        }

        public EffectiveModelContextBuilderException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
