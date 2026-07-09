/*
 * Copyright (c) 2021 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.gnmi.southbound.device.connection;

import com.google.common.util.concurrent.FluentFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.opendaylight.gnmi.connector.gnmi.session.api.GnmiSession;
import org.opendaylight.gnmi.connector.session.api.SessionProvider;
import org.opendaylight.gnmi.southbound.device.session.listener.GnmiConnectionStatusException;
import org.opendaylight.gnmi.southbound.device.session.listener.GnmiConnectionStatusListener;
import org.opendaylight.gnmi.southbound.device.session.provider.GnmiSessionProvider;
import org.opendaylight.gnmi.southbound.schema.provider.SchemaContextProvider;
import org.opendaylight.mdsal.common.api.CommitInfo;
import org.opendaylight.yang.gen.v1.urn.opendaylight.gnmi.topology.rev210316.GnmiNode;
import org.opendaylight.yang.gen.v1.urn.opendaylight.gnmi.topology.rev210316.gnmi.connection.parameters.ExtensionsParameters;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.NodeId;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.Node;
import org.opendaylight.yangtools.yang.model.api.EffectiveModelContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds gNMI session of one connected gNMI device.
 */
public class DeviceConnection implements GnmiSessionProvider, SchemaContextProvider, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(DeviceConnection.class);

    private final SessionProvider sessionProvider;
    private final GnmiConnectionStatusListener connectionStatusListener;
    private final Node node;
    private final ConfigurableParameters configurableParameters;
    private EffectiveModelContext schemaContext;

    public DeviceConnection(final SessionProvider sessionProvider,
                            final GnmiConnectionStatusListener connectionStatusListener, final Node node) {
        this.sessionProvider = sessionProvider;
        this.connectionStatusListener = connectionStatusListener;
        this.node = node;
        final ExtensionsParameters extensionsParameters = resolveExtensionsParameters();
        configurableParameters = new ConfigurableParameters(extensionsParameters);
    }

    private ExtensionsParameters resolveExtensionsParameters() {
        final GnmiNode gnmiNode = node.augmentation(GnmiNode.class);

        return gnmiNode == null ? null : gnmiNode.getExtensionsParameters();
    }

    public FluentFuture<CommitInfo> setDeviceStatusReady() throws GnmiConnectionStatusException {
        return connectionStatusListener.copyDeviceStatusReadyToDatastore();
    }

    public ConfigurableParameters getConfigurableParameters() {
        return configurableParameters;
    }

    public EffectiveModelContext getSchemaContext() {
        return schemaContext;
    }

    public void setSchemaContext(final EffectiveModelContext schemaContext) {
        this.schemaContext = schemaContext;
    }

    public GnmiSession getGnmiSession() {
        return sessionProvider.getGnmiSession();
    }

    @Override
    public void close() throws Exception {
        boolean interrupted = false;
        try {
            connectionStatusListener.close();
        } catch (ExecutionException | TimeoutException e) {
            LOG.warn("Failed to close connection status listener for node {}", getIdentifier(), e);
        } catch (InterruptedException e) {
            LOG.warn("Interrupted while closing connection status listener for node {}", getIdentifier(), e);
            interrupted = true;
        }
        try {
            sessionProvider.close();
        } catch (InterruptedException e) {
            LOG.warn("Interrupted while closing session provider for node {}", getIdentifier(), e);
            interrupted = true;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public NodeId getIdentifier() {
        return node.getNodeId();
    }
}
