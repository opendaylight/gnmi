/*
 * Copyright (c) 2026 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.gnmi.southbound.listener;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import gnmi.Gnmi;
import io.grpc.ConnectivityState;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opendaylight.gnmi.connector.gnmi.session.api.GnmiSession;
import org.opendaylight.gnmi.connector.session.api.SessionProvider;
import org.opendaylight.gnmi.southbound.device.connection.ConfigurableParameters;
import org.opendaylight.gnmi.southbound.device.connection.DeviceConnection;
import org.opendaylight.gnmi.southbound.device.connection.DeviceConnectionInitializer;
import org.opendaylight.gnmi.southbound.device.connection.DeviceConnectionManager;
import org.opendaylight.gnmi.southbound.device.session.listener.GnmiConnectionStatusListener;
import org.opendaylight.gnmi.southbound.identifier.IdentifierUtils;
import org.opendaylight.gnmi.southbound.mountpoint.GnmiMountPointRegistrator;
import org.opendaylight.gnmi.southbound.mountpoint.broker.GnmiDataBroker;
import org.opendaylight.gnmi.southbound.mountpoint.broker.GnmiDataBrokerFactory;
import org.opendaylight.gnmi.southbound.schema.SchemaContextHolder;
import org.opendaylight.mdsal.binding.api.DataBroker;
import org.opendaylight.mdsal.binding.dom.adapter.test.AbstractConcurrentDataBrokerTest;
import org.opendaylight.mdsal.common.api.CommitInfo;
import org.opendaylight.mdsal.common.api.LogicalDatastoreType;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.inet.types.rev130715.Host;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.inet.types.rev130715.IpAddress;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.inet.types.rev130715.Ipv4Address;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.inet.types.rev130715.PortNumber;
import org.opendaylight.yang.gen.v1.urn.opendaylight.gnmi.topology.rev210316.GnmiNodeBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.gnmi.topology.rev210316.gnmi.connection.parameters.ConnectionParametersBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.gnmi.topology.rev210316.gnmi.node.state.NodeState;
import org.opendaylight.yang.gen.v1.urn.opendaylight.gnmi.topology.rev210316.gnmi.node.state.NodeStateBuilder;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.NodeId;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.TopologyId;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.TopologyBuilder;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.Node;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.NodeBuilder;
import org.opendaylight.yangtools.yang.common.Uint16;
import org.opendaylight.yangtools.yang.model.api.EffectiveModelContext;

/**
 * Regression tests for the stale-operational-state ordering defect in the gNMI connection lifecycle.
 *
 * <p>Three async writers merge into the same operational {@code node-state} subtree: a FAILURE state
 * ({@link GnmiNodeListener}), a READY state ({@link GnmiConnectionStatusListener}) and available
 * capabilities ({@link DeviceConnectionManager}). Each test deletes the node from config first, then
 * runs one writer over a real datastore, asserting the invariant "a node absent from config leaves
 * no operational state". Every writer currently violates it, so all cases FAIL until each
 * best-effort write is guarded against an already-removed node.</p>
 */
@Disabled
@ExtendWith(MockitoExtension.class)
class GnmiNodeListenerStaleStateTest extends AbstractConcurrentDataBrokerTest {
    private static final NodeId NODE_ID = new NodeId("stale-node");
    private static final long TIMEOUT_MS = 10_000L;

    @Mock
    private DeviceConnectionManager deviceConnectionManager;
    @Mock
    private SessionProvider sessionProvider;
    @Mock
    private DeviceConnectionInitializer initializer;
    @Mock
    private DeviceConnection deviceConnection;
    @Mock
    private GnmiSession session;
    @Mock
    private ConfigurableParameters configurableParameters;
    @Mock
    private SchemaContextHolder schemaContextHolder;
    @Mock
    private GnmiDataBrokerFactory dataBrokerFactory;
    @Mock
    private GnmiMountPointRegistrator mountPointRegistrator;
    @Mock
    private GnmiDataBroker gnmiDataBroker;
    @Mock
    private EffectiveModelContext schemaContext;
    @Mock
    private CommitInfo commitInfo;

    GnmiNodeListenerStaleStateTest() {
        super(true);
    }

