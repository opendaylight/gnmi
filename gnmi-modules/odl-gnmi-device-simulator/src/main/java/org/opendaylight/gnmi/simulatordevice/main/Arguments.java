/*
 * Copyright (c) 2021 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.gnmi.simulatordevice.main;

import com.beust.jcommander.Parameter;

public class Arguments {

    @Parameter(names = {"-c", "--config-path"}, description = "Path to gNMI json config file. "
            + " (If absent, the default will be used)")
    private String configPath;

    public String getConfigPath() {
        return configPath;
    }
}
