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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import gnmi.Gnmi;
import io.grpc.ConnectivityState;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.opendaylight.gnmi.connector.gnmi.session.api.GnmiSession;
import org.opendaylight.gnmi.connector.session.SessionManagerFactory;
import org.opendaylight.gnmi.connector.session.api.SessionManager;
import org.opendaylight.gnmi.connector.session.api.SessionProvider;
import org.opendaylight.gnmi.southbound.device.session.security.GnmiSecurityProvider;
import org.opendaylight.gnmi.southbound.mountpoint.GnmiMountPointRegistrator;
import org.opendaylight.gnmi.southbound.mountpoint.broker.GnmiDataBrokerFactory;
import org.opendaylight.gnmi.southbound.schema.SchemaContextHolder;
import org.opendaylight.mdsal.binding.api.DataBroker;
import org.opendaylight.mdsal.binding.api.ReadTransaction;
import org.opendaylight.mdsal.binding.api.WriteTransaction;
import org.opendaylight.mdsal.common.api.CommitInfo;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.inet.types.rev130715.Host;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.inet.types.rev130715.IpAddress;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.inet.types.rev130715.Ipv4Address;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.inet.types.rev130715.PortNumber;
import org.opendaylight.yang.gen.v1.urn.opendaylight.gnmi.topology.rev210316.GnmiNode;
import org.opendaylight.yang.gen.v1.urn.opendaylight.gnmi.topology.rev210316.GnmiNodeBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.gnmi.topology.rev210316.gnmi.connection.parameters.ConnectionParametersBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.gnmi.topology.rev210316.gnmi.node.state.NodeState;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.NodeId;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.Node;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.NodeBuilder;
import org.opendaylight.yangtools.yang.common.Uint16;

/**
 * Tests node status written to the operational datastore on reconnect of an already connected device.
 *
 * <p>All executors are direct, so channel state changes fired via {@link #fireChannelStateChange} run the
 * whole connection logic synchronously on the test thread, making the flap scenarios deterministic.</p>
 */
class DeviceReconnectionStatusTest {
    private static final long TIMEOUT_MILLIS = 30_000;
    private static final Gnmi.CapabilityResponse CAPABILITY_RESPONSE = Gnmi.CapabilityResponse.newBuilder()
            .addSupportedEncodings(Gnmi.Encoding.JSON_IETF)
            .build();

    private final List<Node> mergedNodes = new ArrayList<>();

    private DeviceConnectionManager connectionManager;
    private GnmiSession gnmiSessionMock;
    private GnmiMountPointRegistrator mountPointRegistratorMock;
    private ArgumentCaptor<Runnable> stateChangeCaptor;
    private volatile ConnectivityState channelState;

    @BeforeEach
    public void setup() {
        final ExecutorService directExecutor = MoreExecutors.newDirectExecutorService();
        final DataBroker dataBrokerMock = Mockito.mock(DataBroker.class);
        final WriteTransaction txMock = Mockito.mock(WriteTransaction.class);
        final ReadTransaction readTxMock = Mockito.mock(ReadTransaction.class);
        final GnmiSecurityProvider securityProviderMock = Mockito.mock(GnmiSecurityProvider.class);
        final SessionManager sessionManagerMock = Mockito.mock(SessionManager.class);
        final SessionManagerFactory sessionManagerFactoryMock = Mockito.mock(SessionManagerFactory.class);
        final SchemaContextHolder schemaContextHolderMock = Mockito.mock(SchemaContextHolder.class);
        final GnmiDataBrokerFactory gnmiDataBrokerFactoryMock = Mockito.mock(GnmiDataBrokerFactory.class);
        final SessionProvider sessionProviderMock = Mockito.mock(SessionProvider.class);
        gnmiSessionMock = Mockito.mock(GnmiSession.class);
        mountPointRegistratorMock = Mockito.mock(GnmiMountPointRegistrator.class);
        stateChangeCaptor = ArgumentCaptor.forClass(Runnable.class);

        when(sessionManagerFactoryMock.createSessionManager(any()))
                .thenAnswer(invocation -> sessionManagerMock);
        when(sessionManagerMock.createSession(any()))
                .thenAnswer(invocation -> sessionProviderMock);
        when(sessionProviderMock.getChannelState())
                .thenAnswer(invocation -> channelState);
        when(sessionProviderMock.getGnmiSession())
                .thenAnswer(invocation -> gnmiSessionMock);
        doNothing().when(sessionProviderMock).notifyOnStateChangedOneOff(any(), stateChangeCaptor.capture());

        when(dataBrokerMock.newWriteOnlyTransaction())
                .thenAnswer(invocation -> txMock);
        when(txMock.commit())
                .thenAnswer(invocation -> CommitInfo.emptyFluentFuture());

        // The mountpoint-creation path skips saving capabilities for nodes confirmed gone from the
        // configuration datastore, so report the node as still configured to keep exercising that write.
        when(dataBrokerMock.newReadOnlyTransaction())
                .thenAnswer(invocation -> readTxMock);
        when(readTxMock.read(any(), any())).thenAnswer(invocation -> FluentFuture.from(
            Futures.immediateFuture(Optional.of(createNode("configured-node", 9000)))));

        doAnswer(invocation -> {
            if (invocation.getArgument(2) instanceof Node mergedNode) {
                mergedNodes.add(mergedNode);
            }
            return null;
        }).when(txMock).merge(any(), any(), any());

        final DeviceConnectionInitializer connectionInitializer = new DeviceConnectionInitializer(
                securityProviderMock, sessionManagerFactoryMock, dataBrokerMock, directExecutor);
        connectionManager = new DeviceConnectionManager(mountPointRegistratorMock, schemaContextHolderMock,
                gnmiDataBrokerFactoryMock, connectionInitializer, dataBrokerMock, directExecutor);
    }

