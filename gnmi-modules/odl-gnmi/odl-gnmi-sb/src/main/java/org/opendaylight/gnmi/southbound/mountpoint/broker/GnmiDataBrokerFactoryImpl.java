/*
 * Copyright (c) 2021 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.gnmi.southbound.mountpoint.broker;

import com.google.gson.Gson;
import org.opendaylight.gnmi.southbound.device.connection.DeviceConnection;
import org.opendaylight.gnmi.southbound.mountpoint.codecs.GetResponseToNormalizedNodeCodec;
import org.opendaylight.gnmi.southbound.mountpoint.codecs.YangInstanceIdentifierToPathCodec;
import org.opendaylight.gnmi.southbound.mountpoint.codecs.YangInstanceNormToGnmiUpdateCodec;
import org.opendaylight.gnmi.southbound.mountpoint.ops.GnmiGet;
import org.opendaylight.gnmi.southbound.mountpoint.ops.GnmiSet;
import org.opendaylight.gnmi.southbound.mountpoint.requests.GnmiGetRequestFactoryImpl;
import org.opendaylight.gnmi.southbound.mountpoint.requests.GnmiSetRequestFactoryImpl;

public class GnmiDataBrokerFactoryImpl implements GnmiDataBrokerFactory {
    @Override
    public GnmiDataBroker create(DeviceConnection deviceConnection) {

        final Gson gson = new Gson();
        final boolean prefixFirstElement =
                deviceConnection.getConfigurableParameters().getUseModelNamePrefix().orElse(false);

        YangInstanceIdentifierToPathCodec yiiToPathCodec
                = new YangInstanceIdentifierToPathCodec(deviceConnection, prefixFirstElement);
        final GnmiGet getOperation = new GnmiGet(deviceConnection, deviceConnection.getIdentifier(),
                new GetResponseToNormalizedNodeCodec(deviceConnection, gson),
                new GnmiGetRequestFactoryImpl(deviceConnection, yiiToPathCodec));

        final GnmiSet setOperation = new GnmiSet(deviceConnection,
                new GnmiSetRequestFactoryImpl(yiiToPathCodec,
                        new YangInstanceNormToGnmiUpdateCodec(deviceConnection, yiiToPathCodec, gson)),
                deviceConnection.getIdentifier());

        return new GnmiDataBroker(getOperation, setOperation);
    }

}
