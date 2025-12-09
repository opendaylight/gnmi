/*
 * Copyright (c) 2021 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.gnmi.connector.session;

import org.opendaylight.gnmi.connector.configuration.SecurityFactory;
import org.opendaylight.gnmi.connector.gnmi.session.impl.GnmiSessionFactory;
import org.opendaylight.gnmi.connector.security.Security;
import org.opendaylight.gnmi.connector.session.api.SessionManager;

/**
 * This factory provides creation of {@link SessionManager} instance.
 */
public final class SessionManagerFactoryImpl implements SessionManagerFactory {

    private final GnmiSessionFactory gnmiSessionFactory;

    public SessionManagerFactoryImpl(final GnmiSessionFactory gnmiSessionFactory) {

        this.gnmiSessionFactory = gnmiSessionFactory;
    }

    /**
     * Creates new {@link SessionManager} instance.
     * @param security security configuration for session manager - can be created via {@link SecurityFactory}
     * @return instance of {@link SessionManager}
     */
    @Override
    public SessionManager createSessionManager(final Security security) {
        return new SessionManagerImpl(security, gnmiSessionFactory);
    }

}
