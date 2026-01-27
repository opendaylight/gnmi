/*
 * Copyright (c) 2021 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.gnmi.southbound.schema.impl;

import com.google.common.base.Strings;
import com.google.common.collect.Sets;
import com.google.common.io.CharSource;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;
import org.opendaylight.gnmi.southbound.capabilities.GnmiDeviceCapability;
import org.opendaylight.gnmi.southbound.schema.SchemaContextHolder;
import org.opendaylight.gnmi.southbound.schema.yangstore.service.YangDataStoreService;
import org.opendaylight.gnmi.southbound.timeout.TimeoutUtils;
import org.opendaylight.yang.gen.v1.urn.opendaylight.gnmi.yang.storage.rev210331.gnmi.yang.models.GnmiYangModel;
import org.opendaylight.yangtools.yang.model.api.EffectiveModelContext;
import org.opendaylight.yangtools.yang.model.api.source.SourceDependency;
import org.opendaylight.yangtools.yang.model.api.source.SourceIdentifier;
import org.opendaylight.yangtools.yang.model.spi.source.DelegatedYangTextSource;
import org.opendaylight.yangtools.yang.model.spi.source.SourceInfo;
import org.opendaylight.yangtools.yang.parser.api.YangSyntaxErrorException;
import org.opendaylight.yangtools.yang.parser.rfc7950.repo.YangIRSourceInfoExtractor;
import org.opendaylight.yangtools.yang.parser.rfc7950.repo.YangStatementStreamSource;
import org.opendaylight.yangtools.yang.parser.spi.meta.ReactorException;
import org.opendaylight.yangtools.yang.parser.stmt.reactor.CrossSourceStatementReactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SchemaContextHolderImpl implements SchemaContextHolder {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaContextHolderImpl.class);

    private final YangDataStoreService yangDataStoreService;
    private final Map<CapabilitiesKey, EffectiveModelContext> contextCache;
    private final CrossSourceStatementReactor yangReactor;

    public SchemaContextHolderImpl(final YangDataStoreService yangDataStoreService,
                                   final @Nullable CrossSourceStatementReactor reactor) {
        this.yangDataStoreService = yangDataStoreService;
        this.yangReactor = reactor;
        this.contextCache = new ConcurrentHashMap<>();
    }

    /**
     * Based on imports/includes statements of yang models reported by gNMI CapabilityResponse, tries to deduce and
     * read all necessary models so that EffectiveModelContext creation does not fail on missing module dependencies.
     * This step is necessary for cases when device reports non complete set of models, for example, module
     * in Capability response imports/includes another module which is not present in Capability response.
     *
     * @param baseCaps capabilities on which to perform the resolution
     * @return set containing all models for building EffectiveModelContext
     */
    private Set<GnmiYangModel> prepareModelsForSchema(
            final List<GnmiDeviceCapability> baseCaps) throws SchemaException {
        final Set<String> processedModuleNames = new HashSet<>();
        final SchemaException schemaException = new SchemaException();

        Set<GnmiYangModel> fullModelSet = new HashSet<>();
        try {
            // Read models reported in capabilities
            fullModelSet = readCapabilities(baseCaps, processedModuleNames, schemaException);
            // Get dependencies of models reported in capabilities
            Set<SourceInfo> dependencyInfos = getDependenciesOfModels(fullModelSet, schemaException);

            boolean nonComplete = true;
            while (nonComplete) {
                // Read dependency models
                Set<GnmiYangModel> dependencyModels = new HashSet<>();
                for (SourceInfo dependencyInfo : dependencyInfos) {
                    final Set<GnmiYangModel> gnmiYangModels =
                            readDependencyModels(dependencyInfo, processedModuleNames, schemaException);
                    dependencyModels.addAll(gnmiYangModels);
                }
                // See which models are new, if any, do it again
                final Sets.SetView<GnmiYangModel> newModels = Sets.difference(dependencyModels, fullModelSet);
                dependencyInfos = getDependenciesOfModels(newModels.immutableCopy(), schemaException);
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
        // Try to find the model stored with version
        Optional<GnmiYangModel> readImport;
        Optional<String> capabilityVersion = capability.getVersionString();
        if (capabilityVersion.isPresent()) {
            readImport = yangDataStoreService.readYangModel(capability.getName(), capabilityVersion.orElseThrow())
                    .get(TimeoutUtils.DATASTORE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);

            if (readImport.isEmpty()) {
                LOG.warn("Requested gNMI (capability/dependency of capability) {} was not found with requested version."
                        + " {}.", capability.getName(), capabilityVersion.orElseThrow());
                Optional<List<GnmiYangModel>> optModelList = yangDataStoreService.readYangModel(capability.getName())
                        .get(TimeoutUtils.DATASTORE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);

                if (optModelList.isPresent() && !optModelList.orElseThrow().isEmpty()) {
                    List<GnmiYangModel> models = optModelList.orElseThrow();
                    LOG.warn("{} different versions for for this model was found, fetching the highest version...",
                            models.size());

                    GnmiYangModel highestModel = getHighestVersion(models);
                    String requestedVersion = capabilityVersion.orElseThrow();
                    String foundVersion = highestModel.getVersion().getValue();
                    if (!isBackwardsCompatiable(foundVersion, requestedVersion)) {
                        LOG.warn("{} is NOT backwards compatible with {}, model is DROPPED.",
                                foundVersion,
                                requestedVersion);
                        readImport = Optional.empty();
                    }
                    else {
                        LOG.warn("{} is backwards compatible with {}, model is KEPT.", foundVersion, requestedVersion);
                        readImport = Optional.of(highestModel);
                    }
                }
            }
        } else {
            LOG.warn("Capability version is not present, any version should do.");
            Optional<List<GnmiYangModel>> optModelList = yangDataStoreService.readYangModel(capability.toString())
                    .get(TimeoutUtils.DATASTORE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            if (optModelList.isPresent() && !optModelList.orElseThrow().isEmpty()) {
                readImport = Optional.of(optModelList.orElseThrow().get(0));
            }
            else {
                readImport = Optional.empty();
            }
        }

        return readImport;
    }

    private GnmiYangModel getHighestVersion(List<GnmiYangModel> models) {
        List<GnmiYangModel> sortedModels = models.stream()
                .filter(model -> !Strings.isNullOrEmpty(model.getVersion().getValue()))
                .sorted((a, b) -> compareVersions(b.getVersion().getValue(), a.getVersion().getValue()))
                .toList();
        return sortedModels.get(0);
    }

    /**
     * Compares two version strings of the same scheme.
     *
     * <p>Supported schemes:
     * <ul>
     *   <li>Revision date: {@code yyyy-MM-dd}</li>
     *   <li>Dotted numeric: {@code X.Y.Z} (any number of dot-separated numeric segments)</li>
     * </ul>
     *
     * <p>Both version strings must use the same scheme (date or dotted numeric).
     *
     * @return a negative value if {@code a} is lower than {@code b},
     *         zero if {@code a} and {@code b} are equal or not comparable,
     *         or a positive value if {@code a} is higher than {@code b}.
     */
    public static int compareVersions(String v1, String v2) {
        if (v1 == null || v2 == null) {
            return 0;
        }

        String av = v1.trim();
        String bv = v2.trim();
        if (av.isEmpty() || bv.isEmpty()) {
            return 0;
        }

        boolean date1 = isRevisionDate(av);
        boolean date2 = isRevisionDate(bv);

        // Don't compare across schemes. Treat as "equal"/non-orderable.
        if (date1 != date2) {
            return 0;
        }

        if (date1) {
            LocalDate ad = parseDate(av);
            LocalDate bd = parseDate(bv);
            if (ad == null || bd == null) {
                return 0;
            }
            return ad.compareTo(bd);
        }

        int[] an = parseDottedNumeric(av);
        int[] bn = parseDottedNumeric(bv);
        if (an.length == 0 || bn.length == 0) {
            return 0;
        }

        return compareDottedNumeric(an, bn);
    }

    /**
     * Returns true if {@code foundVersion} is strictly higher than {@code requestedVersion},
     * within the same version scheme, meaning it can be treated as backwards compatible.
     */
    public static boolean isBackwardsCompatiable(String foundVersion, String requestedVersion) {
        return compareVersions(foundVersion, requestedVersion) > 0;
    }


    private static boolean isRevisionDate(String str) {
        // strict yyyy-MM-dd
        return str.length() == 10
                && str.charAt(4) == '-'
                && str.charAt(7) == '-'
                && Character.isDigit(str.charAt(0))
                && Character.isDigit(str.charAt(1))
                && Character.isDigit(str.charAt(2))
                && Character.isDigit(str.charAt(3))
                && Character.isDigit(str.charAt(5))
                && Character.isDigit(str.charAt(6))
                && Character.isDigit(str.charAt(8))
                && Character.isDigit(str.charAt(9));
    }

    private static LocalDate parseDate(String str) {
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE;

        try {
            return LocalDate.parse(str, dateTimeFormatter);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Parses "1.2.3" (or "1.2", "9", "1.2.3.4") into int segments.
     * Returns null if any segment is non-numeric or empty.
     */
    private static int[] parseDottedNumeric(String version) {
        String[] parts = version.split("\\.");
        if (parts.length == 0) {
            return new int[0];
        }

        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty()) {
                return new int[0];
            }
            // Reject leading '+'/'-' and non-digits.
            for (int k = 0; k < part.length(); k++) {
                if (!Character.isDigit(part.charAt(k))) {
                    return new int[0];
                }
            }
            try {
                out[i] = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                return new int[0]; // overflow etc.
            }
        }
        return out;
    }

    /**
     * Lexicographic compare with implicit zero-extension.
     * 1.2 == 1.2.0, 1.2 < 1.2.1, 2 > 1.9.9
     */
    private static int compareDottedNumeric(int[] v1, int[] v2) {
        int tmp = Math.max(v1.length, v2.length);
        for (int i = 0; i < tmp; i++) {
            int ai = i < v1.length ? v1[i] : 0;
            int bi = i < v2.length ? v2[i] : 0;
            if (ai != bi) {
                return Integer.compare(ai, bi);
            }
        }
        return 0;
    }

    private Set<SourceInfo> getDependenciesOfModels(final Set<GnmiYangModel> toCheck,
                                                                 final SchemaException schemaException) {
        Set<SourceInfo> dependencies = new HashSet<>();
        for (GnmiYangModel model : toCheck) {
            try {
                final SourceInfo dependencyInfo = YangIRSourceInfoExtractor.forYangText(
                        makeTextSchemaSource(model));
                dependencies.add(dependencyInfo);
            } catch (IOException | YangSyntaxErrorException e) {
                schemaException.addErrorMessage(e.getMessage());
            }
        }
        return dependencies;
    }

    private Set<GnmiYangModel> readDependencyModels(final SourceInfo dependencyInfo,
                                                    final Set<String> processedModuleNames,
                                                    final SchemaException schemaException)
            throws InterruptedException, ExecutionException, TimeoutException {
        Set<GnmiYangModel> models = new HashSet<>();
        for (SourceDependency.Include moduleImport : dependencyInfo.includes()) {
            if (!processedModuleNames.contains(moduleImport.name().getLocalName())) {
                final GnmiDeviceCapability importedCapability = new GnmiDeviceCapability(
                        moduleImport.name().getLocalName(), null,
                        moduleImport.revision());
                final Optional<GnmiYangModel> gnmiYangModel = tryToReadModel(importedCapability);
                if (gnmiYangModel.isPresent()) {
                    models.add(gnmiYangModel.orElseThrow());
                } else {
                    schemaException.addMissingModel(importedCapability);
                }
                processedModuleNames.add(moduleImport.name().getLocalName());
            }
        }
        for (SourceDependency.Import moduleImport : dependencyInfo.imports()) {
            if (!processedModuleNames.contains(moduleImport.name().getLocalName())) {
                final GnmiDeviceCapability importedCapability = new GnmiDeviceCapability(
                        moduleImport.name().getLocalName(), null,
                        moduleImport.revision());
                final Optional<GnmiYangModel> gnmiYangModel = tryToReadModel(importedCapability);
                if (gnmiYangModel.isPresent()) {
                    models.add(gnmiYangModel.orElseThrow());
                } else {
                    schemaException.addMissingModel(importedCapability);
                }
                processedModuleNames.add(moduleImport.name().getLocalName());
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
        final CrossSourceStatementReactor.BuildAction buildAction = yangReactor.newBuild();
        final SchemaException schemaException = new SchemaException();
        boolean success = true;
        final Set<GnmiYangModel> completeCapabilities = prepareModelsForSchema(capabilities);
        for (GnmiYangModel model : completeCapabilities) {
            try {
                buildAction.addSource(YangStatementStreamSource.create(makeTextSchemaSource(model)));
            } catch (IOException | YangSyntaxErrorException e) {
                LOG.error("Adding YANG {} to reactor failed!", model, e);
                schemaException.addErrorMessage(e.getMessage());
                success = false;
            }
        }
        if (success) {
            try {
                final EffectiveModelContext context = buildAction.buildEffective();
                LOG.debug("Schema context created {}", context.getModules());
                contextCache.put(key, context);
                return context;
            } catch (ReactorException e) {
                LOG.error("Reactor failed processing schema context", e);
                schemaException.addErrorMessage(e.getMessage());
            }
        }
        throw schemaException;
    }

    private DelegatedYangTextSource makeTextSchemaSource(final GnmiYangModel model) {
        return new DelegatedYangTextSource(
                new SourceIdentifier(model.getName()), bodyCharSource(model.getBody()));

    }

    private CharSource bodyCharSource(final String yangBody) {
        return CharSource.wrap(yangBody);
    }

}
