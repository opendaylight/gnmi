/*
 * Copyright (c) 2026 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.gnmi.test.utils;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;

/**
 * Resolves test resources to file system paths, for code under test that reads files or walks directories.
 */
public final class TestResources {

    private TestResources() {
        // Utility class
    }

    /**
     * Returns the file system path of a test resource.
     *
     * @param resource resource name relative to the classpath root, e.g. {@code /initData}
     * @return file system path of the resource
     * @throws IllegalArgumentException if the resource does not exist or is not a file system resource
     */
    public static Path resourcePath(final String resource) {
        final URL url = TestResources.class.getResource(resource);
        if (url == null) {
            throw new IllegalArgumentException("Test resource not found: " + resource);
        }
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid test resource URL: " + url, e);
        }
    }
}
