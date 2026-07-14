/*
 * Copyright (c) 2026 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.gnmi.southbound.device.connection;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.opendaylight.gnmi.connector.session.api.SessionProvider;
import org.opendaylight.gnmi.southbound.device.session.listener.GnmiConnectionStatusListener;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.NodeId;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.Node;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.NodeBuilder;

/**
 * Verifies the close ordering of {@link DeviceConnection}: the connection status listener must be closed
 * before the session provider, and a failure while closing the listener must not skip closing the session
 * provider (which would otherwise leak the gRPC channel).
 */
class DeviceConnectionTest {
    private static final Node NODE = new NodeBuilder().setNodeId(new NodeId("close-order-node")).build();

    private SessionProvider sessionProviderMock;
    private GnmiConnectionStatusListener connectionStatusListenerMock;
    private DeviceConnection deviceConnection;

    @BeforeEach
    public void setup() {
        sessionProviderMock = Mockito.mock(SessionProvider.class);
        connectionStatusListenerMock = Mockito.mock(GnmiConnectionStatusListener.class);
        deviceConnection = new DeviceConnection(sessionProviderMock, connectionStatusListenerMock, NODE);
    }

    /**
     * A normal close must close the connection status listener before the session provider, so that
     * tearing down the gRPC channel cannot trigger a final state-change notification into a listener
     * that is still active and about to have its operational state deleted out from under it.
     */
    @Test
    public void closesListenerBeforeSessionProviderTest() throws Exception {
        deviceConnection.close();

        final InOrder inOrder = Mockito.inOrder(connectionStatusListenerMock, sessionProviderMock);
        inOrder.verify(connectionStatusListenerMock).close();
        inOrder.verify(sessionProviderMock).close();
    }

    /**
     * If closing the connection status listener throws, the session provider must still be closed
     * (otherwise the gRPC channel leaks); the listener's exception is rethrown to the caller once
     * cleanup has run.
     */
    @Test
    public void sessionProviderClosedWhenListenerCloseFailsTest() throws Exception {
        Mockito.doThrow(new ExecutionException(new IllegalStateException("commit failed")))
                .when(connectionStatusListenerMock).close();

        assertThrows(ExecutionException.class, deviceConnection::close);
        Mockito.verify(sessionProviderMock).close();
    }
}
