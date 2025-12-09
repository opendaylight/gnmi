/*
 * Copyright (c) 2021 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.gnmi.connector.gnmi.session.impl;

import io.grpc.ManagedChannel;
import org.opendaylight.gnmi.connector.configuration.SessionConfiguration;
import org.opendaylight.gnmi.connector.gnmi.session.api.GnmiSession;

public interface GnmiSessionFactory {

    GnmiSession createGnmiSession(SessionConfiguration configuration, ManagedChannel channel);
}
