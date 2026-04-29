/*
 * Copyright (c) 2026 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.gnmi.southbound.schema.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.opendaylight.gnmi.southbound.schema.impl.SchemaContextHolderImpl.compareVersions;
import static org.opendaylight.gnmi.southbound.schema.impl.SchemaContextHolderImpl.isBackwardsCompatible;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class VersionCompatibilityTest {

    /**
     * Checks whether a model with the found version can be used in place of the requested version. An empty value
     * stands for {@code null}, {@code ''} for an empty string.
     */
    @ParameterizedTest(name = "{0} for {1}: {2}")
    @CsvSource(textBlock = """
        # semantic version: same major version, not lower
        1.5.0,      1.5.0,      true
        1.5.1,      1.5.0,      true
        1.6.0,      1.5.0,      true
        1.5,        1.5.0,      true
        1.4.9,      1.5.0,      false
        # semantic version: a different major version is not compatible, major version 0 counts as one
        2.0.0,      1.5.0,      false
        1.9.0,      2.0.0,      false
        0.9.0,      0.5.0,      true
        # revision date: not older
        2021-01-01, 2021-01-01, true
        2022-01-01, 2021-01-01, true
        2020-12-31, 2021-01-01, false
        # a revision date and a semantic version are never compatible
        2.5.0,      2021-01-01, false
        2021-01-01, 2.0.0,      false
        # missing or unparseable versions are never compatible
                  , 1.0.0,      false
        1.0.0,      ,           false
        '',         1.0.0,      false
        1.0.0-beta, 1.0.0,      false
        +1.0.0,     1.0.0,      false
        1.0.0.1,    1.0.0,      false
        2021-13-01, 2021-01-01, false
        """)
    void compatibleTest(final String foundVersion, final String requestedVersion, final boolean compatible) {
        assertEquals(compatible, isBackwardsCompatible(foundVersion, requestedVersion));
    }

    /**
     * Versions of the same scheme are compared by their value, a revision date and a semantic version are not
     * comparable. Only the sign of the result is checked.
     */
    @ParameterizedTest(name = "{0} vs {1}: {2}")
    @CsvSource(textBlock = """
        1.10.0,     1.9.0,      1
        1.2,        1.2.1,      -1
        1.2,        1.2.0,      0
        2021-01-02, 2021-01-01, 1
        2.5.0,      2021-01-01, 0
        """)
    void compareTest(final String version, final String otherVersion, final int sign) {
        assertEquals(sign, Integer.signum(compareVersions(version, otherVersion)));
    }
}
