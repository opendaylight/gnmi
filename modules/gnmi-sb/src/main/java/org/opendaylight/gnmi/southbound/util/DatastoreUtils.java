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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Datastore helpers shared by the gNMI connection lifecycle writers.
 */
public final class DatastoreUtils {

    private static final Logger LOG = LoggerFactory.getLogger(DatastoreUtils.class);

    private DatastoreUtils() {
        // No-op
    }

    /**
     * Returns whether the gNMI node has been confirmed absent from the configuration datastore. Used by the
     * best-effort operational writers to avoid resurrecting a node that has already been deleted. A read failure
     * is "could not tell", not proof of absence: it is logged and reported as {@code false} so the caller keeps
     * writing rather than dropping a live connection. Only a successful read that finds no node returns
     * {@code true}.
     *
     * @param dataBroker broker used to read the configuration datastore
     * @param nodeId node to look up
     * @return {@code true} only if a successful read confirmed the node is gone, {@code false} if it is present
     *     or the read failed
     */
    public static boolean nodeConfirmedAbsentInConfig(final DataBroker dataBroker, final NodeId nodeId) {
        try (var tx = dataBroker.newReadOnlyTransaction()) {
            return requireNonNull(tx.read(LogicalDatastoreType.CONFIGURATION, IdentifierUtils.gnmiNodeIID(nodeId))
                    .get(TimeoutUtils.DATASTORE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)).isEmpty();
        } catch (ExecutionException | TimeoutException e) {
            LOG.warn("Unable to read configuration of node {}, assuming it is present", nodeId.getValue(), e);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Interrupted while reading configuration of node {}, assuming it is present",
                    nodeId.getValue(), e);
            return false;
        }
    }
}
