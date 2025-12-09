/*
 * Copyright (c) 2021 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.gnmi.connector.gnoi.session.api;

import org.opendaylight.gnmi.connector.gnoi.invokers.api.GnoiCertInvoker;
import org.opendaylight.gnmi.connector.gnoi.invokers.api.GnoiFileInvoker;
import org.opendaylight.gnmi.connector.gnoi.invokers.api.GnoiOsInvoker;
import org.opendaylight.gnmi.connector.gnoi.invokers.api.GnoiSystemInvoker;

public interface GnoiSession {


    GnoiCertInvoker getCertInvoker();

    GnoiFileInvoker getFileInvoker();

    GnoiSystemInvoker getSystemInvoker();

    GnoiOsInvoker getOsInvoker();

}