    /**
     * After the initial connection writes READY once, a device drop (TRANSIENT_FAILURE) followed by a
     * reconnect (READY again) must refresh READY in the operational datastore a second time, instead of
     * leaving the node stuck on the stale TRANSIENT_FAILURE status.
     */
    @Test
    public void reconnectRefreshesReadyStatusInDatastoreTest() throws Exception {
        final Node node = createNode("reconnect-node", 9000);
        when(gnmiSessionMock.capabilities(any()))
                .thenAnswer(invocation -> Futures.immediateFuture(CAPABILITY_RESPONSE));

        channelState = ConnectivityState.CONNECTING;
        final ListenableFuture<CommitInfo> connectFuture = connectionManager.connectDevice(node);
        assertEquals(0, countMergedStatus(NodeState.NodeStatus.READY));

        // Channel becomes READY, mountpoint is created and READY is written by the connection manager
        fireChannelStateChange(ConnectivityState.READY);
        connectFuture.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        assertTrue(connectionManager.nodeActive(node.getNodeId()));
        assertEquals(1, countMergedStatus(NodeState.NodeStatus.READY));

        // Device drops, only the failure status is written
        fireChannelStateChange(ConnectivityState.TRANSIENT_FAILURE);
        assertEquals(1, countMergedStatus(NodeState.NodeStatus.TRANSIENTFAILURE));
        assertEquals(1, countMergedStatus(NodeState.NodeStatus.READY));

        // Device reconnects, READY status is refreshed in the datastore
        fireChannelStateChange(ConnectivityState.READY);
        assertEquals(2, countMergedStatus(NodeState.NodeStatus.READY));
    }

    /**
     * Reproduces the race where the channel briefly flaps READY -&gt; IDLE -&gt; READY while the very first
     * mountpoint creation is still in flight (blocked on the capabilities response). The reconnect-status
     * refresh path must not advertise READY before the mountpoint exists; once mountpoint creation
     * completes, {@link DeviceConnectionManager} itself writes READY exactly once.
     */
    @Test
    public void readyFlapBeforeMountpointCreationDoesNotWriteReadyTest() throws Exception {
        final Node node = createNode("flap-node", 9001);
        final SettableFuture<Gnmi.CapabilityResponse> capabilitiesFuture = SettableFuture.create();
        when(gnmiSessionMock.capabilities(any()))
                .thenAnswer(invocation -> capabilitiesFuture);

        channelState = ConnectivityState.CONNECTING;
        final ListenableFuture<CommitInfo> connectFuture = connectionManager.connectDevice(node);

        // Channel becomes READY, but mountpoint creation is blocked on the capabilities response
        fireChannelStateChange(ConnectivityState.READY);
        assertFalse(connectFuture.isDone());

        // Rapid READY -> IDLE -> READY flap while the mountpoint does not exist yet
        fireChannelStateChange(ConnectivityState.IDLE);
        fireChannelStateChange(ConnectivityState.READY);

        // READY must not be advertised before the mountpoint is created
        assertEquals(0, countMergedStatus(NodeState.NodeStatus.READY));

        // Mountpoint creation finishes, the connection manager itself advertises READY exactly once
        capabilitiesFuture.set(CAPABILITY_RESPONSE);
        connectFuture.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        assertTrue(connectionManager.nodeActive(node.getNodeId()));
        Mockito.verify(mountPointRegistratorMock).registerMountPoint(any(), any(), any());
        assertEquals(1, countMergedStatus(NodeState.NodeStatus.READY));
    }

    private void fireChannelStateChange(final ConnectivityState newState) {
        channelState = newState;
        stateChangeCaptor.getValue().run();
    }

    private long countMergedStatus(final NodeState.NodeStatus status) {
        return mergedNodes.stream()
                .map(mergedNode -> mergedNode.augmentation(GnmiNode.class))
                .filter(Objects::nonNull)
                .map(GnmiNode::getNodeState)
                .filter(Objects::nonNull)
                .map(NodeState::getNodeStatus)
                .filter(status::equals)
                .count();
    }

    private static Node createNode(final String nameOfNode, final int port) {
        return new NodeBuilder()
                .setNodeId(new NodeId(nameOfNode))
                .addAugmentation(new GnmiNodeBuilder().setConnectionParameters(
                        new ConnectionParametersBuilder()
                                .setHost(new Host(new IpAddress(Ipv4Address.getDefaultInstance("127.0.0.1"))))
                                .setPort(new PortNumber(Uint16.valueOf(port)))
                                .build())
                        .build())
                .build();
    }
}
