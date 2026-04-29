/*
 * Copyright (c) 2026 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.gnmi.southbound.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opendaylight.gnmi.southbound.capabilities.GnmiDeviceCapability;
import org.opendaylight.gnmi.southbound.schema.impl.SchemaContextHolderImpl;
import org.opendaylight.gnmi.southbound.schema.impl.SchemaException;
import org.opendaylight.gnmi.southbound.schema.loader.api.YangLoadException;
import org.opendaylight.gnmi.southbound.schema.loader.impl.ByClassPathYangLoaderService;
import org.opendaylight.yang.svc.v1.http.openconfig.net.yang.openconfig.ext.rev200616.YangModuleInfoImpl;
import org.opendaylight.yangtools.yang.model.api.EffectiveModelContext;
import org.opendaylight.yangtools.yang.parser.ri.DefaultYangParserFactory;
import org.opendaylight.yangtools.yang.source.ir.DefaultYangTextToIRSourceTransformer;

/**
 * Tests which stored version of a model is used when the device requests a version that is not stored exactly.
 */
class SchemaVersionResolutionTest {
    private static final String MODEL = "oc-foo";

    private TestYangDataStoreService store;

    @BeforeEach
    void setup() throws YangLoadException {
        store = new TestYangDataStoreService();
        new ByClassPathYangLoaderService(Set.of(YangModuleInfoImpl.INSTANCE), new DefaultYangParserFactory(),
            new DefaultYangTextToIRSourceTransformer()).load(store);
    }

    /**
     * Only 1.2.0 is stored and 2.0.0 is requested: a different major version is not compatible, the model is missing.
     */
    @Test
    void majorVersionTest() {
        storeOcModel("1.2.0", "2020-01-01");

        assertMissing(semVer("2.0.0"));
    }

    /**
     * Only 1.2.0 is stored and 1.5.0 is requested: an older version is not compatible, the model is missing.
     */
    @Test
    void olderVersionTest() {
        storeOcModel("1.2.0", "2020-01-01");

        assertMissing(semVer("1.5.0"));
    }

    /**
     * 1.2.0, 1.6.1, 1.8.0 and 2.0.0 are stored and 1.5.0 is requested: the closest compatible version 1.6.1 is used,
     * not the highest compatible 1.8.0.
     */
    @Test
    void closestVersionTest() {
        storeOcModel("1.2.0", "2020-01-01");
        storeOcModel("1.6.1", "2021-01-01");
        storeOcModel("1.8.0", "2021-06-01");
        storeOcModel("2.0.0", "2022-01-01");

        assertEquals("1.6.1", usedVersion(semVer("1.5.0")));
    }

    /**
     * A requested revision is matched to a model stored under its semantic version.
     */
    @Test
    void revisionTest() {
        storeOcModel("1.2.0", "2020-01-01");
        storeOcModel("1.6.1", "2021-01-01");
        storeOcModel("2.0.0", "2022-01-01");

        assertEquals("1.6.1", usedVersion(revision("2021-01-01")));
    }

    /**
     * A requested revision is translated to the semantic version referenced in the revision history, so 3.0.0 is not
     * used for a 2.4.3 revision, while 2.5.0 is.
     */
    @Test
    void revisionMajorVersionTest() {
        final var history = "revision 2019-11-19 { reference \"2.4.3\"; }";
        store.addYangModel(MODEL, "3.0.0", ocModel("3.0.0", "2023-01-01", history));
        assertMissing(revision("2019-11-19"));

        store.addYangModel(MODEL, "2.5.0", ocModel("2.5.0", "2021-04-06", history));
        assertEquals("2.5.0", usedVersion(revision("2019-11-19")));
    }

    /**
     * A model stored under a revision date is never compared with a requested semantic version, 2.5.0 is used with
     * or without a requested version.
     */
    @Test
    void mixedSchemesTest() {
        store.addYangModel(MODEL, "2021-01-01", ocModel("0.0.0", "2021-01-01", ""));
        storeOcModel("2.5.0", "2022-01-01");

        assertEquals("2.5.0", usedVersion(semVer("2.0.0")));
        assertEquals("2.5.0", usedVersion(new GnmiDeviceCapability(MODEL)));
    }

