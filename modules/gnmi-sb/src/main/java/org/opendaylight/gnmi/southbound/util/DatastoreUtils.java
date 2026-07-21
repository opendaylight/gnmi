/*
 * Copyright (c) 2026 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.gnmi.southbound.util;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.opendaylight.gnmi.southbound.identifier.IdentifierUtils;
import org.opendaylight.gnmi.southbound.timeout.TimeoutUtils;
import org.opendaylight.mdsal.binding.api.DataBroker;
import org.opendaylight.mdsal.common.api.LogicalDatastoreType;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.NodeId;

/**
 * Datastore helpers shared by the gNMI connection lifecycle writers.
 */
public final class DatastoreUtils {

    private DatastoreUtils() {
        // Utility class
    }

    /**
     * Checks whether the gNMI node is still present in the configuration datastore. Used by the best-effort
     * operational writers to avoid resurrecting a node that has already been deleted. A read failure is not
     * treated as absence: it is propagated so the caller can tell "node is gone" apart from "could not tell".
     *
     * @param dataBroker broker used to read the configuration datastore
     * @param nodeId node to look up
     * @return true if the node is still configured, false if it has been deleted
     * @throws ExecutionException if the configuration read fails
     * @throws TimeoutException if the configuration read does not complete in time
     * @throws InterruptedException if interrupted while reading
     */
    public static boolean nodePresentInConfig(final DataBroker dataBroker, final NodeId nodeId)
            throws InterruptedException, ExecutionException, TimeoutException {
        try (var tx = dataBroker.newReadOnlyTransaction()) {
            return requireNonNull(tx.read(LogicalDatastoreType.CONFIGURATION, IdentifierUtils.gnmiNodeID(nodeId))
                    .get(TimeoutUtils.DATASTORE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)).isPresent();
        }
    }
}
