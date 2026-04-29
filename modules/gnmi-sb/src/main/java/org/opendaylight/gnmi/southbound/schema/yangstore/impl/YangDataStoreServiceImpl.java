/*
 * Copyright (c) 2021 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.gnmi.southbound.schema.yangstore.impl;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.List;
import java.util.Optional;
import org.opendaylight.gnmi.commons.util.YangModelSanitizer;
import org.opendaylight.gnmi.southbound.schema.yangstore.service.YangDataStoreService;
import org.opendaylight.mdsal.binding.api.DataBroker;
import org.opendaylight.mdsal.binding.api.ReadTransaction;
import org.opendaylight.mdsal.binding.api.WriteTransaction;
import org.opendaylight.mdsal.common.api.CommitInfo;
import org.opendaylight.mdsal.common.api.LogicalDatastoreType;
import org.opendaylight.yang.gen.v1.urn.opendaylight.gnmi.yang.storage.rev210331.GnmiYangModels;
import org.opendaylight.yang.gen.v1.urn.opendaylight.gnmi.yang.storage.rev210331.ModuleVersionType;
import org.opendaylight.yang.gen.v1.urn.opendaylight.gnmi.yang.storage.rev210331.gnmi.yang.models.GnmiYangModel;
import org.opendaylight.yang.gen.v1.urn.opendaylight.gnmi.yang.storage.rev210331.gnmi.yang.models.GnmiYangModelBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.gnmi.yang.storage.rev210331.gnmi.yang.models.GnmiYangModelKey;
import org.opendaylight.yangtools.binding.DataObjectIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class YangDataStoreServiceImpl implements YangDataStoreService {
    private static final Logger LOG = LoggerFactory.getLogger(YangDataStoreServiceImpl.class);

    private final DataBroker dataBroker;

    public YangDataStoreServiceImpl(final DataBroker dataBroker) {
        this.dataBroker = dataBroker;
    }

    @Override
    public ListenableFuture<? extends CommitInfo> addYangModel(final String modelName, final String modelVersion,
                                                               final String modelBody) {
        LOG.debug("Adding yang model {} with version {} to operational datastore", modelName, modelVersion);
        final GnmiYangModelKey gnmiYangModelKey = new GnmiYangModelKey(modelName, new ModuleVersionType(modelVersion));
        final var identifier = DataObjectIdentifier.builder(GnmiYangModels.class)
                .child(GnmiYangModel.class, gnmiYangModelKey)
                .build();
        final String sanitizedModelBody = YangModelSanitizer.removeRegexpPosix(modelBody);
        final GnmiYangModelBuilder gnmiYangModelBuilder = new GnmiYangModelBuilder()
                .setName(modelName)
                .setBody(sanitizedModelBody)
                .withKey(gnmiYangModelKey);
        final WriteTransaction writeTX = dataBroker.newWriteOnlyTransaction();
        writeTX.merge(LogicalDatastoreType.OPERATIONAL, identifier, gnmiYangModelBuilder.build());
        return writeTX.commit();
    }

    @Override
    public ListenableFuture<Optional<GnmiYangModel>> readYangModel(final String modelName, final String modelVersion) {
        final var identifier = DataObjectIdentifier.builder(GnmiYangModels.class)
                .child(GnmiYangModel.class, new GnmiYangModelKey(modelName, new ModuleVersionType(modelVersion)))
                .build();
        try (ReadTransaction readOnlyTransaction = this.dataBroker.newReadOnlyTransaction()) {
            return readOnlyTransaction.read(LogicalDatastoreType.OPERATIONAL, identifier);
        }
    }

    @Override
    public ListenableFuture<List<GnmiYangModel>> readYangModel(final String modelName) {
        // When only the model name is known, return every stored version of that model
        final var identifier = DataObjectIdentifier.builder(GnmiYangModels.class).build();

        try (ReadTransaction readOnlyTransaction = this.dataBroker.newReadOnlyTransaction()) {
            final ListenableFuture<Optional<GnmiYangModels>> yangModelOptionalFuture = readOnlyTransaction.read(
                    LogicalDatastoreType.OPERATIONAL, identifier);
            return Futures.transform(yangModelOptionalFuture, yangModelOptional -> yangModelOptional
                    // Keep only models with requested name, no container means nothing is stored
                    .map(yangModels -> yangModels.nonnullGnmiYangModel().values().stream()
                            .filter(m -> m.getName().equals(modelName))
                            .toList())
                    // Cheap filter, run it directly: callers block on the result from the gNMI pool, so queueing
                    // it on that same pool can starve it
                    .orElse(List.of()), MoreExecutors.directExecutor());
        }
    }

}
