/*
 * Copyright (c) 2026 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.gnmi.southbound.device.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.ConnectivityState;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opendaylight.gnmi.connector.session.SessionManagerFactory;
import org.opendaylight.gnmi.connector.session.api.SessionManager;
import org.opendaylight.gnmi.connector.session.api.SessionProvider;
import org.opendaylight.gnmi.southbound.device.session.security.GnmiSecurityProvider;
import org.opendaylight.gnmi.southbound.identifier.IdentifierUtils;
import org.opendaylight.mdsal.binding.dom.adapter.test.AbstractConcurrentDataBrokerTest;
import org.opendaylight.mdsal.common.api.LogicalDatastoreType;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.inet.types.rev130715.Host;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.inet.types.rev130715.IpAddress;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.inet.types.rev130715.Ipv4Address;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.inet.types.rev130715.PortNumber;
import org.opendaylight.yang.gen.v1.urn.opendaylight.gnmi.topology.rev210316.GnmiNode;
import org.opendaylight.yang.gen.v1.urn.opendaylight.gnmi.topology.rev210316.GnmiNodeBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.gnmi.topology.rev210316.gnmi.connection.parameters.ConnectionParametersBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.gnmi.topology.rev210316.gnmi.node.state.NodeState;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.NodeId;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.TopologyId;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.TopologyBuilder;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.Node;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.NodeBuilder;
import org.opendaylight.yangtools.yang.common.Uint16;

/**
 * Tests {@link DeviceConnectionInitializer} with a real connection status listener over a test data broker.
 *
 * <p>Verifies that the node-state is deleted from the operational datastore even when closing the session provider
 * throws, both for an established {@link DeviceConnection} and for a pending initializer, and that a repeated READY
 * transition of an established connection refreshes READY status in the datastore.</p>
 */
class DeviceConnectionInitializerTest extends AbstractConcurrentDataBrokerTest {
    private static final long TIMEOUT_MILLIS = 10_000L;
    private static final Node NODE = new NodeBuilder()
            .setNodeId(new NodeId("initializer-test-node"))
            .addAugmentation(new GnmiNodeBuilder().setConnectionParameters(
                new ConnectionParametersBuilder()
                    .setHost(new Host(new IpAddress(Ipv4Address.getDefaultInstance("127.0.0.1"))))
                    .setPort(new PortNumber(Uint16.valueOf(9000)))
                    .build())
                .build())
            .build();

    private SessionProvider sessionProviderMock;
    private ArgumentCaptor<Runnable> stateChangeCaptor;
    private ConnectivityState channelState;
    private DeviceConnectionInitializer connectionInitializer;

    DeviceConnectionInitializerTest() {
        super(true);
    }

    @BeforeEach
    void before() throws Exception {
        setup();
        final var topology = new TopologyBuilder()
            .setTopologyId(new TopologyId(IdentifierUtils.GNMI_TOPOLOGY_ID))
            .build();
        final var configTx = getDataBroker().newWriteOnlyTransaction();
        configTx.merge(LogicalDatastoreType.CONFIGURATION, IdentifierUtils.GNMI_TOPOLOGY_PATH, topology);
        // The READY write is skipped for nodes absent from configuration, so the node has to be configured
        configTx.put(LogicalDatastoreType.CONFIGURATION, IdentifierUtils.gnmiNodeID(NODE.getNodeId()), NODE);
        configTx.commit().get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        final var operationalTx = getDataBroker().newWriteOnlyTransaction();
        operationalTx.merge(LogicalDatastoreType.OPERATIONAL, IdentifierUtils.GNMI_TOPOLOGY_PATH, topology);
        operationalTx.commit().get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);

        sessionProviderMock = mock(SessionProvider.class);
        stateChangeCaptor = ArgumentCaptor.forClass(Runnable.class);
        final var sessionManagerMock = mock(SessionManager.class);
        final var sessionManagerFactoryMock = mock(SessionManagerFactory.class);
        when(sessionManagerFactoryMock.createSessionManager(any())).thenReturn(sessionManagerMock);
        when(sessionManagerMock.createSession(any())).thenReturn(sessionProviderMock);
        when(sessionProviderMock.getChannelState()).thenAnswer(invocation -> channelState);
        doNothing().when(sessionProviderMock).notifyOnStateChangedOneOff(any(), stateChangeCaptor.capture());

