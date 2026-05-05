/*
 * Copyright (c) 2021 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.gnmi.southbound.schema.impl;

import com.google.common.collect.Sets;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.eclipse.jdt.annotation.Nullable;
import org.opendaylight.gnmi.southbound.capabilities.GnmiDeviceCapability;
import org.opendaylight.gnmi.southbound.schema.SchemaContextHolder;
import org.opendaylight.gnmi.southbound.schema.yangstore.service.YangDataStoreService;
import org.opendaylight.gnmi.southbound.timeout.TimeoutUtils;
import org.opendaylight.yang.gen.v1.urn.opendaylight.gnmi.yang.storage.rev210331.gnmi.yang.models.GnmiYangModel;
import org.opendaylight.yangtools.concepts.SemVer;
import org.opendaylight.yangtools.yang.common.Revision;
import org.opendaylight.yangtools.yang.ir.IRArgument;
import org.opendaylight.yangtools.yang.ir.IRStatement;
import org.opendaylight.yangtools.yang.model.api.EffectiveModelContext;
import org.opendaylight.yangtools.yang.model.api.source.SourceIdentifier;
import org.opendaylight.yangtools.yang.model.api.source.SourceSyntaxException;
import org.opendaylight.yangtools.yang.model.api.source.YangTextSource;
import org.opendaylight.yangtools.yang.model.spi.source.StringYangTextSource;
import org.opendaylight.yangtools.yang.model.spi.source.YangIRSource;
import org.opendaylight.yangtools.yang.model.spi.source.YangTextToIRSourceTransformer;
import org.opendaylight.yangtools.yang.parser.api.YangParser;
import org.opendaylight.yangtools.yang.parser.api.YangParserException;
import org.opendaylight.yangtools.yang.parser.api.YangParserFactory;
import org.opendaylight.yangtools.yang.parser.api.YangSyntaxErrorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SchemaContextHolderImpl implements SchemaContextHolder {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaContextHolderImpl.class);

    private final YangDataStoreService yangDataStoreService;
    private final Map<CapabilitiesKey, EffectiveModelContext> contextCache;
    private final YangParserFactory yangParserFactory;
    private final YangTextToIRSourceTransformer textToIrTransformer;

    public SchemaContextHolderImpl(final YangDataStoreService yangDataStoreService,
        final @Nullable YangParserFactory yangParserFactory,
        final YangTextToIRSourceTransformer textToIrTransformer) {
        this.yangDataStoreService = yangDataStoreService;
        this.yangParserFactory = yangParserFactory;
        this.contextCache = new ConcurrentHashMap<>();
        this.textToIrTransformer = textToIrTransformer;
    }

    private Set<GnmiYangModel> prepareModelsForSchema(
        final List<GnmiDeviceCapability> baseCaps) throws SchemaException {
        final Set<String> processedModuleNames = new HashSet<>();
        final SchemaException schemaException = new SchemaException();

        Set<GnmiYangModel> fullModelSet = new HashSet<>();
        try {
            // Read models reported in capabilities
            fullModelSet = readCapabilities(baseCaps, processedModuleNames, schemaException);
            // Get dependencies using native AST extractor
            Set<GnmiDeviceCapability> dependencyCaps = getDependenciesOfModels(fullModelSet, schemaException);

            boolean nonComplete = true;
            while (nonComplete) {
                // Read dependency models directly from the generated capabilities
                final Set<GnmiYangModel> dependencyModels = readDependencyModels(
                    dependencyCaps, processedModuleNames, schemaException);

                // See which models are new, if any, do it again
                final Sets.SetView<GnmiYangModel> newModels = Sets.difference(dependencyModels, fullModelSet);
                dependencyCaps = getDependenciesOfModels(newModels.immutableCopy(), schemaException);
                nonComplete = fullModelSet.addAll(newModels);
            }
        } catch (ExecutionException | TimeoutException e) {
            LOG.error("Error reading yang model from datastore", e);
            schemaException.addErrorMessage(e.getMessage());
        } catch (InterruptedException e) {
            LOG.error("Interrupted while reading model from datastore", e);
            Thread.currentThread().interrupt();
            schemaException.addErrorMessage(e.getMessage());
        }

        if (schemaException.getMissingModels().isEmpty() && schemaException.getErrorMessages().isEmpty()) {
            return fullModelSet;
        }
        throw schemaException;
    }

    private Set<GnmiYangModel> readCapabilities(final List<GnmiDeviceCapability> baseCaps,
                                                final Set<String> processedModuleNames,
                                                final SchemaException schemaException)
            throws InterruptedException, ExecutionException, TimeoutException {
        Set<GnmiYangModel> readModels = new HashSet<>();
        for (GnmiDeviceCapability capability : baseCaps) {
            if (!processedModuleNames.contains(capability.getName())) {
                final Optional<GnmiYangModel> readModel = tryToReadModel(capability);
                if (readModel.isPresent()) {
                    readModels.add(readModel.orElseThrow());
                } else {
                    schemaException.addMissingModel(capability);
                }
                processedModuleNames.add(capability.getName());
            }
        }
        return readModels;
    }

    private Optional<GnmiYangModel> tryToReadModel(final GnmiDeviceCapability capability)
        throws InterruptedException, ExecutionException, TimeoutException {
        Optional<GnmiYangModel> readImport;
        Optional<String> capabilityVersion = capability.getVersionString();
        if (capabilityVersion.isPresent()) {
            readImport = yangDataStoreService.readYangModel(capability.getName(), capabilityVersion.orElseThrow())
                .get(TimeoutUtils.DATASTORE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            if (readImport.isEmpty()) {
                LOG.warn("Requested gNMI (capability/dependency of capability) {} was not found with requested version"
                    + " {}.", capability.getName(), capabilityVersion.orElseThrow());
                readImport = yangDataStoreService.readYangModel(capability.getName())
                    .get(TimeoutUtils.DATASTORE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                readImport.ifPresent(gnmiYangModel ->
                    LOG.warn("Model {} was found, but with version {}, since it is the only one"
                            + " present, using it for schema.", capability.getName(),
                        gnmiYangModel.getVersion().getValue()));
            }
        } else {
            readImport = yangDataStoreService.readYangModel(capability.toString())
                .get(TimeoutUtils.DATASTORE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        }

        return readImport;
    }

    private Set<GnmiDeviceCapability> getDependenciesOfModels(final Set<GnmiYangModel> toCheck,
        final SchemaException schemaException) {
        Set<GnmiDeviceCapability> dependencies = new HashSet<>();
        for (GnmiYangModel model : toCheck) {
            try {
                dependencies.addAll(extractDependenciesFromYang(model));
            } catch (SourceSyntaxException | IOException e) {
                schemaException.addErrorMessage(e.getMessage());
            }
        }
        return dependencies;
    }

    private Set<GnmiDeviceCapability> extractDependenciesFromYang(final GnmiYangModel model)
        throws SourceSyntaxException, IOException {
        final Set<GnmiDeviceCapability> deps = new HashSet<>();
        if (model.getBody() == null || model.getBody().isEmpty()) {
            return deps;
        }

        final StringYangTextSource textSource = new StringYangTextSource(
            new SourceIdentifier(model.getName()), model.getBody());

        final YangIRSource irSource = this.textToIrTransformer.transformSource(textSource);
        final IRStatement rootStmt = irSource.statement();

        for (final IRStatement stmt : rootStmt.statements()) {
            final String keyword = stmt.keyword().identifier();

            if ("import".equals(keyword) || "include".equals(keyword)) {
                final String moduleName = getArgumentString(stmt.argument());

                Revision revision = null;
                SemVer semVer = null;

                for (final IRStatement subStmt : stmt.statements()) {
                    final String subKw = subStmt.keyword().identifier();

                    if ("revision-date".equals(subKw)) {
                        final String revStr = getArgumentString(subStmt.argument());
                        if (revStr != null && !revStr.isEmpty()) {
                            revision = Revision.ofNullable(revStr).orElse(null);
                        }
                    }
                    else if ("semantic-version".equals(subKw) || "openconfig-version".equals(subKw)) {
                        final String semVerStr = getArgumentString(subStmt.argument());
                        if (semVerStr != null && !semVerStr.isEmpty()) {
                            try {
                                semVer = SemVer.valueOf(semVerStr);
                            } catch (IllegalArgumentException e) {
                                LOG.warn("Failed to parse SemVer for module import: {}", moduleName);
                            }
                        }
                    }
                }

                if (moduleName != null && !moduleName.isEmpty()) {
                    deps.add(new GnmiDeviceCapability(moduleName, semVer, revision));
                }
            }
        }
        return deps;
    }

    /**
     * Helper to safely extract the raw string from an IRArgument.
     */
    private String getArgumentString(final IRArgument argument) {
        if (argument instanceof IRArgument.Single) {
            return ((IRArgument.Single) argument).string();
        }
        return null;
    }

    private Set<GnmiYangModel> readDependencyModels(final Set<GnmiDeviceCapability> dependencyCaps,
        final Set<String> processedModuleNames,
        final SchemaException schemaException)
        throws InterruptedException, ExecutionException, TimeoutException {
        Set<GnmiYangModel> models = new HashSet<>();
        for (GnmiDeviceCapability importedCapability : dependencyCaps) {
            if (!processedModuleNames.contains(importedCapability.getName())) {
                final Optional<GnmiYangModel> gnmiYangModel = tryToReadModel(importedCapability);
                if (gnmiYangModel.isPresent()) {
                    models.add(gnmiYangModel.orElseThrow());
                } else {
                    schemaException.addMissingModel(importedCapability);
                }
                processedModuleNames.add(importedCapability.getName());
            }
        }
        return models;
    }

    @Override
    public EffectiveModelContext getSchemaContext(final List<GnmiDeviceCapability> capabilities)
        throws SchemaException {
        final CapabilitiesKey key = new CapabilitiesKey(capabilities);
        if (contextCache.containsKey(key)) {
            LOG.info("Schema context for capabilities {} is already cached, reusing", capabilities);
            return contextCache.get(key);
        }

        // Compute schema and add to cache
        final YangParser parser = yangParserFactory.createParser();
        final SchemaException schemaException = new SchemaException();
        boolean success = true;

        final Set<GnmiYangModel> completeCapabilities = prepareModelsForSchema(capabilities);
        for (GnmiYangModel model : completeCapabilities) {
            try {
                parser.addSource(makeTextSchemaSource(model));
            } catch (IOException | YangSyntaxErrorException e) {
                LOG.error("Adding YANG {} to parser failed!", model, e);
                schemaException.addErrorMessage(e.getMessage());
                success = false;
            }
        }

        if (success) {
            try {
                final EffectiveModelContext context = parser.buildEffectiveModel();
                LOG.debug("Schema context created {}", context.getModules());
                contextCache.put(key, context);
                return context;
            } catch (YangParserException e) {
                LOG.error("Parser failed processing schema context", e);
                schemaException.addErrorMessage(e.getMessage());
            }
        }
        throw schemaException;
    }

    private YangTextSource makeTextSchemaSource(final GnmiYangModel model) {
        return new StringYangTextSource(
            new SourceIdentifier(model.getName()), model.getBody());
    }
}