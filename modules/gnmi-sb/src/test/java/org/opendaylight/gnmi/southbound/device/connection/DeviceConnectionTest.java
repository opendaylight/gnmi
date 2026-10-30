/*
 * Copyright (c) 2026 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.gnmi.southbound.device.connection;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opendaylight.gnmi.connector.session.api.SessionProvider;
import org.opendaylight.gnmi.southbound.device.session.listener.GnmiConnectionStatusListener;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.NodeId;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.Node;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.NodeBuilder;

/**
 * Verifies the close ordering of {@link DeviceConnection}: the connection status listener must be closed
 * before the session provider, and a failure while closing the listener must not skip closing the session
 * provider (which would otherwise leak the gRPC channel). When both close steps fail, the first failure is
 * propagated with the later one suppressed into it.
 */
@ExtendWith(MockitoExtension.class)
class DeviceConnectionTest {
    private static final Node NODE = new NodeBuilder().setNodeId(new NodeId("close-order-node")).build();

    @Mock
    private SessionProvider sessionProviderMock;
    @Mock
    private GnmiConnectionStatusListener connectionStatusListenerMock;
    private DeviceConnection deviceConnection;

    @BeforeEach
    void setup() {
        deviceConnection = new DeviceConnection(sessionProviderMock, connectionStatusListenerMock, NODE);
    }

    /**
     * A normal close must close the connection status listener before the session provider, so that
     * tearing down the gRPC channel cannot trigger a final state-change notification into a listener
     * that is still active and about to have its operational state deleted out from under it.
     */
    @Test
    void closesListenerBeforeSessionProviderTest() throws Exception {
        deviceConnection.close();

        final var inOrder = Mockito.inOrder(connectionStatusListenerMock, sessionProviderMock);
        inOrder.verify(connectionStatusListenerMock).close();
        inOrder.verify(sessionProviderMock).close();
    }

    /**
     * If closing the connection status listener throws, the session provider must still be closed
     * (otherwise the gRPC channel leaks); the listener's exception is then propagated to the caller.
     */
    @Test
    void sessionProviderClosedWhenListenerCloseFailsTest() throws Exception {
        final var listenerFailure = new ExecutionException(new IllegalStateException("commit failed"));
        doThrow(listenerFailure).when(connectionStatusListenerMock).close();
        assertSame(listenerFailure, assertThrows(ExecutionException.class, deviceConnection::close));
        verify(sessionProviderMock).close();
    }

    /**
     * If both the connection status listener and the session provider fail to close, the listener's failure
     * must not be replaced by the session provider's one: it is propagated with the latter suppressed.
     */
    @Test
    void sessionProviderFailureSuppressedWhenBothCloseStepsFailTest() throws Exception {
        final var listenerFailure = new ExecutionException(new IllegalStateException("commit failed"));
        final var sessionFailure = new IllegalStateException("channel did not terminate");
        doThrow(listenerFailure).when(connectionStatusListenerMock).close();
        doThrow(sessionFailure).when(sessionProviderMock).close();

        assertSame(listenerFailure, assertThrows(ExecutionException.class, deviceConnection::close));
        assertArrayEquals(new Throwable[] {sessionFailure}, listenerFailure.getSuppressed());
    }
}
