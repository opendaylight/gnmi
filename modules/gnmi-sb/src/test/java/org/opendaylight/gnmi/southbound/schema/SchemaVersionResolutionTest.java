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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.base.Throwables;
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
     * Only 1.2.0 is stored and 2.0.0 is requested, a different major version is not compatible, the model is missing.
     */
    @Test
    void majorVersionTest() {
        storeOcModel("1.2.0", "2020-01-01");

        assertMissing(semVer("2.0.0"));
    }

    /**
     * Only 1.2.0 is stored and 1.5.0 is requested, an older version is not compatible, the model is missing.
     */
    @Test
    void olderVersionTest() {
        storeOcModel("1.2.0", "2020-01-01");

        assertMissing(semVer("1.5.0"));
    }

    /**
     * 1.2.0, 1.6.1, 1.8.0 and 2.0.0 are stored and 1.5.0 is requested, the closest compatible version 1.6.1 is used,
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
     * 1.9.0 and 1.10.0 are stored and no version is requested, the highest version 1.10.0 is used, compared by
     * version, not by text.
     */
    @Test
    void highestVersionTest() {
        storeOcModel("1.10.0", "2021-06-01");
        storeOcModel("1.9.0", "2021-01-01");

        assertEquals("1.10.0", usedVersion(new GnmiDeviceCapability(MODEL)));
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
     * Only 3.0.0 is stored and a revision from its history without a reference is requested, a model stored under
     * a semantic version is not compared by revision date, the model is missing.
     */
    @Test
    void unreferencedRevisionTest() {
        store.addYangModel(MODEL, "3.0.0", ocModel("3.0.0", "2023-01-01", "revision 2019-11-19;"));

        assertMissing(revision("2019-11-19"));
    }

    /**
     * Only 3.0.0 is stored and its latest revision 2023-01-01, which has no reference, is requested, the copy is
     * exactly the requested revision, so it is used.
     */
    @Test
    void latestUnreferencedRevisionTest() {
        store.addYangModel(MODEL, "3.0.0", """
            module %1$s {
              yang-version 1;
              namespace "urn:test:%1$s";
              prefix t;
              import openconfig-extensions { prefix oc-ext; }
              oc-ext:openconfig-version "3.0.0";
              revision 2023-01-01;
              revision 2019-11-19;
              leaf v { type string; description "3.0.0"; }
            }
            """.formatted(MODEL));

        assertEquals("3.0.0", usedVersion(revision("2023-01-01")));
    }

    /**
     * Models stored under revisions 2020-01-01, 2021-01-01 and 2022-01-01 and 2020-06-01 is requested, the closest
     * not older revision 2021-01-01 is used.
     */
    @Test
    void revisionStoredTest() {
        for (final var revision : List.of("2020-01-01", "2021-01-01", "2022-01-01")) {
            storeRevisionModel(revision);
        }

        assertEquals("2021-01-01", usedVersion(revision("2020-06-01")));
    }

    /**
     * Only a model stored under revision 2020-01-01 is stored and 2021-01-01 is requested, an older revision is not
     * compatible, the model is missing.
     */
    @Test
    void olderRevisionTest() {
        storeRevisionModel("2020-01-01");

        assertMissing(revision("2021-01-01"));
    }

    /**
     * Only a model stored without a version is stored and 1.0.0 is requested, there is no version to compare with,
     * so it is used as before.
     */
    @Test
    void noVersionTest() {
        store.addYangModel(MODEL, "", """
            module %1$s {
              yang-version 1;
              namespace "urn:test:%1$s";
              prefix t;
              leaf v { type string; description "none"; }
            }
            """.formatted(MODEL));

        assertEquals("none", usedVersion(semVer("1.0.0")));
    }

    /**
     * openconfig-extensions has no openconfig-version and is stored under its revision 2020-06-16, which references
     * 0.5.0, a request for 0.5.0 uses it, a request for 1.0.0 does not.
     */
    @Test
    void semVerToRevisionTest() {
        final var extensions = "openconfig-extensions";
        final var context = buildSchema(new GnmiDeviceCapability(extensions, "0.5.0", null));
        assertEquals(1, context.findModules(extensions).size());
        assertMissing(new GnmiDeviceCapability(extensions, "1.0.0", null));
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
     * Parent and submodule are stored at 1.0.0 and 2.0.0 and the parent 1.0.0 is requested, the included submodule
     * of the parent's version is used, not the highest stored one.
     */
    @Test
    void submoduleVersionTest() {
        for (final var version : List.of("1.0.0", "2.0.0")) {
            store.addYangModel(MODEL, version, ocModel(version, "2020-01-01", "include oc-foo-sub;"));
            storeSubmodule(version);
        }

        final var module = buildSchema(semVer("1.0.0")).findModules(MODEL).iterator().next();
        module.getChildNodes().forEach(node -> assertEquals("1.0.0", node.getDescription().orElseThrow()));
    }

    /**
     * The parent 1.2.0 is requested and its submodule is stored only at 1.1.0 and 2.0.0, the submodule of the
     * parent's major version 1.1.0 is used, not the highest stored 2.0.0.
     */
    @Test
    void submoduleMajorVersionTest() {
        store.addYangModel(MODEL, "1.2.0", ocModel("1.2.0", "2020-01-01", "include oc-foo-sub;"));
        storeSubmodule("1.1.0");
        storeSubmodule("2.0.0");

        final var module = buildSchema(semVer("1.2.0")).findModules(MODEL).iterator().next();
        final var submoduleLeaf = module.getChildNodes().stream()
            .filter(node -> node.getQName().getLocalName().equals("sub"))
            .findFirst().orElseThrow();
        assertEquals("1.1.0", submoduleLeaf.getDescription().orElseThrow());
    }

    /**
     * The parent 1.2.0 is requested and its submodule is stored only under revisions 2019-01-01 and 2021-01-01, the
     * submodule is not versioned like its parent, so the highest stored submodule 2021-01-01 is used.
     */
    @Test
    void submoduleHighestVersionTest() {
        store.addYangModel(MODEL, "1.2.0", ocModel("1.2.0", "2020-01-01", "include oc-foo-sub;"));
        for (final var revision : List.of("2019-01-01", "2021-01-01")) {
            store.addYangModel("oc-foo-sub", revision, """
                submodule oc-foo-sub {
                  yang-version 1;
                  belongs-to %1$s { prefix t; }
                  revision %2$s;
                  leaf sub { type string; description "%2$s"; }
                }
                """.formatted(MODEL, revision));
        }

        final var module = buildSchema(semVer("1.2.0")).findModules(MODEL).iterator().next();
        final var submoduleLeaf = module.getChildNodes().stream()
            .filter(node -> node.getQName().getLocalName().equals("sub"))
            .findFirst().orElseThrow();
        assertEquals("2021-01-01", submoduleLeaf.getDescription().orElseThrow());
    }

    /**
     * The test model imports dependency with revision-date 2020-01-01 and only dependency 2021-01-01 is stored,
     * a revision-date requires exactly that revision, so dependency is reported as missing instead of the parser
     * failing on the newer one.
     */
    @Test
    void importRevisionDateTest() {
        store.addYangModel(MODEL, "1.0.0", ocModel("1.0.0", "2020-01-01",
            "import dependency { prefix d; revision-date 2020-01-01; }"));
        store.addYangModel("dependency", "2021-01-01", """
            module dependency {
              yang-version 1;
              namespace "urn:test:dependency";
              prefix d;
              revision 2021-01-01;
            }
            """);

        final var exception = assertThrows(SchemaException.class,
            () -> newSchemaContextHolder().getSchemaContext(List.of(semVer("1.0.0"))));
        assertEquals(List.of(new GnmiDeviceCapability("dependency", null, "2020-01-01")), exception.getMissingModels());
    }

    /**
     * The test model imports dependency with revision-date 2021-01-01 and dependency is stored only under its semantic
     * version 1.0.0, whose latest revision is 2021-01-01, so it is used.
     */
    @Test
    void importRevisionDateStoredUnderSemVerTest() {
        store.addYangModel(MODEL, "1.0.0", ocModel("1.0.0", "2020-01-01",
            "import dependency { prefix d; revision-date 2021-01-01; }"));
        storeDependency("1.0.0", "2021-01-01");

        assertEquals("1.0.0", usedDependencyVersion(semVer("1.0.0")));
    }

    /**
     * The device reports a 1.0.0 and c 1.0.0. Module a imports b pinned to 1.0.0, c imports d and d imports b with
     * revision-date 2020-06-01, so the revision-date import is found one level deeper than the pin. b is stored at
     * 1.1.0 (revision 2020-01-01), the closest to the pin, and at 1.2.0 (revision 2020-06-01), which satisfies both,
     * so 1.2.0 is used, as if both requirements were found on the same level.
     */
    @Test
    void importRevisionDateBelowPinTest() {
        store.addYangModel("a", "1.0.0", ocModule("a", "1.0.0", "2020-01-01",
            "import b { prefix b; oc-ext:openconfig-version \"1.0.0\"; }"));
        store.addYangModel("c", "1.0.0", ocModule("c", "1.0.0", "2020-01-01", "import d { prefix d; }"));
        store.addYangModel("d", "1.0.0", ocModule("d", "1.0.0", "2020-01-01",
            "import b { prefix b; revision-date 2020-06-01; }"));
        store.addYangModel("b", "1.1.0", ocModule("b", "1.1.0", "2020-01-01", ""));
        store.addYangModel("b", "1.2.0", ocModule("b", "1.2.0", "2020-06-01", ""));

        final EffectiveModelContext context;
        try {
            context = newSchemaContextHolder().getSchemaContext(List.of(
                new GnmiDeviceCapability("a", "1.0.0", null), new GnmiDeviceCapability("c", "1.0.0", null)));
        } catch (SchemaException e) {
            throw new AssertionError("Schema creation failed, missing models: " + e.getMissingModels(), e);
        }
        final var moduleB = context.findModules("b").iterator().next();
        assertEquals("1.2.0", moduleB.getChildNodes().iterator().next().getDescription().orElseThrow());
    }

    /**
     * The test model imports dependency pinned to 1.0.0 and dependency is stored at 1.0.0 and 2.0.0, the pinned
     * version 1.0.0 is used, not the highest stored one.
     */
    @Test
    void importVersionTest() {
        store.addYangModel(MODEL, "1.0.0", ocModel("1.0.0", "2020-01-01",
            "import dependency { prefix d; oc-ext:openconfig-version \"1.0.0\"; }"));
        storeDependency("1.0.0");
        storeDependency("2.0.0");

        final var dependency = buildSchema(semVer("1.0.0")).findModules("dependency").iterator().next();
        assertEquals("1.0.0", dependency.getChildNodes().iterator().next().getDescription().orElseThrow());
    }

    /**
     * The test model imports dependency without a version, dependency is stored at 1.0.0 and 2.0.0, and 2.0.0 does
     * not build, so the lower stored version 1.0.0 is used.
     */
    @Test
    void lowerVersionTest() {
        store.addYangModel(MODEL, "1.0.0", ocModel("1.0.0", "2020-01-01", "import dependency { prefix d; }"));
        storeDependency("1.0.0");
        storeBrokenDependency("2.0.0");

        final var dependency = buildSchema(semVer("1.0.0")).findModules("dependency").iterator().next();
        assertEquals("1.0.0", dependency.getChildNodes().iterator().next().getDescription().orElseThrow());
    }

    /**
     * The test model imports dependency without a version, dependency is stored at 1.0.0 and 2.0.0, and neither
     * builds, so the schema fails with the error of the highest stored version.
     */
    @Test
    void lowerVersionFailsTest() {
        store.addYangModel(MODEL, "1.0.0", ocModel("1.0.0", "2020-01-01", "import dependency { prefix d; }"));
        storeBrokenDependency("1.0.0");
        storeBrokenDependency("2.0.0");

        final var exception = assertThrows(SchemaException.class,
            () -> newSchemaContextHolder().getSchemaContext(List.of(semVer("1.0.0"))));
        assertEquals(List.of(), exception.getMissingModels());
        final var reason = Throwables.getRootCause(exception).getMessage();
        assertTrue(reason.contains("missing-2.0.0"), reason);
    }

    /**
     * Dependency is stored at 1.0.0 to 1.10.0 and only 1.0.0 builds, it is the 10th lower version tried, the last one
     * within the limit, so it is used.
     */
    @Test
    void lowerVersionAttemptsLimitTest() {
        store.addYangModel(MODEL, "1.0.0", ocModel("1.0.0", "2020-01-01", "import dependency { prefix d; }"));
        storeDependency("1.0.0");
        for (int minor = 1; minor <= 10; minor++) {
            storeBrokenDependency("1." + minor + ".0");
        }

        assertEquals("1.0.0", usedDependencyVersion(semVer("1.0.0")));
    }

    /**
     * Dependency is stored at 1.0.0 to 1.11.0 and only 1.0.0 builds, it would be the 11th lower version tried, so it
     * is not tried and the schema fails with the error of the highest stored version.
     */
    @Test
    void lowerVersionAttemptsAboveLimitTest() {
        store.addYangModel(MODEL, "1.0.0", ocModel("1.0.0", "2020-01-01", "import dependency { prefix d; }"));
        storeDependency("1.0.0");
        for (int minor = 1; minor <= 11; minor++) {
            storeBrokenDependency("1." + minor + ".0");
        }

        final var exception = assertThrows(SchemaException.class,
            () -> newSchemaContextHolder().getSchemaContext(List.of(semVer("1.0.0"))));
        assertEquals(List.of(), exception.getMissingModels());
        final var reason = Throwables.getRootCause(exception).getMessage();
        assertTrue(reason.contains("missing-1.11.0"), reason);
    }

    /**
     * Stores the submodule oc-foo-sub of the test model, whose single leaf describes the version.
     */
    private void storeSubmodule(final String version) {
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

    /**
     * Stores a module without an openconfig-version under its revision, whose single leaf describes the revision.
     */
    private void storeRevisionModel(final String revision) {
        store.addYangModel(MODEL, revision, """
            module %1$s {
              yang-version 1;
              namespace "urn:test:%1$s";
              prefix t;
              revision %2$s;
              leaf v { type string; description "%2$s"; }
            }
            """.formatted(MODEL, revision));
    }

    /**
     * Stores the OpenConfig-style module dependency imported by the test model, whose single leaf describes the
     * version.
     */
    private void storeDependency(final String version) {
        store.addYangModel("dependency", version, """
            module dependency {
              yang-version 1;
              namespace "urn:test:dependency";
              prefix d;
              import openconfig-extensions { prefix oc-ext; }
              oc-ext:openconfig-version "%1$s";
              leaf d { type string; description "%1$s"; }
            }
            """.formatted(version));
    }

    /**
     * Stores the OpenConfig-style module dependency like {@link #storeDependency(String)}, with the given latest
     * revision.
     */
    private void storeDependency(final String version, final String revision) {
        store.addYangModel("dependency", version, """
            module dependency {
              yang-version 1;
              namespace "urn:test:dependency";
              prefix d;
              import openconfig-extensions { prefix oc-ext; }
              oc-ext:openconfig-version "%1$s";
              revision %2$s;
              leaf d { type string; description "%1$s"; }
            }
            """.formatted(version, revision));
    }

    /**
     * Stores dependency with a leaf of an undefined type named after the version, so that the schema cannot be built
     * with it.
     */
    private void storeBrokenDependency(final String version) {
        store.addYangModel("dependency", version, """
            module dependency {
              yang-version 1;
              namespace "urn:test:dependency";
              prefix d;
              leaf d { type d:missing-%1$s; description "%1$s"; }
            }
            """.formatted(version));
    }

    private void storeOcModel(final String version, final String revision) {
        store.addYangModel(MODEL, version, ocModel(version, revision, ""));
    }

    /**
     * Returns an OpenConfig-style module, whose single leaf describes the version, so the test can tell which stored
     * model ended up in the schema. The extra statements follow the import, so they can be includes or revisions.
     */
    private static String ocModel(final String version, final String revision, final String extraStatements) {
        return """
            module %1$s {
              yang-version 1;
              namespace "urn:test:%1$s";
              prefix t;
              import openconfig-extensions { prefix oc-ext; }
              %4$s
              oc-ext:openconfig-version "%2$s";
              revision %3$s { reference "%2$s"; }
              leaf v { type string; description "%2$s"; }
            }
            """.formatted(MODEL, version, revision, extraStatements);
    }

    /**
     * Returns an OpenConfig-style module with the given name, version and latest revision, whose single leaf describes
     * the version. The extra statements follow the import of openconfig-extensions.
     */
    private static String ocModule(final String name, final String version, final String revision,
            final String extraStatements) {
        return """
            module %1$s {
              yang-version 1;
              namespace "urn:test:%1$s";
              prefix %1$s;
              import openconfig-extensions { prefix oc-ext; }
              %4$s
              oc-ext:openconfig-version "%2$s";
              revision %3$s;
              leaf v { type string; description "%2$s"; }
            }
            """.formatted(name, version, revision, extraStatements);
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

    private String usedDependencyVersion(final GnmiDeviceCapability capability) {
        final var dependency = buildSchema(capability).findModules("dependency").iterator().next();
        return dependency.getChildNodes().iterator().next().getDescription().orElseThrow();
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
