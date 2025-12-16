/*
 * Copyright (c) 2021 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.gnmi.connector.session.api;

import io.grpc.ManagedChannel;
import java.util.Map;
import org.opendaylight.gnmi.connector.configuration.SessionConfiguration;

/**
 * This interface is used to retrieve session state.
 */
public interface SessionAdmin {

    /**
     *  Creates unmodifiable copy from channelCache.
     *  @return channelCache Map
     */
    Map<SessionConfiguration, ManagedChannel> getChannelCache();

    /**
     *  Creates unmodifiable copy from openSessionsCounter.
     *  @return openSessionsCounter Map
     */
    Map<SessionConfiguration, Integer> getOpenSessionsCounter();

}
