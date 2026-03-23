/*
 * Copyright (c) 2021 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.gnmi.simulatordevice.utils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FileUtils {
    private static final Logger LOG = LoggerFactory.getLogger(FileUtils.class);

    private FileUtils() {
        //Utility class
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
