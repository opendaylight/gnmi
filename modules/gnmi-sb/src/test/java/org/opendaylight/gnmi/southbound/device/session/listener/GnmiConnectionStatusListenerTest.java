/*
 * Copyright (c) 2026 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.gnmi.southbound.device.session.listener;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.ConnectivityState;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.opendaylight.gnmi.connector.session.api.SessionProvider;
import org.opendaylight.mdsal.binding.api.DataBroker;
import org.opendaylight.mdsal.binding.api.WriteTransaction;
import org.opendaylight.mdsal.common.api.CommitInfo;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.NodeId;

/**
 * Verifies {@link GnmiConnectionStatusListener}'s channel-state-change handling: the registered READY
 * callback must fire on every transition to READY (not only the first), {@code
 * copyDeviceStatusReadyToDatastore()} must reject the write when the channel has left READY, and a
 * shut-down callback executor must not let {@link java.util.concurrent.RejectedExecutionException} escape
 * from the gRPC channel's state-change notification.
 */
class GnmiConnectionStatusListenerTest {
    private static final NodeId NODE_ID = new NodeId("listener-test-node");

    private SessionProvider sessionProviderMock;
    private DataBroker dataBrokerMock;
    private ArgumentCaptor<Runnable> stateChangeCaptor;
    private volatile ConnectivityState channelState;

    @BeforeEach
    public void setup() {
        sessionProviderMock = Mockito.mock(SessionProvider.class);
        dataBrokerMock = Mockito.mock(DataBroker.class);
        final WriteTransaction txMock = Mockito.mock(WriteTransaction.class);
        stateChangeCaptor = ArgumentCaptor.forClass(Runnable.class);

        when(sessionProviderMock.getChannelState())
                .thenAnswer(invocation -> channelState);
        doNothing().when(sessionProviderMock).notifyOnStateChangedOneOff(any(), stateChangeCaptor.capture());
        when(dataBrokerMock.newWriteOnlyTransaction())
                .thenAnswer(invocation -> txMock);
        when(txMock.commit())
                .thenAnswer(invocation -> CommitInfo.emptyFluentFuture());
    }

    /**
     * copyDeviceStatusReadyToDatastore() must reject the write with a {@link GnmiConnectionStatusException}
     * carrying the actual channel state whenever the channel is not currently READY.
     */
    @Test
    public void copyDeviceStatusReadyThrowsWhenNotReadyTest() {
        final GnmiConnectionStatusListener listener = createListener(MoreExecutors.newDirectExecutorService());
        channelState = ConnectivityState.CONNECTING;
        listener.init();

        final GnmiConnectionStatusException exception = assertThrows(GnmiConnectionStatusException.class,
                listener::copyDeviceStatusReadyToDatastore);
        assertEquals(ConnectivityState.CONNECTING, exception.getCurrentState());
    }

    /**
     * The registered status callback must fire on every transition into READY, not only the first one:
     * an intervening IDLE transition must not fire it again, but a subsequent READY (reconnect) must.
     */
    @Test
    public void callbackTriggersOnEveryReadyTransitionTest() {
        final GnmiConnectionStatusListener listener = createListener(MoreExecutors.newDirectExecutorService());
        final AtomicInteger callbackCount = new AtomicInteger();
        listener.registerOnStatusCallback(callbackCount::incrementAndGet, ConnectivityState.READY);

        channelState = ConnectivityState.CONNECTING;
        listener.init();
        assertEquals(0, callbackCount.get());

        fireChannelStateChange(ConnectivityState.READY);
        assertEquals(1, callbackCount.get());

        fireChannelStateChange(ConnectivityState.IDLE);
        assertEquals(1, callbackCount.get());

        // The callback stays registered and must fire again when the channel reconnects
        fireChannelStateChange(ConnectivityState.READY);
        assertEquals(2, callbackCount.get());
    }

    /**
     * If the callback executor has already been shut down, the resulting RejectedExecutionException must
     * be caught and the callback silently skipped, instead of propagating out of the gRPC channel's
     * state-change notification.
     */
    @Test
    public void callbackSkippedWhenExecutorIsShutDownTest() {
        final ExecutorService executorService = Executors.newSingleThreadExecutor();
        executorService.shutdown();
        final GnmiConnectionStatusListener listener = createListener(executorService);
        final AtomicInteger callbackCount = new AtomicInteger();
        listener.registerOnStatusCallback(callbackCount::incrementAndGet, ConnectivityState.READY);

        channelState = ConnectivityState.CONNECTING;
        listener.init();

        // RejectedExecutionException of the shut down executor must not propagate to the channel notification
        assertDoesNotThrow(() -> fireChannelStateChange(ConnectivityState.READY));
        assertEquals(0, callbackCount.get());
    }

    private GnmiConnectionStatusListener createListener(final ExecutorService executorService) {
        return new GnmiConnectionStatusListener(sessionProviderMock, dataBrokerMock, NODE_ID, executorService);
    }

    private void fireChannelStateChange(final ConnectivityState newState) {
        channelState = newState;
        stateChangeCaptor.getValue().run();
    }
}
