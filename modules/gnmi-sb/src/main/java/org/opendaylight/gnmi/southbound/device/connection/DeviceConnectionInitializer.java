/*
 * Copyright (c) 2021 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.gnmi.southbound.device.connection;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.ConnectivityState;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import org.opendaylight.gnmi.connector.configuration.SessionConfiguration;
import org.opendaylight.gnmi.connector.session.SessionManagerFactory;
import org.opendaylight.gnmi.connector.session.api.SessionManager;
import org.opendaylight.gnmi.connector.session.api.SessionProvider;
import org.opendaylight.gnmi.southbound.device.session.listener.GnmiConnectionStatusException;
import org.opendaylight.gnmi.southbound.device.session.listener.GnmiConnectionStatusListener;
import org.opendaylight.gnmi.southbound.device.session.security.GnmiSecurityProvider;
import org.opendaylight.gnmi.southbound.device.session.security.SessionSecurityException;
import org.opendaylight.mdsal.binding.api.DataBroker;
import org.opendaylight.mdsal.common.api.CommitInfo;
import org.opendaylight.yang.gen.v1.urn.opendaylight.gnmi.topology.rev210316.GnmiNode;
import org.opendaylight.yang.gen.v1.urn.opendaylight.gnmi.topology.rev210316.credentials.Credentials;
import org.opendaylight.yang.gen.v1.urn.opendaylight.gnmi.topology.rev210316.gnmi.connection.parameters.ConnectionParameters;
import org.opendaylight.yang.gen.v1.urn.opendaylight.gnmi.topology.rev210316.security.SecurityChoice;
import org.opendaylight.yang.gen.v1.urn.opendaylight.gnmi.topology.rev210316.security.security.choice.InsecureDebugOnly;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.NodeId;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides initialization of connections for gNMI devices.
 */
public class DeviceConnectionInitializer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(DeviceConnectionInitializer.class);

    private final DataBroker dataBroker;
    private final GnmiSecurityProvider securityProvider;
    private final Map<NodeId, SessionInitializationHolder> activeInitializers;
    private final ExecutorService executorService;
    private final SessionManagerFactory sessionManagerFactory;

    public DeviceConnectionInitializer(final GnmiSecurityProvider securityProvider,
            final SessionManagerFactory sessionManagerFactory,
            final DataBroker dataBroker,
            final ExecutorService executorService) {
        this.securityProvider = securityProvider;
        this.sessionManagerFactory = sessionManagerFactory;
        this.dataBroker = dataBroker;
        this.executorService = executorService;
        this.activeInitializers = new ConcurrentHashMap<>();
    }

    public ListenableFuture<DeviceConnection> initConnection(final Node node) throws SessionSecurityException {
        final GnmiNode gnmiNode = Objects.requireNonNull(node.augmentation(GnmiNode.class),
                "Node must be augmented by gNMI");
        final SessionManager sessionManager =
                sessionManagerFactory.createSessionManager(securityProvider.getSecurity(gnmiNode));
        ConnectionParameters connectionParameters = gnmiNode.getConnectionParameters();
        final InetSocketAddress address = new InetSocketAddress(
                connectionParameters.getHost().getIpAddress().stringValue(),
                connectionParameters.getPort().getValue().intValue());
        final SessionConfiguration configuration = getSessionConfiguration(connectionParameters, address);

        final SessionProvider sessionProvider = sessionManager.createSession(configuration);
        final SessionInitializationHolder initializer =
                new SessionInitializationHolder(sessionProvider, node);
        activeInitializers.put(node.getNodeId(), initializer);
        return initializer.init();
    }

    public boolean isNodeConnecting(final NodeId node) {
        return activeInitializers.containsKey(node);
    }

    public void cancelInitializer(final NodeId nodeId) throws Exception {
        if (activeInitializers.containsKey(nodeId)) {
            activeInitializers.get(nodeId).close();
            activeInitializers.remove(nodeId);
        } else {
            LOG.warn("Initializer of node {} does not exists, can not cancel", nodeId);
        }
    }

    @Override
    public void close() throws Exception {
        for (NodeId activeInitializer : activeInitializers.keySet()) {
            cancelInitializer(activeInitializer);
        }
    }

    public Set<NodeId> getActiveInitializers() {
        return activeInitializers.keySet();
    }

    private SessionConfiguration getSessionConfiguration(final ConnectionParameters connectionParameters,
                                                         final InetSocketAddress address) {
        final boolean usePlainText = useNoTlsConnection(connectionParameters.getSecurityChoice());
        final Credentials credentials = connectionParameters.getCredentials();
        if (credentials != null) {
            return new SessionConfiguration(address, usePlainText, credentials.getUsername(),
                    credentials.getPassword());
        }
        return new SessionConfiguration(address, usePlainText);
    }

    private boolean useNoTlsConnection(final SecurityChoice securityChoice) {
        return securityChoice instanceof InsecureDebugOnly && ((InsecureDebugOnly) securityChoice).getConnectionType()
                .equals(InsecureDebugOnly.ConnectionType.PLAINTEXT);
    }

    class SessionInitializationHolder implements AutoCloseable {

        private GnmiConnectionStatusListener listener;
        private final Node node;
        private final SettableFuture<DeviceConnection> futureManager;
        private final SessionProvider sessionProvider;
        private DeviceConnection deviceConnection;

        SessionInitializationHolder(final SessionProvider sessionProvider, final Node node) {
            this.node = node;
            futureManager = SettableFuture.create();
            this.sessionProvider = sessionProvider;
        }

        public ListenableFuture<DeviceConnection> init() {
            listener = new GnmiConnectionStatusListener(sessionProvider, dataBroker, node.getNodeId(), executorService);
            listener.registerOnStatusCallback(this::onSessionReady, ConnectivityState.READY);
            listener.init();
            return futureManager;
        }

        public synchronized void onSessionReady() {
            if (deviceConnection == null) {
                // When we connect to device for first time, the DeviceConnection is null so create it and pass it to
                // DeviceConnectionManager which will set status READY.
                deviceConnection = new DeviceConnection(sessionProvider, listener, node);
                activeInitializers.remove(node.getNodeId());
                futureManager.set(deviceConnection);
            } else {
                // Reconnect: DeviceConnection already exists, so just refresh status READY in the datastore.
                // Note: the callback stays registered for the whole connection lifetime, so a rapid
                // READY -> IDLE -> READY flap right after the initial connection may write READY before
                // DeviceConnectionManager has finished creating the mountpoint. This is transient:
                // the manager writes READY again once the mountpoint is created, and a failed mountpoint
                // creation overwrites the status with the failure reason (see GnmiNodeListener).
                try {
                    deviceConnection.setDeviceStatusReady().addCallback(new FutureCallback<>() {
                        @Override
                        public void onSuccess(final CommitInfo result) {
                            LOG.debug("Refreshed READY status of node {} in operational datastore",
                                    node.getNodeId());
                        }

                        @Override
                        public void onFailure(final Throwable throwable) {
                            LOG.warn("Failed to write READY status of node {} to operational datastore",
                                    node.getNodeId(), throwable);
                        }
                    }, MoreExecutors.directExecutor());
                } catch (GnmiConnectionStatusException e) {
                    LOG.debug("Node {} left READY again before status could be refreshed; "
                        + "will retry on the next READY transition", node.getNodeId(), e);
                }
            }
        }

        @Override
        public void close() throws Exception {
            LOG.warn("Closing device initializer of node {}", node.getNodeId());
            try {
                listener.close();
            } finally {
                try {
                    sessionProvider.close();
                } finally {
                    futureManager.cancel(true);
                }
            }
        }
    }
}
