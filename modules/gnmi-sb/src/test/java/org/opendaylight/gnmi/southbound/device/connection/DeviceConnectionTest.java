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

    @Test
    public void closesListenerBeforeSessionProviderTest() throws Exception {
        deviceConnection.close();

        final InOrder inOrder = Mockito.inOrder(connectionStatusListenerMock, sessionProviderMock);
        inOrder.verify(connectionStatusListenerMock).close();
        inOrder.verify(sessionProviderMock).close();
    }

    @Test
    public void sessionProviderClosedWhenListenerCloseFailsTest() throws Exception {
        Mockito.doThrow(new ExecutionException(new IllegalStateException("commit failed")))
                .when(connectionStatusListenerMock).close();

        assertThrows(ExecutionException.class, deviceConnection::close);
        Mockito.verify(sessionProviderMock).close();
    }
}
