/*
 * Copyright (c) 2026 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.gnmi.southbound.device.connection;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.opendaylight.gnmi.connector.session.api.SessionProvider;
import org.opendaylight.gnmi.southbound.device.session.listener.GnmiConnectionStatusListener;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.NodeId;
import org.slf4j.Logger;

/**
 * Closes the status listener and session provider of a device connection, in that order (see
 * {@link DeviceConnection#close()} for why the listener must go first).
 *
 * <p>An {@link InterruptedException} from either step is only restored on the current thread once both steps have
 * run, instead of right away. Restoring it eagerly would leave the interrupt flag set going into the next step,
 * causing it to abort immediately (e.g. the session provider's blocking wait for the gRPC channel to drain) even
 * though nothing is actually wrong with that step.</p>
 */
final class ConnectionResourcesCloser {

    private ConnectionResourcesCloser() {
        // Utility class
    }

    static void close(final Logger log, final NodeId nodeId, final GnmiConnectionStatusListener listener,
            final SessionProvider sessionProvider) throws Exception {
        var interrupted = false;
        try {
            try {
                listener.close();
            } catch (ExecutionException | TimeoutException e) {
                log.warn("Failed to close connection status listener for node {}", nodeId, e);
            } catch (InterruptedException e) {
                log.warn("Interrupted while closing connection status listener for node {}", nodeId, e);
                interrupted = true;
            }
            try {
                sessionProvider.close();
            } catch (InterruptedException e) {
                log.warn("Interrupted while closing session provider for node {}", nodeId, e);
                interrupted = true;
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
