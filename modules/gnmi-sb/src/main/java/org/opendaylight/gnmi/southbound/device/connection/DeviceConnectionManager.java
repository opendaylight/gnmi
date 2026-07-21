/*
 * Copyright (c) 2021 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.gnmi.southbound.device.connection;

import com.google.common.collect.Sets;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import gnmi.Gnmi;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.opendaylight.gnmi.southbound.capabilities.GnmiDeviceCapability;
import org.opendaylight.gnmi.southbound.capabilities.MissingEncodingException;
import org.opendaylight.gnmi.southbound.device.session.listener.GnmiConnectionStatusException;
import org.opendaylight.gnmi.southbound.device.session.security.SessionSecurityException;
import org.opendaylight.gnmi.southbound.identifier.IdentifierUtils;
import org.opendaylight.gnmi.southbound.mountpoint.GnmiMountPointRegistrator;
import org.opendaylight.gnmi.southbound.mountpoint.broker.GnmiDataBrokerFactory;
import org.opendaylight.gnmi.southbound.requests.utils.GnmiRequestUtils;
import org.opendaylight.gnmi.southbound.schema.SchemaContextHolder;
import org.opendaylight.gnmi.southbound.schema.impl.SchemaException;
import org.opendaylight.gnmi.southbound.timeout.TimeoutUtils;
import org.opendaylight.gnmi.southbound.util.DatastoreUtils;
import org.opendaylight.mdsal.binding.api.DataBroker;
import org.opendaylight.mdsal.common.api.CommitInfo;
import org.opendaylight.mdsal.common.api.LogicalDatastoreType;
import org.opendaylight.yang.gen.v1.urn.opendaylight.gnmi.topology.rev210316.GnmiNodeBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.gnmi.topology.rev210316.gnmi.node.state.NodeStateBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.gnmi.topology.rev210316.gnmi.node.state.node.state.AvailableCapabilitiesBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.gnmi.topology.rev210316.gnmi.node.state.node.state.available.capabilities.AvailableCapabilityBuilder;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.NodeId;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.Node;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.NodeBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Establishes and holds connections of gNMI devices.
 */
