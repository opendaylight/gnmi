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
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.opendaylight.yangtools.yang.model.api.EffectiveModelContext;
import org.opendaylight.yangtools.yang.model.api.source.SourceIdentifier;
import org.opendaylight.yangtools.yang.model.spi.source.DelegatedYangTextSource;
import org.opendaylight.yangtools.yang.parser.api.YangParserException;
import org.opendaylight.yangtools.yang.parser.rfc7950.reactor.RFC7950Reactors;
import org.opendaylight.yangtools.yang.parser.rfc7950.repo.YangStatementStreamSource;
import org.opendaylight.yangtools.yang.parser.spi.meta.ReactorException;
import org.opendaylight.yangtools.yang.parser.stmt.reactor.CrossSourceStatementReactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FileUtils {
    private static final Logger LOG = LoggerFactory.getLogger(FileUtils.class);

    private FileUtils() {
        //Utility class
    }

    public static EffectiveModelContext buildSchemaFromYangsDir(final String path) {
        final CrossSourceStatementReactor.BuildAction buildAction = RFC7950Reactors.defaultReactorBuilder()
                .build().newBuild();
        try (Stream<Path> pathStream = Files.walk(Path.of(toResourcePath(path)))) {
            final List<File> filesInFolder = pathStream
                    .filter(Files::isRegularFile)
                    .map(Path::toFile)
                    .collect(Collectors.toList());

            for (File file : filesInFolder) {
                final YangStatementStreamSource statementSource = YangStatementStreamSource.create(
                        new DelegatedYangTextSource(
                                SourceIdentifier.ofYangFileName(file.getName()), CharSource.wrap(
                                (CharSequence) com.google.common.io.Files.asCharSource(file,StandardCharsets.UTF_8))));

                buildAction.addSource(statementSource);
            }
            return buildAction.buildEffective();
        } catch (IOException | YangParserException | ReactorException | URISyntaxException e) {
            throw new RuntimeException("Constructing schema from provided path failed!", e);
        }
    }

    public static InputStream getResourceAsStream(final String path) throws IOException {
        InputStream resourceStream = FileUtils.class.getResourceAsStream(path);
        if (resourceStream != null) {
            return resourceStream;
        }
        return Files.newInputStream(Path.of(path));
    }

    public static String toResourcePath(final String path) throws URISyntaxException {
        String ret =  Path.of(Objects.requireNonNull(
                FileUtils.class.getResource(path),
                "Missing resource: " + path
        ).toURI()).toString();

        return ret;
    }

    public static Path toResourcePath(final Path input) throws URISyntaxException {
        if (Files.exists(input)) {
            return input;
        }

        var url = Objects.requireNonNull(
                FileUtils.class.getResource(input.toString()),
                "Missing resource: " + input
        );
        return Path.of(url.toURI());
    }
}
