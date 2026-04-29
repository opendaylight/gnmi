/*
 * Copyright (c) 2026 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.gnmi.southbound.schema.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.opendaylight.gnmi.southbound.schema.impl.SchemaContextHolderImpl.compareVersions;
import static org.opendaylight.gnmi.southbound.schema.impl.SchemaContextHolderImpl.isBackwardsCompatible;

import org.junit.jupiter.api.Test;

class VersionCompatibilityTest {

    /**
     * A semantic version with the same major version, not lower than requested, is compatible.
     */
    @Test
    void semVerTest() {
        assertTrue(isBackwardsCompatible("1.5.0", "1.5.0"));
        assertTrue(isBackwardsCompatible("1.5.1", "1.5.0"));
        assertTrue(isBackwardsCompatible("1.6.0", "1.5.0"));
        assertTrue(isBackwardsCompatible("1.5", "1.5.0"));
    }

    /**
     * A lower semantic version is not compatible.
     */
    @Test
    void semVerLowerTest() {
        assertFalse(isBackwardsCompatible("1.4.9", "1.5.0"));
    }

    /**
     * A different major version is not compatible.
     */
    @Test
    void semVerMajorTest() {
        assertFalse(isBackwardsCompatible("2.0.0", "1.5.0"));
        assertFalse(isBackwardsCompatible("1.9.0", "2.0.0"));
    }

    /**
     * Major version 0 is treated as a single major version.
     */
    @Test
    void semVerMajorZeroTest() {
        assertTrue(isBackwardsCompatible("0.9.0", "0.5.0"));
    }

    /**
     * A revision date not older than requested is compatible.
     */
    @Test
    void revisionTest() {
        assertTrue(isBackwardsCompatible("2021-01-01", "2021-01-01"));
        assertTrue(isBackwardsCompatible("2022-01-01", "2021-01-01"));
        assertFalse(isBackwardsCompatible("2020-12-31", "2021-01-01"));
    }

    /**
     * A revision date and a semantic version are never compatible.
     */
    @Test
    void differentSchemesTest() {
        assertFalse(isBackwardsCompatible("2.5.0", "2021-01-01"));
        assertFalse(isBackwardsCompatible("2021-01-01", "2.0.0"));
    }

    /**
     * Missing or unparseable versions are never compatible.
     */
    @Test
    void unknownVersionsTest() {
        assertFalse(isBackwardsCompatible(null, "1.0.0"));
        assertFalse(isBackwardsCompatible("1.0.0", null));
        assertFalse(isBackwardsCompatible("", "1.0.0"));
        assertFalse(isBackwardsCompatible("1.0.0-beta", "1.0.0"));
        assertFalse(isBackwardsCompatible("+1.0.0", "1.0.0"));
        assertFalse(isBackwardsCompatible("1.0.0.1", "1.0.0"));
        assertFalse(isBackwardsCompatible("2021-13-01", "2021-01-01"));
    }

    /**
     * Versions of the same scheme are compared by their value.
     */
    @Test
    void compareTest() {
        assertTrue(compareVersions("1.10.0", "1.9.0") > 0);
        assertTrue(compareVersions("1.2", "1.2.1") < 0);
        assertEquals(0, compareVersions("1.2", "1.2.0"));
        assertTrue(compareVersions("2021-01-02", "2021-01-01") > 0);
    }

    /**
     * A revision date and a semantic version are not comparable.
     */
    @Test
    void compareDifferentSchemesTest() {
        assertEquals(0, compareVersions("2.5.0", "2021-01-01"));
    }
}