        connectionInitializer = new DeviceConnectionInitializer(mock(GnmiSecurityProvider.class),
            sessionManagerFactoryMock, getDataBroker(), MoreExecutors.newDirectExecutorService());
    }

    /**
     * Closing an established connection must delete its node-state even when closing the session provider throws,
     * and the session provider's failure must be propagated.
     */
    @Test
    void deviceConnectionCloseDeletesNodeStateWhenSessionCloseFailsTest() throws Exception {
        final var deviceConnection = connect();
        deviceConnection.setDeviceStatusReady().get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        assertEquals(NodeState.NodeStatus.READY, readNodeStatus().orElseThrow());

        final var sessionFailure = new IllegalStateException("channel did not terminate");
        doThrow(sessionFailure).when(sessionProviderMock).close();

        assertSame(sessionFailure, assertThrows(IllegalStateException.class, deviceConnection::close));
        assertFalse(readOperationalNode().isPresent());
    }

    /**
     * Cancelling a pending initializer must delete the node-state, cancel the connection future and release the
     * initializer even when closing the session provider throws, and the session provider's failure must be
     * propagated.
     */
    @Test
    void cancelInitializerDeletesNodeStateWhenSessionCloseFailsTest() throws Exception {
        channelState = ConnectivityState.CONNECTING;
        final var connectionFuture = connectionInitializer.initConnection(NODE);
        assertEquals(NodeState.NodeStatus.CONNECTING, readNodeStatus().orElseThrow());

        final var sessionFailure = new IllegalStateException("channel did not terminate");
        doThrow(sessionFailure).when(sessionProviderMock).close();

        assertSame(sessionFailure, assertThrows(IllegalStateException.class,
                () -> connectionInitializer.cancelInitializer(NODE.getNodeId())));
        assertFalse(readOperationalNode().isPresent());
        assertTrue(connectionFuture.isCancelled());
        assertFalse(connectionInitializer.isNodeConnecting(NODE.getNodeId()));
    }

    /**
     * A READY transition after the connection was established (reconnect) must refresh READY status in the
     * datastore, instead of leaving the node on the previous TRANSIENT_FAILURE status.
     */
    @Test
    void repeatedReadyRefreshesReadyStatusTest() throws Exception {
        try (var deviceConnection = connect()) {
            deviceConnection.setDeviceStatusReady().get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            assertEquals(NodeState.NodeStatus.READY, readNodeStatus().orElseThrow());

            fireChannelStateChange(ConnectivityState.TRANSIENT_FAILURE);
            assertEquals(NodeState.NodeStatus.TRANSIENTFAILURE, readNodeStatus().orElseThrow());

            fireChannelStateChange(ConnectivityState.READY);
            Awaitility.await().atMost(Duration.ofMillis(TIMEOUT_MILLIS))
                .until(() -> readNodeStatus().orElse(null) == NodeState.NodeStatus.READY);
        }
    }

    private DeviceConnection connect() throws Exception {
        channelState = ConnectivityState.CONNECTING;
        final ListenableFuture<DeviceConnection> connectionFuture = connectionInitializer.initConnection(NODE);
        assertFalse(connectionFuture.isDone());
        fireChannelStateChange(ConnectivityState.READY);
        return connectionFuture.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void fireChannelStateChange(final ConnectivityState newState) {
        channelState = newState;
        stateChangeCaptor.getValue().run();
    }

    private Optional<NodeState.NodeStatus> readNodeStatus() throws Exception {
        return readOperationalNode()
            .map(node -> node.augmentation(GnmiNode.class))
            .map(GnmiNode::getNodeState)
            .map(NodeState::getNodeStatus);
    }

    private Optional<Node> readOperationalNode() throws Exception {
        try (var tx = getDataBroker().newReadOnlyTransaction()) {
            return tx.read(LogicalDatastoreType.OPERATIONAL, IdentifierUtils.gnmiNodeID(NODE.getNodeId()))
                .get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        }
    }
}