    @BeforeEach
    void before() throws Exception {
        setup();
        final var topology = new TopologyBuilder()
            .setTopologyId(new TopologyId(IdentifierUtils.GNMI_TOPOLOGY_ID))
            .build();
        for (final var store
                : new LogicalDatastoreType[] {LogicalDatastoreType.CONFIGURATION, LogicalDatastoreType.OPERATIONAL}) {
            final var tx = getDataBroker().newWriteOnlyTransaction();
            tx.merge(store, IdentifierUtils.GNMI_TOPOLOGY_PATH, topology);
            tx.commit().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * The FAILURE writer. {@link GnmiNodeListener} starts an in-flight connection, the node is then
     * deleted from config (so {@code disconnectNode()} removes its operational state), and only
     * afterwards does the connection future fail with a non-cancellation error. The {@code onFailure}
     * callback merges a FAILURE {@code node-state} back into operational, resurrecting the deleted
     * node. Fails until the failure write is skipped for an already-removed node.
     */
    @Test
    void failureCallbackAfterDeleteMustNotLeaveStaleOperationalState() throws Exception {
        final var dataBroker = getDataBroker();
        // Run the listener's connection callbacks inline on the caller thread so we control exactly
        // when onFailure runs (when we complete the future below).
        final var directExecutor = MoreExecutors.newDirectExecutorService();

        // The connection future we complete by hand, after the delete has committed.
        final SettableFuture<CommitInfo> connectFuture = SettableFuture.create();
        final var connectInvoked = new CountDownLatch(1);

        when(deviceConnectionManager.connectDevice(any(Node.class))).thenAnswer(inv -> {
            connectInvoked.countDown();
            return connectFuture;
        });
        // Model the vulnerable window: at delete time the node is neither "connecting" nor "active"
        // (channel reached READY, capabilities failing), so closeConnection cancels nothing.
        doNothing().when(deviceConnectionManager).closeConnection(any(NodeId.class));

        final var listener = new GnmiNodeListener(deviceConnectionManager, dataBroker, directExecutor);
        final var registration = dataBroker.registerTreeChangeListener(LogicalDatastoreType.CONFIGURATION,
            IdentifierUtils.GNMI_NODE_DTI, listener);

        try {
            // 1. Create the node in config -> listener starts the (in-flight) connection.
            writeConfigNode();
            assertTrue(connectInvoked.await(TIMEOUT_MS, TimeUnit.MILLISECONDS),
                "listener never started the device connection");

            // 2. Seed operational state for the node so we can deterministically observe the
            // delete being applied by disconnectNode().
            seedOperationalState();
            assertTrue(awaitOperationalPresent(dataBroker, true), "seed operational state not visible");

            // 3. Delete the node from config -> disconnectNode() deletes operational. Wait until the
            // delete has actually committed (operational becomes absent).
            deleteConfigNode();
            assertTrue(awaitOperationalPresent(dataBroker, false), "disconnectNode did not remove "
                + "operational state");

            // 4. Only NOW does the connection fail (non-cancellation). onFailure runs inline and, in
            // the buggy code, merges a FAILURE node-state back into operational.
            connectFuture.setException(new RuntimeException("UNAUTHENTICATED: No authentication header"));

            // 5. Invariant: a node deleted from config must have no operational state.
            final var leftover = readOperational(dataBroker);
            assertFalse(leftover.isPresent(), "STALE STATE: operational node-state was resurrected by the "
                + "FAILURE callback after the node was deleted from config: " + leftover.orElse(null));
        } finally {
            registration.close();
            directExecutor.shutdownNow();
        }
    }

    /**
     * The READY writer. A {@link GnmiConnectionStatusListener} observes a READY channel, the node is
     * deleted from config (operational removed), and only afterwards does a late READY status
     * callback run {@code copyDeviceStatusReadyToDatastore()}. That merges a READY {@code node-status}
     * back into operational, resurrecting the deleted node. Fails until the status write is skipped
     * for an already-removed node.
     */
    @Test
    void statusReadyWriteAfterDeleteMustNotLeaveStaleOperationalState() throws Exception {
        final var dataBroker = getDataBroker();
        final var directExecutor = MoreExecutors.newDirectExecutorService();

        // A status listener whose underlying channel is READY.
        when(sessionProvider.getChannelState()).thenReturn(ConnectivityState.READY);
        final var statusListener = new GnmiConnectionStatusListener(sessionProvider, dataBroker, NODE_ID,
            directExecutor);

        try {
            // 1. Node exists in config, its connection attempt is in flight.
            writeConfigNode();
            statusListener.init();

            // 2. Node deleted from config -> its operational state is removed. Confirm it is absent.
            deleteConfigNode();
            assertTrue(awaitOperationalPresent(dataBroker, false), "operational state not absent after delete");

            // 3. A late READY status callback fires (the channel reported READY). In the buggy code
            // this merges a READY node-status back into operational for the deleted node.
            statusListener.copyDeviceStatusReadyToDatastore().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);

            // 4. Invariant: a node deleted from config must have no operational state.
            final var leftover = readOperational(dataBroker);
            assertFalse(leftover.isPresent(), "STALE STATE: operational node-state was resurrected by the READY "
                + "status write after the node was deleted from config: " + leftover.orElse(null));
        } finally {
            directExecutor.shutdownNow();
        }
    }

    /**
     * The capabilities writer. {@link DeviceConnectionManager#connectDevice} is started with an
     * in-flight connection, the node is deleted from config while it is neither connecting nor active
     * (so {@code closeConnection()} is a no-op), and only afterwards does the connection complete.
     * {@code createMountPoint()} then runs {@code saveCapabilitiesList()}, merging available
     * capabilities back into operational and resurrecting the deleted node. Fails until the
     * capabilities write is skipped for an already-removed node.
     */
    @Test
    void capabilitiesWriteAfterDeleteMustNotLeaveStaleOperationalState() throws Exception {
        final var dataBroker = getDataBroker();
        final var directExecutor = MoreExecutors.newDirectExecutorService();

        // The connection init future we complete by hand, after the delete has committed.
        final SettableFuture<DeviceConnection> connectFuture = SettableFuture.create();
        when(initializer.initConnection(any(Node.class))).thenReturn(connectFuture);
        // Vulnerable window: the node is not reported as "connecting" at delete time, so
        // closeConnection() cancels nothing and the in-flight future keeps running.
        when(initializer.isNodeConnecting(any(NodeId.class))).thenReturn(false);

        final var capabilities = Gnmi.CapabilityResponse.newBuilder()
            .addSupportedEncodings(Gnmi.Encoding.JSON_IETF)
            .build();
        when(session.capabilities(any())).thenReturn(Futures.immediateFuture(capabilities));
        when(deviceConnection.getGnmiSession()).thenReturn(session);
        when(configurableParameters.getModelDataList()).thenReturn(Optional.empty());
        when(deviceConnection.getConfigurableParameters()).thenReturn(configurableParameters);
        when(deviceConnection.setDeviceStatusReady())
            .thenReturn(FluentFuture.from(Futures.immediateFuture(commitInfo)));

        when(schemaContextHolder.getSchemaContext(any())).thenReturn(schemaContext);
        when(dataBrokerFactory.create(any())).thenReturn(gnmiDataBroker);

        final var dcm = new DeviceConnectionManager(mountPointRegistrator, schemaContextHolder,
            dataBrokerFactory, initializer, dataBroker, directExecutor);

        try {
            // 1. Start connecting the node (mountpoint/capabilities setup still pending).
            writeConfigNode();
            final var connectResult = dcm.connectDevice(buildNode());

            // 2. Node deleted from config -> closeConnection is a no-op in this window, then the
            // operational subtree is deleted (as disconnectNode does).
            dcm.closeConnection(NODE_ID);
            deleteConfigNode();
            deleteOperationalNode();
            assertTrue(awaitOperationalPresent(dataBroker, false), "operational state not absent after delete");

            // 3. Only NOW does the connection complete. createMountPoint() runs saveCapabilitiesList(),
            // which merges available-capabilities back into operational for the deleted node.
            connectFuture.set(deviceConnection);
            connectResult.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);

            // 4. Invariant: a node deleted from config must have no operational state.
            final var leftover = readOperational(dataBroker);
            assertFalse(leftover.isPresent(), "STALE STATE: operational node-state was resurrected by the "
                + "capabilities write after the node was deleted from config: " + leftover.orElse(null));
        } finally {
            directExecutor.shutdownNow();
        }
    }

