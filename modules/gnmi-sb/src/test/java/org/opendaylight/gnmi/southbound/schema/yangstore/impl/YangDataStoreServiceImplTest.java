/*
 * Copyright (c) 2026 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.gnmi.southbound.schema.yangstore.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opendaylight.gnmi.southbound.timeout.TimeoutUtils;
import org.opendaylight.mdsal.binding.dom.adapter.test.AbstractConcurrentDataBrokerTest;

class YangDataStoreServiceImplTest extends AbstractConcurrentDataBrokerTest {
    private YangDataStoreServiceImpl service;

    YangDataStoreServiceImplTest() {
        super(true);
    }

    @BeforeEach
    void before() throws Exception {
        setup();
        service = new YangDataStoreServiceImpl(getDataBroker());
    }

    /**
     * Nothing is stored, not even the gnmi-yang-models container: the name lookup returns an empty list.
     */
    @Test
    void emptyTest() throws Exception {
        assertEquals(List.of(), readVersions("foo"));
    }

    /**
     * foo is stored at 1.0.0 and 2.0.0 and bar at 1.0.0: the name lookup returns both versions of foo only.
     */
    @Test
    void allVersionsTest() throws Exception {
        service.addYangModel("foo", "1.0.0", "module foo {}").get(TimeoutUtils.DATASTORE_TIMEOUT_MILLIS,
            TimeUnit.MILLISECONDS);
        service.addYangModel("foo", "2.0.0", "module foo {}").get(TimeoutUtils.DATASTORE_TIMEOUT_MILLIS,
            TimeUnit.MILLISECONDS);
        service.addYangModel("bar", "1.0.0", "module bar {}").get(TimeoutUtils.DATASTORE_TIMEOUT_MILLIS,
            TimeUnit.MILLISECONDS);

        assertEquals(List.of("1.0.0", "2.0.0"), readVersions("foo"));
    }

    private List<String> readVersions(final String name) throws Exception {
        return service.readYangModel(name).get(TimeoutUtils.DATASTORE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS).stream()
            .map(model -> model.getVersion().getValue())
            .sorted()
            .toList();
    }
}
