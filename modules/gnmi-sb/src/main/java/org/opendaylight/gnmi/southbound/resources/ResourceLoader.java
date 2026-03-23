/*
 * Copyright (c) 2026 Smartoptics AS, and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.gnmi.southbound.resources;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ResourceLoader {

    private static final Logger LOG = LoggerFactory.getLogger(ResourceLoader.class);

    private ResourceLoader() {

    }

    public static InputStream getResourceAsStream(String path) throws IOException {
        InputStream resourceStream = FileUtils.class.getResourceAsStream(path);
        if (resourceStream != null) {
            return resourceStream;
        }
        return Files.newInputStream(Path.of(path));
    }

    public static String toResourcePath(String path) throws URISyntaxException {
        String ret =  Path.of(Objects.requireNonNull(
                FileUtils.class.getResource(path),
                "Missing resource: " + path
        ).toURI()).toString();

        return ret;
    }

    public static Path toResourcePath(Path input) throws URISyntaxException {
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
