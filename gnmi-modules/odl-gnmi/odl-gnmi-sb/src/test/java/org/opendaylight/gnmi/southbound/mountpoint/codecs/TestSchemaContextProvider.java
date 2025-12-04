/*
 * Copyright (c) 2021 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.gnmi.southbound.mountpoint.codecs;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.opendaylight.gnmi.southbound.capabilities.GnmiDeviceCapability;
import org.opendaylight.gnmi.southbound.schema.SchemaContextHolder;
import org.opendaylight.gnmi.southbound.schema.TestYangDataStoreService;
import org.opendaylight.gnmi.southbound.schema.impl.SchemaContextHolderImpl;
import org.opendaylight.gnmi.southbound.schema.impl.SchemaException;
import org.opendaylight.gnmi.southbound.schema.loader.api.YangLoadException;
import org.opendaylight.gnmi.southbound.schema.loader.impl.ByClassPathYangLoaderService;
import org.opendaylight.gnmi.southbound.schema.loader.impl.ByPathYangLoaderService;
import org.opendaylight.gnmi.southbound.schema.provider.SchemaContextProvider;
import org.opendaylight.yangtools.binding.meta.YangModuleInfo;
import org.opendaylight.yangtools.yang.model.api.EffectiveModelContext;

public class TestSchemaContextProvider implements SchemaContextProvider {

    private final EffectiveModelContext schemaContext;

    public TestSchemaContextProvider(final EffectiveModelContext schemaContext) {
        this.schemaContext = schemaContext;
    }

    @Override
    public EffectiveModelContext getSchemaContext() {
        return schemaContext;

    }

    public static TestSchemaContextProvider createInstance(final Path path, final Set<YangModuleInfo> moduleInfoSet)
            throws YangLoadException, SchemaException {
        final TestYangDataStoreService dataStoreService = new TestYangDataStoreService();
        final List<GnmiDeviceCapability> capabilities = new ByPathYangLoaderService(path).load(dataStoreService);
        capabilities.addAll(new ByClassPathYangLoaderService(moduleInfoSet).load(dataStoreService));

        final SchemaContextHolder schemaContextHolder = new SchemaContextHolderImpl(dataStoreService, null);
        return new TestSchemaContextProvider(schemaContextHolder.getSchemaContext(capabilities));
    }

}
