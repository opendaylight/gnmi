/*
 * Copyright (c) 2021 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.gnmi.southbound.schema;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.eclipse.jdt.annotation.Nullable;
import org.opendaylight.gnmi.commons.util.YangModelSanitizer;
import org.opendaylight.gnmi.southbound.schema.yangstore.service.YangDataStoreService;
import org.opendaylight.mdsal.common.api.CommitInfo;
import org.opendaylight.yang.gen.v1.urn.opendaylight.gnmi.yang.storage.rev210331.ModuleVersionType;
import org.opendaylight.yang.gen.v1.urn.opendaylight.gnmi.yang.storage.rev210331.gnmi.yang.models.GnmiYangModel;
import org.opendaylight.yang.gen.v1.urn.opendaylight.gnmi.yang.storage.rev210331.gnmi.yang.models.GnmiYangModelBuilder;

public class TestYangDataStoreService implements YangDataStoreService {

    private Map<ImmutablePair<String, String>, String> yangs;

    public TestYangDataStoreService() {
        yangs = new LinkedHashMap<>();
    }

    @Override
    public ListenableFuture<CommitInfo> addYangModel(final String modelName, final String modelVersion,
                                                               final String modelBody) {
        final String sanitizedModelBody = YangModelSanitizer.removeRegexpPosix(modelBody);
        yangs.put(ImmutablePair.of(modelName, modelVersion), sanitizedModelBody);
        return Futures.immediateFuture(CommitInfo.empty());
    }


    /**
     * Deletes the given version of a yang model, or every stored version of it when {@code modelVersion} is
     * {@code null}.
     *
     * @return true if anything was deleted
     */
    public boolean deleteYangModel(final String modelName, @Nullable final String modelVersion) {
        if (modelVersion != null) {
            return yangs.remove(ImmutablePair.of(modelName, modelVersion)) != null;
        }
        return yangs.keySet().removeIf(key -> key.left.equals(modelName));
    }

    @Override
    public ListenableFuture<Optional<GnmiYangModel>> readYangModel(final String modelName, final String modelVersion) {
        for (Map.Entry<ImmutablePair<String, String>, String> entry : yangs.entrySet()) {
            if (entry.getKey().left.equals(modelName) && entry.getKey().right.equals(modelVersion)) {
                return Futures.immediateFuture(Optional.of(new GnmiYangModelBuilder()
                        .setVersion(new ModuleVersionType(modelVersion))
                        .setName(modelName)
                        .setBody(entry.getValue()).build()));
            }
        }
        return Futures.immediateFuture(Optional.empty());
    }

    @Override
    public ListenableFuture<List<GnmiYangModel>> readYangModel(final String modelName) {
        // Return every stored version of the model, in insertion order so tests can control the order
        final var modelsWithRequestedName = yangs.entrySet().stream()
            .filter(e -> e.getKey().left.equals(modelName))
            .map(e -> new GnmiYangModelBuilder()
                .setVersion(new ModuleVersionType(e.getKey().right))
                .setName(modelName)
                .setBody(e.getValue()).build())
            .toList();
        return Futures.immediateFuture(modelsWithRequestedName);
    }
}