    private void writeConfigNode() throws Exception {
        final var tx = getDataBroker().newWriteOnlyTransaction();
        tx.merge(LogicalDatastoreType.CONFIGURATION, IdentifierUtils.gnmiNodeID(NODE_ID), buildNode());
        tx.commit().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private static Node buildNode() {
        return new NodeBuilder()
            .setNodeId(NODE_ID)
            .addAugmentation(new GnmiNodeBuilder()
                .setConnectionParameters(new ConnectionParametersBuilder()
                    .setHost(new Host(new IpAddress(Ipv4Address.getDefaultInstance("127.0.0.1"))))
                    .setPort(new PortNumber(Uint16.valueOf(9339)))
                    .build())
                .build())
            .build();
    }

    private void deleteConfigNode() throws Exception {
        final var tx = getDataBroker().newWriteOnlyTransaction();
        tx.delete(LogicalDatastoreType.CONFIGURATION, IdentifierUtils.gnmiNodeID(NODE_ID));
        tx.commit().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private void deleteOperationalNode() throws Exception {
        final var tx = getDataBroker().newWriteOnlyTransaction();
        tx.delete(LogicalDatastoreType.OPERATIONAL, IdentifierUtils.gnmiNodeID(NODE_ID));
        tx.commit().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private void seedOperationalState() throws Exception {
        final var node = new NodeBuilder()
            .setNodeId(NODE_ID)
            .addAugmentation(new GnmiNodeBuilder()
                .setNodeState(new NodeStateBuilder()
                    .setNodeStatus(NodeState.NodeStatus.CONNECTING)
                    .build())
                .build())
            .build();
        final var tx = getDataBroker().newWriteOnlyTransaction();
        tx.merge(LogicalDatastoreType.OPERATIONAL, IdentifierUtils.gnmiNodeID(NODE_ID), node);
        tx.commit().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private static Optional<Node> readOperational(final DataBroker dataBroker) throws Exception {
        try (var tx = dataBroker.newReadOnlyTransaction()) {
            return tx.read(LogicalDatastoreType.OPERATIONAL, IdentifierUtils.gnmiNodeID(NODE_ID))
                .get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
    }

    private static boolean awaitOperationalPresent(final DataBroker dataBroker, final boolean present)
            throws Exception {
        final var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
        while (System.nanoTime() < deadline) {
            if (readOperational(dataBroker).isPresent() == present) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }
}
