/*
 * Copyright (c) 2021 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.gnmi.simulatordevice.yang;

import com.google.common.io.CharStreams;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import org.apache.commons.lang3.StringUtils;
import org.opendaylight.gnmi.commons.util.DataConverter;
import org.opendaylight.mdsal.dom.api.DOMDataTreeChangeListener;
import org.opendaylight.mdsal.dom.api.DOMSchemaService;
import org.opendaylight.mdsal.dom.spi.FixedDOMSchemaService;
import org.opendaylight.mdsal.dom.spi.store.DOMStoreReadTransaction;
import org.opendaylight.mdsal.dom.spi.store.DOMStoreReadWriteTransaction;
import org.opendaylight.mdsal.dom.spi.store.DOMStoreThreePhaseCommitCohort;
import org.opendaylight.mdsal.dom.store.inmemory.InMemoryDOMStore;
import org.opendaylight.mdsal.dom.store.inmemory.InMemoryDOMStoreConfigProperties;
import org.opendaylight.mdsal.dom.store.inmemory.dagger.InMemoryDOMStoreFactoryModule;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier;
import org.opendaylight.yangtools.yang.data.api.schema.NormalizedNode;
import org.opendaylight.yangtools.yang.data.tree.api.DataTreeConfiguration;
import org.opendaylight.yangtools.yang.data.tree.dagger.ReferenceDataTreeFactoryModule;
import org.opendaylight.yangtools.yang.model.api.EffectiveModelContext;
import org.opendaylight.yangtools.yang.model.api.SchemaContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("UnstableApiUsage")
public class YangDataService {
    private static final Logger LOG = LoggerFactory.getLogger(YangDataService.class);

    private final EnumMap<DatastoreType, InMemoryDOMStore> datastoreMap;

    public YangDataService(final EffectiveModelContext schemaContext, final String initialConfigDataPath,
                           final String initialStateDataPath) throws IOException {
        this.datastoreMap = createDatastoreMap(schemaContext);
        initializeDataStore(initialConfigDataPath, initialStateDataPath, schemaContext);
    }

    public Optional<NormalizedNode> readDataByPath(final DatastoreType datastoreType,
                                                         final YangInstanceIdentifier path) {
        try (DOMStoreReadTransaction tx = datastoreMap.get(datastoreType).newReadOnlyTransaction()) {
            return tx.read(path).get();
        } catch (final ExecutionException e) {
            LOG.error("Unable to fetch data from DataStore", e);
        } catch (InterruptedException e) {
            LOG.error("Interrupted while fetching data from DataStore", e);
            Thread.currentThread().interrupt();
        }
        return Optional.empty();
    }

    public void mergeDataByPath(final DatastoreType datastoreType, final YangInstanceIdentifier path,
                                final NormalizedNode node) {
        modifyDataByPath(datastoreType, path, node, ModificationType.MERGE);
    }

    public void writeDataByPath(final DatastoreType datastoreType, final YangInstanceIdentifier path,
                                final NormalizedNode node) {
        modifyDataByPath(datastoreType, path, node, ModificationType.WRITE);
    }

    public void deleteDataByPath(final DatastoreType datastoreType, final YangInstanceIdentifier path) {
        modifyDataByPath(datastoreType, path, null, ModificationType.DELETE);
    }

    private void modifyDataByPath(final DatastoreType datastoreType, final YangInstanceIdentifier path,
                                  final NormalizedNode node, final ModificationType modificationType) {
        try (DOMStoreReadWriteTransaction tx = datastoreMap.get(datastoreType).newReadWriteTransaction()) {
            if (modificationType == ModificationType.WRITE) {
                tx.write(path, node);
            } else if (modificationType == ModificationType.DELETE) {
                tx.delete(path);
            } else {
                tx.merge(path, node);
            }
            final DOMStoreThreePhaseCommitCohort tpcc = tx.ready();
            tpcc.canCommit().get();
            tpcc.preCommit().get();
            tpcc.commit().get();
        } catch (final ExecutionException exception) {
            LOG.error("Unable to commit changes to datastore", exception);
            throw new RuntimeException("Unable to commit changes to datastore", exception);
        } catch (InterruptedException e) {
            LOG.error("Interrupted while committing changes to datastore", e);
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while committing changes to datastore", e);
        }
    }

    private void initializeDataStore(final String initialConfigDataPath, final String initialStateDataPath,
                                     final EffectiveModelContext schemaContext)
            throws IOException {
        // Init config data
        if (StringUtils.isNotEmpty(initialConfigDataPath)) {
            final InputStream configFile = Files.newInputStream(Path.of(initialConfigDataPath));
            initDataTree(configFile, DatastoreType.CONFIGURATION, schemaContext);
        }
        if (StringUtils.isNotEmpty(initialStateDataPath)) {
            final InputStream configFile = Files.newInputStream(Path.of(initialStateDataPath));
            initDataTree(configFile, DatastoreType.STATE, schemaContext);
        }
    }

    private void initDataTree(final InputStream stream, final DatastoreType datastoreType,
                              final EffectiveModelContext schemaContext) {
        try {
            // read json configuration from file
            final String configJson = CharStreams.toString(new InputStreamReader(stream, StandardCharsets.UTF_8));
            /*
             ROOT YII because we are writing one/or multiple top-level elements
              (interfaces,alarms, components ...).
            */
            final NormalizedNode node =
                    DataConverter.nodeFromJsonString(YangInstanceIdentifier.of(), configJson, schemaContext);
            /*
             If QName of parsed node is a root node (SchemaContext.NAME), that means we parsed multiple
              top-level element, in that case we need to write this node on ROOT YII.
            */
            if (node.name().getNodeType().equals(SchemaContext.NAME)) {
                writeDataByPath(datastoreType, YangInstanceIdentifier.of(), node);
            // Else we parsed only one top-level element, in that case we write this node on it's identifier.
            } else {
                writeDataByPath(datastoreType, YangInstanceIdentifier.of(node.name().getNodeType()), node);
            }

        } catch (final IOException e) {
            LOG.error("Unable to get data from stream {}", stream, e);
        }
    }

    public void registerListener(final DatastoreType datastoreType, final YangInstanceIdentifier identifier,
                                 final DOMDataTreeChangeListener listener) {
        datastoreMap.get(datastoreType).registerTreeChangeListener(identifier, listener);
    }

    private EnumMap<DatastoreType, InMemoryDOMStore> createDatastoreMap(EffectiveModelContext schemaContext) {
        final var storeFactory = InMemoryDOMStoreFactoryModule.provideInMemoryDOMStoreFactory(
                ReferenceDataTreeFactoryModule.provideDataTreeFactory());
        final DOMSchemaService schemaService = new FixedDOMSchemaService(schemaContext);
        final InMemoryDOMStoreConfigProperties properties = InMemoryDOMStoreConfigProperties.builder()
                .maxDataChangeExecutorPoolSize(20)
                .maxDataChangeExecutorQueueSize(20)
                .maxDataChangeListenerQueueSize(20)
                .debugTransactions(false)
                .build();

        final EnumMap<DatastoreType, InMemoryDOMStore> dataStoreTypeMap = new EnumMap<>(DatastoreType.class);
        dataStoreTypeMap.put(DatastoreType.CONFIGURATION, storeFactory.create(DatastoreType.CONFIGURATION.getName(),
                DataTreeConfiguration.DEFAULT_CONFIGURATION, properties, schemaService));
        dataStoreTypeMap.put(DatastoreType.OPERATIONAL, storeFactory.create(DatastoreType.OPERATIONAL.getName(),
                DataTreeConfiguration.DEFAULT_OPERATIONAL, properties, schemaService));
        dataStoreTypeMap.put(DatastoreType.STATE, storeFactory.create(DatastoreType.STATE.getName(),
                DataTreeConfiguration.DEFAULT_OPERATIONAL, properties, schemaService));
        return dataStoreTypeMap;
    }

    private enum ModificationType {
        WRITE,
        MERGE,
        DELETE
    }
}
