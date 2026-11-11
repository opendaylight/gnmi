/*
 * Copyright (c) 2026 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.gnmi.southbound.schema;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opendaylight.gnmi.southbound.capabilities.GnmiDeviceCapability;
import org.opendaylight.gnmi.southbound.schema.impl.SchemaContextHolderImpl;
import org.opendaylight.gnmi.southbound.schema.impl.SchemaException;
import org.opendaylight.gnmi.southbound.schema.loader.api.YangLoadException;
import org.opendaylight.gnmi.southbound.schema.loader.impl.ByClassPathYangLoaderService;
import org.opendaylight.gnmi.southbound.schema.loader.impl.ByPathYangLoaderService;
import org.opendaylight.yang.svc.v1.http.openconfig.net.yang.openconfig.ext.rev200616.YangModuleInfoImpl;
import org.opendaylight.yangtools.yang.model.api.EffectiveModelContext;
import org.opendaylight.yangtools.yang.model.api.Module;
import org.opendaylight.yangtools.yang.parser.ri.DefaultYangParserFactory;
import org.opendaylight.yangtools.yang.source.ir.DefaultYangTextToIRSourceTransformer;

/**
 * Version resolution of the real OpenConfig model openconfig-bgp-types, stored in two major versions.
 *
 * <p>Stored models (src/test/resources/openconfig-bgp-types, copied from github.com/openconfig/public):
 * <ul>
 *   <li>v3.0.0: openconfig-bgp-types 5.5.0 with its submodule openconfig-bgp-errors 5.5.0 (revision 2023-09-06),
 *       openconfig-types 0.6.0, openconfig-inet-types 0.6.0</li>
 *   <li>v5.11.0: openconfig-bgp-types 6.2.0 with its submodule openconfig-bgp-errors 6.2.0 (revision 2026-03-24).
 *       6.x uses the type oc-types:posix-eregexp, which exists only since openconfig-types 1.0.0.</li>
 * </ul>
 *
 * <p>Each test asserts the behavior expected from the change "Yangloader backwards compatibility": a model of
 * another major version is never used. The last two tests fail on that change.
 */
class OpenConfigBgpTypesVersionTest {
    private static final String BGP_TYPES = "openconfig-bgp-types";
    private static final String BGP_ERRORS = "openconfig-bgp-errors";
    private static final String REVISION_5_5_0 = "2023-09-06";
    // The device runs release v3.0.0, so it reports these dependencies with their v3.0.0 versions
    private static final GnmiDeviceCapability OC_TYPES = new GnmiDeviceCapability("openconfig-types", "0.6.0", null);
    private static final GnmiDeviceCapability OC_INET_TYPES =
        new GnmiDeviceCapability("openconfig-inet-types", "0.6.0", null);

    private TestYangDataStoreService store;

    @BeforeEach
    void setup() throws YangLoadException, URISyntaxException {
        store = new TestYangDataStoreService();
        final var parserFactory = new DefaultYangParserFactory();
        final var transformer = new DefaultYangTextToIRSourceTransformer();
        new ByClassPathYangLoaderService(Set.of(YangModuleInfoImpl.INSTANCE), parserFactory, transformer).load(store);
        new ByPathYangLoaderService(Path.of(getClass().getResource("/openconfig-bgp-types").toURI()), parserFactory,
            transformer).load(store);
    }

    /**
     * WRONG: the device reports openconfig-bgp-types with an empty version (ModelData.version is optional in gNMI).
     * The highest stored version 6.2.0 is used regardless of its major version. It needs openconfig-types 1.0.0, but
     * the device reported 0.6.0, so the schema fails with "Type posix-eregexp was not found", although the stored
     * 5.5.0 fits the reported models.
     */
    @Test
    void noVersionReportedTest() {
        final var context = assertDoesNotThrow(() -> buildSchema(new GnmiDeviceCapability(BGP_TYPES)),
            "Highest version 6.2.0 was used, it does not fit openconfig-types 0.6.0 reported by the device");

        assertEquals(REVISION_5_5_0, revisionOf(bgpTypes(context)));
    }

    /**
     * WRONG: 5.5.0 is reported, but only the 6.2.0 copy of its submodule is stored. The submodule of another major
     * version is used silently instead of being reported as missing.
     */
    @Test
    void submoduleOfOtherMajorVersionTest() {
        store.deleteYangModel(BGP_ERRORS, "5.5.0");

        final var exception = assertThrows(SchemaException.class, () -> buildSchema(bgpTypes("5.5.0")),
            "openconfig-bgp-types 5.5.0 was built with openconfig-bgp-errors 6.2.0");
        assertEquals(List.of(BGP_ERRORS),
            exception.getMissingModels().stream().map(GnmiDeviceCapability::getName).toList());
    }

    private EffectiveModelContext buildSchema(final GnmiDeviceCapability bgpTypes) throws SchemaException {
        // New instance per schema, so the schema context cache does not leak between tests
        return new SchemaContextHolderImpl(store, new DefaultYangParserFactory(),
            new DefaultYangTextToIRSourceTransformer()).getSchemaContext(List.of(bgpTypes, OC_TYPES, OC_INET_TYPES));
        //place a debugger on the line of code above, run submoduleOfOtherMajorVersionTest() and run the following in debugger:
        // new SchemaContextHolderImpl(store, new DefaultYangParserFactory(), new DefaultYangTextToIRSourceTransformer()).getSchemaContext(List.of(bgpTypes, OC_TYPES, OC_INET_TYPES)).findModules("openconfig-bgp-types").iterator().next().getSubmodules().stream().map(s -> s.getRevision().orElseThrow()).toList().getFirst()
        //as you can see the revision was used to build the model ().
    }

    private static GnmiDeviceCapability bgpTypes(final String version) {
        return new GnmiDeviceCapability(BGP_TYPES, version, null);
    }

    private static Module bgpTypes(final EffectiveModelContext context) {
        return context.findModules(BGP_TYPES).iterator().next();
    }

    private static String revisionOf(final Module module) {
        return module.getRevision().orElseThrow().toString();
    }

    private static String submoduleRevisionOf(final Module module) {
        final var submodule = module.getSubmodules().iterator().next();
        assertEquals(BGP_ERRORS, submodule.getName());
        return submodule.getRevision().orElseThrow().toString();
    }
}