    /**
     * Same as {@link #mixedSchemesTest()} with the models stored in reverse order, the result must not depend on it.
     */
    @Test
    void mixedSchemesReverseOrderTest() {
        storeOcModel("2.5.0", "2022-01-01");
        store.addYangModel(MODEL, "2021-01-01", ocModel("0.0.0", "2021-01-01", ""));

        assertEquals("2.5.0", usedVersion(semVer("2.0.0")));
        assertEquals("2.5.0", usedVersion(new GnmiDeviceCapability(MODEL)));
    }

    /**
     * Parent and submodule are stored at 1.0.0 and 2.0.0 and the parent 1.0.0 is requested: the included submodule
     * of the parent's version is used, not the highest stored one.
     */
    @Test
    void submoduleVersionTest() {
        for (final var version : List.of("1.0.0", "2.0.0")) {
            store.addYangModel(MODEL, version, ocModel(version, "2020-01-01", "include oc-foo-sub;"));
            store.addYangModel("oc-foo-sub", version, """
                submodule oc-foo-sub {
                  yang-version 1;
                  belongs-to %1$s { prefix t; }
                  import openconfig-extensions { prefix oc-ext; }
                  oc-ext:openconfig-version "%2$s";
                  leaf sub { type string; description "%2$s"; }
                }
                """.formatted(MODEL, version));
        }

        final var module = buildSchema(semVer("1.0.0")).findModules(MODEL).iterator().next();
        module.getChildNodes().forEach(node -> assertEquals("1.0.0", node.getDescription().orElseThrow()));
    }

    private void storeOcModel(final String version, final String revision) {
        store.addYangModel(MODEL, version, ocModel(version, revision, ""));
    }

    /**
     * Returns an OpenConfig-style module, whose single leaf describes the version, so the test can tell which stored
     * model ended up in the schema.
     */
    private static String ocModel(final String version, final String revision, final String extraStatements) {
        return """
            module %1$s {
              yang-version 1;
              namespace "urn:test:%1$s";
              prefix t;
              import openconfig-extensions { prefix oc-ext; }
              oc-ext:openconfig-version "%2$s";
              revision %3$s { reference "%2$s"; }
              %4$s
              leaf v { type string; description "%2$s"; }
            }
            """.formatted(MODEL, version, revision, extraStatements);
    }

    private static GnmiDeviceCapability semVer(final String version) {
        return new GnmiDeviceCapability(MODEL, version, null);
    }

    private static GnmiDeviceCapability revision(final String revision) {
        return new GnmiDeviceCapability(MODEL, null, revision);
    }

    private String usedVersion(final GnmiDeviceCapability capability) {
        final var context = buildSchema(capability);
        final var module = context.findModules(MODEL).iterator().next();
        return module.getChildNodes().iterator().next().getDescription().orElseThrow();
    }

    private EffectiveModelContext buildSchema(final GnmiDeviceCapability capability) {
        try {
            return newSchemaContextHolder().getSchemaContext(List.of(capability));
        } catch (SchemaException e) {
            throw new AssertionError("Schema creation failed, missing models: " + e.getMissingModels(), e);
        }
    }

    private void assertMissing(final GnmiDeviceCapability capability) {
        final var exception = assertThrows(SchemaException.class,
            () -> newSchemaContextHolder().getSchemaContext(List.of(capability)));
        assertEquals(List.of(capability), exception.getMissingModels());
    }

    private SchemaContextHolderImpl newSchemaContextHolder() {
        // New instance per lookup, so the schema context cache does not leak between lookups
        return new SchemaContextHolderImpl(store, new DefaultYangParserFactory(),
            new DefaultYangTextToIRSourceTransformer());
    }
}
