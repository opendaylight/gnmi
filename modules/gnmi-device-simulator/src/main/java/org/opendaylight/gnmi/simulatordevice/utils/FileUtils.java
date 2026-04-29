/*
 * Copyright (c) 2021 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.gnmi.simulatordevice.utils;

import java.io.InputStream;

public final class FileUtils {

    private FileUtils() {
        //Utility class
    }

    public static InputStream getResourceAsStream(final String resource) {
        return FileUtils.class.getClassLoader().getResourceAsStream(resource);
    }

}
