/*
 * Copyright (c) 2021 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.gnmi.commons.util;

import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opendaylight.yangtools.yang.model.api.EffectiveModelContext;
import org.opendaylight.yangtools.yang.model.api.Module;
import org.opendaylight.yangtools.yang.test.util.YangParserTestUtils;

class GnmiDataConverterTest {
    private static final String YANG_MODEL_1_FILE_NAME = "rootModel1.yang";
    private static final String YANG_MODEL_2_FILE_NAME = "rootModel2.yang";

    @Test
    public void findCorrectRootYangModel() {
        EffectiveModelContext schemaContext = prepareSchemaWithMultipleRootContainersWithSameName();
        final Optional<? extends Module> rootModel1
                = DataConverter.findModuleByElement("root-model-1:root-container", schemaContext);
        Assertions.assertTrue(rootModel1.isPresent());
        final Optional<? extends Module> rootModel2
                = DataConverter.findModuleByElement("root-model-2:root-container", schemaContext);
        Assertions.assertTrue(rootModel2.isPresent());
        Assertions.assertNotEquals(rootModel1, rootModel2);

        final Optional<? extends Module> unspecifiedRootModule
                = DataConverter.findModuleByElement("root-container", schemaContext);
        Assertions.assertTrue(unspecifiedRootModule.isEmpty());
    }

    private static EffectiveModelContext prepareSchemaWithMultipleRootContainersWithSameName() {
        return YangParserTestUtils.parseYangResources(GnmiDataConverterTest.class,
            "/test/schema/" + YANG_MODEL_1_FILE_NAME,
            "/test/schema/" + YANG_MODEL_2_FILE_NAME);
    }
}