public class DeviceConnectionManager implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(DeviceConnectionManager.class);

    private final GnmiMountPointRegistrator mountPointRegistrator;
    private final SchemaContextHolder schemaContextHolder;
    private final GnmiDataBrokerFactory gnmiDataBrokerFactory;
    private final Map<NodeId, DeviceConnection> activeDevices;
    private final DeviceConnectionInitializer connectionInitializer;
    private final DataBroker dataBroker;
    private final ExecutorService executorService;
    private final AtomicLong connectionGeneration;
    private final Map<NodeId, Long> activeGenerations;

    public DeviceConnectionManager(final GnmiMountPointRegistrator mountPointRegistrator,
            final SchemaContextHolder schemaContextHolder, final GnmiDataBrokerFactory gnmiDataBrokerFactory,
            final DeviceConnectionInitializer connectionInitializer, final DataBroker dataBroker,
            final ExecutorService executors) {
        this.mountPointRegistrator = mountPointRegistrator;
        this.schemaContextHolder = schemaContextHolder;
        this.gnmiDataBrokerFactory = gnmiDataBrokerFactory;
        this.connectionInitializer = connectionInitializer;
        this.dataBroker = dataBroker;
        this.executorService = executors;
        this.activeDevices = new ConcurrentHashMap<>();
        this.connectionGeneration = new AtomicLong();
        this.activeGenerations = new ConcurrentHashMap<>();
    }

    public ListenableFuture<CommitInfo> connectDevice(final Node node) {
        if (!activeDevices.containsKey(node.getNodeId())) {
            // Stamp this attempt with a fresh generation. Starting a new attempt supersedes any older
            // in-flight one, and closeConnection() drops the stamp, so a callback which completes after the
            // node was removed (or replaced) finds its generation is no longer current and aborts.
            final var generation = connectionGeneration.incrementAndGet();
            activeGenerations.put(node.getNodeId(), generation);
            final ListenableFuture<CommitInfo> connectionFuture;
            try {
                /*
                 Establish connection with device (future will be set with GnmiDeviceManager for device when
                 state of the gRPC channel is READY
                 */
                final var deviceConnectionFuture = connectionInitializer.initConnection(node);

                connectionFuture = Futures.transformAsync(deviceConnectionFuture,
                    deviceConnection -> prepareDeviceConnection(node, deviceConnection, generation),
                    executorService);

            } catch (SessionSecurityException e) {
                // Drop the stamp: initConnection() never registered the node, so nothing else would clear it.
                activeGenerations.remove(node.getNodeId(), generation);
                return Futures.immediateFailedFuture(e);
            }

            // Report a failure of a no-longer-current attempt (node removed or superseded) as a
            // cancellation, so the caller uniformly skips stale writes. An existing cancellation is kept.
            return Futures.catchingAsync(connectionFuture, Throwable.class, throwable -> {
                if (throwable instanceof CancellationException || !isCurrentGeneration(node.getNodeId(), generation)) {
                    return Futures.immediateCancelledFuture();
                }
                return Futures.immediateFailedFuture(throwable);
            }, executorService);

        } else {
            LOG.debug("Node {} is already active", node.getNodeId());
            return Futures.immediateFuture(null);
        }
    }

    private ListenableFuture<CommitInfo> prepareDeviceConnection(final Node node,
            final DeviceConnection deviceConnection, final long generation) {
        final var mountPointCreatedFuture = createMountPoint(node, deviceConnection, generation);

        return Futures.transformAsync(mountPointCreatedFuture,
            voidResult -> {
                final var statusReadyFuture = deviceConnection.setDeviceStatusReady();

                // handle GnmiConnectionStatusException in `statusReadyFuture`
                return Futures.catchingAsync(statusReadyFuture, GnmiConnectionStatusException.class,
                    statusException -> {
                        LOG.error("Connection status unexpectedly changed from READY to {} while creating"
                                + " Mountpoint", statusException.getCurrentState());
                        throw statusException;
                    },
                    executorService);
            },
            executorService);
    }

    private ListenableFuture<Void> createMountPoint(final Node node, final DeviceConnection deviceConnection,
            final long generation) {

        final var readCapabilitiesFuture =
                deviceConnection.getGnmiSession().capabilities(GnmiRequestUtils.makeDefaultCapabilityRequest());

        return Futures.transformAsync(readCapabilitiesFuture,
            capabilityResponse -> {
                LOG.debug("Received gNMI Capabiltiies response from {} : {}",node.getNodeId(), capabilityResponse);

                if (!capabilityResponse.getSupportedEncodingsList().contains(Gnmi.Encoding.JSON_IETF)) {
                    return Futures.immediateFailedFuture(
                            new MissingEncodingException("gNMI Device must support JSON_IETF encoding"));
                }

                final List<GnmiDeviceCapability> capabilitiesList = new ArrayList<>();
                final var forceCapabilities = deviceConnection.getConfigurableParameters().getModelDataList();
                if (forceCapabilities.isPresent()) {
                    final var capabilitiesResponseBuilder = Gnmi.CapabilityResponse.newBuilder()
                        .addAllSupportedModels(forceCapabilities.orElseThrow()).build();
                    capabilitiesList.addAll(GnmiRequestUtils.fromCapabilitiesResponse(capabilitiesResponseBuilder));
                } else {
                    capabilitiesList.addAll(GnmiRequestUtils.fromCapabilitiesResponse(capabilityResponse));
                }
                try {
                    final var schemaContext = schemaContextHolder.getSchemaContext(capabilitiesList);
                    deviceConnection.setSchemaContext(schemaContext);
                    final var gnmiDataBroker = gnmiDataBrokerFactory.create(deviceConnection);

                    // The node may have been closed, or superseded by a newer attempt, while this connection
                    // was still in flight. Claim it atomically against closeConnection(): if this attempt is no
                    // longer the current generation, registering the mountpoint or entering activeDevices now
                    // would resurrect a node which is already gone.
                    synchronized (this) {
                        if (!isCurrentGeneration(node.getNodeId(), generation)) {
                            LOG.info("Node {} was closed while connecting, aborting mountpoint creation",
                                    node.getNodeId());
                            closeQuietly(deviceConnection);
                            // Cancel so prepareDeviceConnection skips setDeviceStatusReady() on the closed
                            // connection and onFailure treats it as a no-op.
                            return Futures.immediateCancelledFuture();
                        }
                        mountPointRegistrator.registerMountPoint(node, schemaContext, gnmiDataBroker);
                        activeDevices.put(node.getNodeId(), deviceConnection);
                        connectionInitializer.finishInitializer(node.getNodeId());
                    }
                    // Committed outside the claim lock (the commit blocks). A narrow window remains where a
                    // delete racing this merge leaves capabilities behind; see FIXME in saveCapabilitiesList.
                    saveCapabilitiesList(node.getNodeId(), capabilitiesList);
                    return Futures.immediateFuture(null);

                } catch (SchemaException | ExecutionException | TimeoutException e) {
                    return Futures.immediateFailedFuture(e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Futures.immediateFailedFuture(e);
                }
            },
            executorService);
    }

    private void saveCapabilitiesList(final NodeId nodeId, final List<GnmiDeviceCapability> gnmiDeviceCapabilities)
            throws InterruptedException, ExecutionException, TimeoutException {
        // Skip the merge if the node was deleted since the generation check. A read failure is "could not
        // tell", not "deleted": the device is already mounted, so proceed rather than fail a live connection.
        boolean present = true;
        try {
            present = DatastoreUtils.nodePresentInConfig(dataBroker, nodeId);
        } catch (ExecutionException | TimeoutException e) {
            LOG.warn("Unable to read configuration of node {}, proceeding with capabilities write",
                    nodeId.getValue(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Interrupted while reading configuration of node {}, proceeding with capabilities write",
                    nodeId.getValue(), e);
        }
        if (!present) {
            LOG.info("Node {} is no longer present in configuration, not writing available capabilities",
                    nodeId.getValue());
            return;
        }

        final var capabilityList = gnmiDeviceCapabilities.stream()
                .map(cap -> new AvailableCapabilityBuilder().setCapability(cap.toString()).build())
                .collect(Collectors.toList());

        final var operationalNode = new NodeBuilder()
                .setNodeId(nodeId)
                .addAugmentation(new GnmiNodeBuilder()
                        .setNodeState(new NodeStateBuilder()
                                .setAvailableCapabilities(new AvailableCapabilitiesBuilder()
                                        .setAvailableCapability(capabilityList)
                                        .build())
                                .build())
                        .build())
                .build();

        final var tx = dataBroker.newWriteOnlyTransaction();
        tx.merge(LogicalDatastoreType.OPERATIONAL, IdentifierUtils.gnmiNodeID(nodeId), operationalNode);
        // FIXME (GNMI-29): merge not ordered vs concurrent disconnect delete; serialize per-node on async path.
        tx.commit().get(TimeoutUtils.DATASTORE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    }

    public boolean nodeActive(final NodeId nodeId) {
        return activeDevices.containsKey(nodeId);
    }

    public boolean nodeConnecting(final NodeId nodeId) {
        return connectionInitializer.isNodeConnecting(nodeId);
    }

    private boolean isCurrentGeneration(final NodeId nodeId, final long generation) {
        final var current = activeGenerations.get(nodeId);
        return current != null && current == generation;
    }

    @SuppressWarnings({"checkstyle:illegalCatch"})
    private void closeQuietly(final DeviceConnection deviceConnection) {
        try {
            deviceConnection.close();
        } catch (Exception e) {
            LOG.warn("Failed closing device connection of node {}", deviceConnection.getIdentifier(), e);
        }
    }

    @SuppressWarnings({"checkstyle:illegalCatch"})
    public synchronized void closeConnection(final NodeId nodeId) {
        activeGenerations.remove(nodeId);
        if (!nodeActive(nodeId) && !nodeConnecting(nodeId)) {
            LOG.warn("Node {} is not registered, not deleting", nodeId);
        }
        if (nodeConnecting(nodeId)) {
            try {
                connectionInitializer.cancelInitializer(nodeId);
            } catch (Exception e) {
                LOG.warn("Failed closing initializer of connecting node {}", nodeId);
            }
        }
        if (activeDevices.containsKey(nodeId)) {
            final var deviceConnection = activeDevices.get(nodeId);
            try {
                deviceConnection.close();
            } catch (Exception e) {
                LOG.warn("Failed closing device manager of connected node {}", nodeId);
            }
            activeDevices.remove(nodeId);
            mountPointRegistrator.unregisterMountPoint(nodeId);
        }
    }

    @Override
    public void close() {
        LOG.info("Closing all connections to devices");
        for (NodeId nodeId : Sets.union(connectionInitializer.getActiveInitializers(), activeDevices.keySet())) {
            closeConnection(nodeId);
        }
        activeGenerations.clear();
    }
}
