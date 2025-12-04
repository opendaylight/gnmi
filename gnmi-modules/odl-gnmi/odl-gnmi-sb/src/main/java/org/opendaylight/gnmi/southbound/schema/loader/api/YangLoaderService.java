/*
 * Copyright (c) 2021 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.gnmi.southbound.schema.loader.api;


import java.util.List;
import org.opendaylight.gnmi.southbound.capabilities.GnmiDeviceCapability;
import org.opendaylight.gnmi.southbound.schema.yangstore.service.YangDataStoreService;

public interface YangLoaderService {

    /**
     * Loads models into YangDataStoreService.
     * @param storeService YangDataStoreService in which the models are stored, useful for loading default models
     * @return loaded models
     * @throws YangLoadException when loading fails
     */
    List<GnmiDeviceCapability> load(YangDataStoreService storeService) throws YangLoadException;

}
