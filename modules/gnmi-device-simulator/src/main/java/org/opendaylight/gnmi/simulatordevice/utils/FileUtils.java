/*
 * Copyright (c) 2021 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.gnmi.simulatordevice.utils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.opendaylight.yangtools.yang.model.api.EffectiveModelContext;
import org.opendaylight.yangtools.yang.model.api.source.SourceIdentifier;
import org.opendaylight.yangtools.yang.model.spi.source.StringYangTextSource;
import org.opendaylight.yangtools.yang.parser.api.YangParser;
import org.opendaylight.yangtools.yang.parser.api.YangParserException;
import org.opendaylight.yangtools.yang.parser.ri.DefaultYangParserFactory;

public final class FileUtils {

    private FileUtils() {
        //Utility class
    }

    public static InputStream getResourceAsStream(final String resource) {
        return FileUtils.class.getClassLoader().getResourceAsStream(resource);
    }

    public static EffectiveModelContext buildSchemaFromYangsDir(final String path) {
        final YangParser parser = new DefaultYangParserFactory().createParser();
        try (Stream<Path> pathStream = Files.walk(Path.of(path))) {
            final List<File> filesInFolder = pathStream
                    .filter(Files::isRegularFile)
                    .map(Path::toFile)
                    .collect(Collectors.toList());

            for (File file : filesInFolder) {
                final String content = Files.readString(file.toPath());
                parser.addSource(new StringYangTextSource(
                    new SourceIdentifier(file.getName()), content));
            }
            return parser.buildEffectiveModel();
        } catch (IOException | YangParserException e) {
            throw new RuntimeException("Constructing schema from provided path failed!", e);
        }
    }
}
