/*
 * Copyright (c) 2021 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.gnmi.connector.gnoi.session.impl;

import io.grpc.Channel;
import java.util.Objects;
import org.opendaylight.gnmi.connector.gnoi.invokers.api.GnoiCertInvoker;
import org.opendaylight.gnmi.connector.gnoi.invokers.api.GnoiFileInvoker;
import org.opendaylight.gnmi.connector.gnoi.invokers.api.GnoiOsInvoker;
import org.opendaylight.gnmi.connector.gnoi.invokers.api.GnoiSystemInvoker;
import org.opendaylight.gnmi.connector.gnoi.invokers.impl.GnoiCertInvokerImpl;
import org.opendaylight.gnmi.connector.gnoi.invokers.impl.GnoiFileInvokerImpl;
import org.opendaylight.gnmi.connector.gnoi.invokers.impl.GnoiOsInvokerImpl;
import org.opendaylight.gnmi.connector.gnoi.invokers.impl.GnoiSystemInvokerImpl;
import org.opendaylight.gnmi.connector.gnoi.session.api.GnoiSession;

public class GnoiSessionImpl implements GnoiSession {


    private final GnoiCertInvoker certInvoker;
    private final GnoiFileInvoker fileInvoker;
    private final GnoiSystemInvoker systemInvoker;
    private final GnoiOsInvoker osInvoker;

    public GnoiSessionImpl(final Channel channel) {
        Objects.requireNonNull(channel);
        this.certInvoker = GnoiCertInvokerImpl.fromChannel(channel);
        this.fileInvoker = GnoiFileInvokerImpl.fromChannel(channel);
        this.systemInvoker = GnoiSystemInvokerImpl.fromChannel(channel);
        this.osInvoker = GnoiOsInvokerImpl.fromChannel(channel);
    }

    @Override
    public GnoiCertInvoker getCertInvoker() {
        return certInvoker;
    }

    @Override
    public GnoiFileInvoker getFileInvoker() {
        return fileInvoker;
    }

    @Override
    public GnoiSystemInvoker getSystemInvoker() {
        return systemInvoker;
    }

    @Override
    public GnoiOsInvoker getOsInvoker() {
        return osInvoker;
    }
}
