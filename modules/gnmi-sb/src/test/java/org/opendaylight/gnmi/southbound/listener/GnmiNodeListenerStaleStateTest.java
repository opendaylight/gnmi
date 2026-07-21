/*
 * Copyright (c) 2026 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.gnmi.southbound.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import gnmi.Gnmi;
import io.grpc.ConnectivityState;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import org.opendaylight.mdsal.binding.api.ReadTransaction;
import org.opendaylight.mdsal.binding.api.WriteTransaction;
import org.opendaylight.mdsal.binding.dom.adapter.test.AbstractConcurrentDataBrokerTest;
import org.opendaylight.mdsal.common.api.CommitInfo;
import org.opendaylight.mdsal.common.api.LogicalDatastoreType;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.inet.types.rev130715.Host;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.inet.types.rev130715.IpAddress;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.inet.types.rev130715.Ipv4Address;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.inet.types.rev130715.PortNumber;
import org.opendaylight.yang.gen.v1.urn.opendaylight.gnmi.topology.rev210316.GnmiNode;
import org.opendaylight.yang.gen.v1.urn.opendaylight.gnmi.topology.rev210316.GnmiNodeBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.gnmi.topology.rev210316.gnmi.connection.parameters.ConnectionParametersBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.gnmi.topology.rev210316.gnmi.node.state.NodeState;
import org.opendaylight.yang.gen.v1.urn.opendaylight.gnmi.topology.rev210316.gnmi.node.state.NodeStateBuilder;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.NodeId;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.TopologyId;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.TopologyBuilder;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.Node;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.NodeBuilder;
import org.opendaylight.yangtools.util.concurrent.FluentFutures;
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
     * Tests that the FAILURE writer does not resurrect a deleted node: a connection that fails after the
     * node was deleted from config leaves no operational state.
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

        try (registration) {
            // 1. Create the node in config -> listener starts the (in-flight) connection.
            writeConfigNode();
            assertTrue(connectInvoked.await(TIMEOUT_MS, TimeUnit.MILLISECONDS),
                "listener never started the device connection");

            // 2. Seed operational state for the node so we can deterministically observe the
            // delete being applied by disconnectNode().
            seedOperationalState();
            awaitOperational(dataBroker, true, "seed operational state not visible");

            // 3. Delete the node from config -> disconnectNode() deletes operational. Wait until the
            // delete has actually committed (operational becomes absent).
            deleteConfigNode();
            awaitOperational(dataBroker, false, "disconnectNode did not remove operational state");

            // 4. Only NOW does the connection fail (non-cancellation). onFailure runs inline and, in
            // the buggy code, merges a FAILURE node-state back into operational.
            connectFuture.setException(new RuntimeException("UNAUTHENTICATED: No authentication header"));

            // 5. Invariant: a node deleted from config must have no operational state.
            final var leftover = readOperational(dataBroker);
            assertFalse(leftover.isPresent(), "STALE STATE: operational node-state was resurrected by the "
                + "FAILURE callback after the node was deleted from config: " + leftover.orElse(null));
        } finally {
            directExecutor.shutdownNow();
        }
    }

    /**
     * Tests that the READY writer does not resurrect a deleted node: a late READY status callback after
     * the node was deleted from config leaves no operational state.
     */
    @Test
    void statusReadyWriteAfterDeleteMustNotLeaveStaleOperationalState() throws Exception {
        final var dataBroker = getDataBroker();
        final var directExecutor = MoreExecutors.newDirectExecutorService();

        // A status listener whose underlying channel is READY.
        when(sessionProvider.getChannelState()).thenReturn(ConnectivityState.READY);
        final var statusListener = new GnmiConnectionStatusListener(sessionProvider, dataBroker, NODE_ID,
            directExecutor);

        try (statusListener) {
            // 1. Node exists in config, its connection attempt is in flight.
            writeConfigNode();
            statusListener.init();

            // 2. Node deleted from config -> its operational state is removed. Confirm it is absent.
            deleteConfigNode();
            awaitOperational(dataBroker, false, "operational state not absent after delete");

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
     * Tests that the capabilities writer does not resurrect a deleted node: when the connection completes
     * after the node was deleted mid-connect, the generation gate aborts the write and cancels the connect
     * future.
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
        // No setDeviceStatusReady() stub: the abort path must never call it on the closed connection.
        when(schemaContextHolder.getSchemaContext(any())).thenReturn(schemaContext);
        when(dataBrokerFactory.create(any())).thenReturn(gnmiDataBroker);

        final var dcm = new DeviceConnectionManager(mountPointRegistrator, schemaContextHolder,
            dataBrokerFactory, initializer, dataBroker, directExecutor);

        try (dcm) {
            // 1. Start connecting the node (mountpoint/capabilities setup still pending).
            writeConfigNode();
            final var connectResult = dcm.connectDevice(buildNode());

            // 2. Node deleted from config -> closeConnection is a no-op in this window, then the
            // operational subtree is deleted (as disconnectNode does).
            dcm.closeConnection(NODE_ID);
            deleteConfigNode();
            deleteOperationalNode();
            awaitOperational(dataBroker, false, "operational state not absent after delete");

            // 3. Only NOW does the connection complete. createMountPoint() sees a stale generation, aborts
            // before saveCapabilitiesList(), and cancels the connect future.
            connectFuture.set(deviceConnection);
            assertThrows(CancellationException.class,
                () -> connectResult.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));

            // 4. Invariant: a node deleted from config must have no operational state.
            final var leftover = readOperational(dataBroker);
            assertFalse(leftover.isPresent(), "STALE STATE: operational node-state was resurrected by the "
                + "capabilities write after the node was deleted from config: " + leftover.orElse(null));
        } finally {
            directExecutor.shutdownNow();
        }
    }

    /**
     * Tests that the generation gate cancels a superseded attempt: an attempt whose node was reconfigured
     * (so still in config, where the config-presence guards can not help) that later fails surfaces as a
     * cancellation, so its FAILURE write is skipped rather than clobbering the current attempt.
     */
    @Test
    void supersededAttemptFailureIsReportedAsCancellation() throws Exception {
        final var dataBroker = getDataBroker();
        final var directExecutor = MoreExecutors.newDirectExecutorService();

        // Two in-flight init futures: the first attempt, then a second attempt that supersedes it.
        final SettableFuture<DeviceConnection> firstAttempt = SettableFuture.create();
        final SettableFuture<DeviceConnection> secondAttempt = SettableFuture.create();
        when(initializer.initConnection(any(Node.class)))
            .thenReturn(firstAttempt)
            .thenReturn(secondAttempt);

        final var dcm = new DeviceConnectionManager(mountPointRegistrator, schemaContextHolder,
            dataBrokerFactory, initializer, dataBroker, directExecutor);

        try (dcm) {
            // 1. Attempt 1 is in flight; attempt 2 (node never deleted, just reconfigured) supersedes it.
            final var firstResult = dcm.connectDevice(buildNode());
            final var secondResult = dcm.connectDevice(buildNode());

            // 2. Attempt 1 now fails with a real, non-cancellation error.
            firstAttempt.setException(new RuntimeException("UNAUTHENTICATED: No authentication header"));

            // 3. The superseded attempt must report as cancelled, not failed, so no FAILURE state is
            // written for a node a newer attempt now owns.
            assertThrows(CancellationException.class,
                () -> firstResult.get(TIMEOUT_MS, TimeUnit.MILLISECONDS));
            assertFalse(secondResult.isDone(), "the current attempt must not be affected by the superseded one");
        } finally {
            directExecutor.shutdownNow();
        }
    }

    /**
     * Tests that a failed config-presence read does not fail a live connection: "could not tell" is not
     * "node deleted", so the READY hand-off write proceeds rather than failing an already-mounted device.
     */
    @Test
    void readyWriteProceedsWhenConfigPresenceReadFails() throws Exception {
        final var directExecutor = MoreExecutors.newDirectExecutorService();

        final var brokerMock = mock(DataBroker.class);
        // The config-presence read fails ("could not tell").
        final var failingReadTx = mock(ReadTransaction.class);
        when(failingReadTx.read(any(), any()))
            .thenReturn(FluentFutures.immediateFailedFluentFuture(new RuntimeException("config read failed")));
        when(brokerMock.newReadOnlyTransaction()).thenReturn(failingReadTx);
        // The operational write still goes ahead.
        final var writeTx = mock(WriteTransaction.class);
        when(brokerMock.newWriteOnlyTransaction()).thenReturn(writeTx);
        doReturn(CommitInfo.emptyFluentFuture()).when(writeTx).commit();

        when(sessionProvider.getChannelState()).thenReturn(ConnectivityState.READY);
        final var statusListener =
            new GnmiConnectionStatusListener(sessionProvider, brokerMock, NODE_ID, directExecutor);

        try (statusListener) {
            statusListener.init();
            statusListener.copyDeviceStatusReadyToDatastore().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);

            // The READY merge was issued despite the failed presence read.
            final var nodeCaptor = ArgumentCaptor.forClass(Node.class);
            verify(writeTx).merge(eq(LogicalDatastoreType.OPERATIONAL), eq(IdentifierUtils.gnmiNodeIID(NODE_ID)),
                nodeCaptor.capture());
            assertEquals(NodeState.NodeStatus.READY, nodeCaptor.getValue().augmentation(GnmiNode.class)
                .getNodeState().getNodeStatus());
        } finally {
            directExecutor.shutdownNow();
        }
    }

    private void writeConfigNode() throws Exception {
        final var tx = getDataBroker().newWriteOnlyTransaction();
        tx.merge(LogicalDatastoreType.CONFIGURATION, IdentifierUtils.gnmiNodeIID(NODE_ID), buildNode());
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
        tx.delete(LogicalDatastoreType.CONFIGURATION, IdentifierUtils.gnmiNodeIID(NODE_ID));
        tx.commit().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private void deleteOperationalNode() throws Exception {
        final var tx = getDataBroker().newWriteOnlyTransaction();
        tx.delete(LogicalDatastoreType.OPERATIONAL, IdentifierUtils.gnmiNodeIID(NODE_ID));
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
        tx.merge(LogicalDatastoreType.OPERATIONAL, IdentifierUtils.gnmiNodeIID(NODE_ID), node);
        tx.commit().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private static Optional<Node> readOperational(final DataBroker dataBroker) throws Exception {
        try (var tx = dataBroker.newReadOnlyTransaction()) {
            return tx.read(LogicalDatastoreType.OPERATIONAL, IdentifierUtils.gnmiNodeIID(NODE_ID))
                .get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
    }

    private static void awaitOperational(final DataBroker dataBroker, final boolean present, final String alias) {
        Awaitility.await(alias)
            .atMost(Duration.ofMillis(TIMEOUT_MS))
            .pollInterval(Duration.ofMillis(20))
            .until(() -> readOperational(dataBroker).isPresent() == present);
    }
}
