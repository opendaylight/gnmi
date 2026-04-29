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
        // Try to find the model stored with version
        Optional<GnmiYangModel> readImport;
        final Optional<String> capabilityVersion = capability.getVersionString();
        if (capabilityVersion.isPresent()) {
            readImport = yangDataStoreService.readYangModel(capability.getName(), capabilityVersion.orElseThrow())
                    .get(TimeoutUtils.DATASTORE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);

            if (readImport.isEmpty()) {
                LOG.warn("Requested gNMI (capability/dependency of capability) {} was not found with requested version."
                        + " {}.", capability.getName(), capabilityVersion.orElseThrow());
                final Optional<List<GnmiYangModel>> optModelList =
                        yangDataStoreService.readYangModel(capability.getName())
                                .get(TimeoutUtils.DATASTORE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);

                if (optModelList.isPresent() && !optModelList.orElseThrow().isEmpty()) {
                    final List<GnmiYangModel> models = optModelList.orElseThrow();
                    LOG.warn("{} different version(s) for this model were found, fetching the highest version...",
                            models.size());

                    final Optional<GnmiYangModel> highestModel = getHighestVersion(models);
                    final String requestedVersion = capabilityVersion.orElseThrow();
                    if (highestModel.isPresent()
                            && isBackwardsCompatible(highestModel.orElseThrow().getVersion().getValue(),
                            requestedVersion)) {
                        final String foundVersion = highestModel.orElseThrow().getVersion().getValue();
                        LOG.warn("{} is backwards compatible with {}, model is KEPT.", foundVersion, requestedVersion);
                        readImport = highestModel;
                    } else {
                        LOG.warn("No backwards compatible version was found for {}, model is DROPPED.",
                                requestedVersion);
                        readImport = Optional.empty();
                    }
                }
            }
        } else {
            LOG.warn("Capability version is not present, any version should do.");
            final Optional<List<GnmiYangModel>> optModelList =
                    yangDataStoreService.readYangModel(capability.getName())
                            .get(TimeoutUtils.DATASTORE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            if (optModelList.isPresent() && !optModelList.orElseThrow().isEmpty()) {
                readImport = getHighestVersion(optModelList.orElseThrow());
            } else {
                readImport = Optional.empty();
            }
        }

        return readImport;
    }

    /**
     * Selects the model with the highest version, ignoring models that carry no version.
     *
     * <p>If there is no model with a version, but there is a model with no version then the noversion mdodel
     * is returned</p>
     *
     * @param models candidate models, must not be empty
     * @return the highest-versioned model, or empty if every candidate lacks a version
     */
    private static Optional<GnmiYangModel> getHighestVersion(final List<GnmiYangModel> models) {
        Optional<GnmiYangModel> versionOpt = models.stream()
                .filter(model -> model != null
                        && model.getVersion() != null
                        && !Strings.isNullOrEmpty(model.getVersion().getValue()))
                .max((a, b) -> compareVersions(a.getVersion().getValue(), b.getVersion().getValue()));

        if (versionOpt.isEmpty() && !models.isEmpty()) {
            return Optional.of(models.get(models.size() - 1));
        }

        return versionOpt;
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
     * @return a negative value if {@code v1} is lower than {@code v2},
     *         zero if {@code v1} and {@code v2} are equal or not comparable,
     *         or a positive value if {@code v1} is higher than {@code v2}.
     */
    static int compareVersions(final String v1, final String v2) {
        if (v1 == null || v2 == null) {
            return 0;
        }

        final var av = v1.trim();
        final var bv = v2.trim();
        if (av.isEmpty() || bv.isEmpty()) {
            return 0;
        }

        final boolean date1 = isRevisionDate(av);
        final boolean date2 = isRevisionDate(bv);

        // Don't compare across schemes. Treat as "equal"/non-orderable.
        if (date1 != date2) {
            return 0;
        }

        if (date1) {
            final var ad = parseDate(av);
            final var bd = parseDate(bv);
            if (ad == null || bd == null) {
                return 0;
            }
            return ad.compareTo(bd);
        }

        final int[] an = parseDottedNumeric(av);
        final int[] bn = parseDottedNumeric(bv);
        if (an.length == 0 || bn.length == 0) {
            return 0;
        }

        return compareDottedNumeric(an, bn);
    }

    /**
     * Returns true if {@code foundVersion} is strictly higher than {@code requestedVersion},
     * within the same version scheme, meaning it can be treated as backwards compatible.
     */
    static boolean isBackwardsCompatible(final String foundVersion, final String requestedVersion) {
        return compareVersions(foundVersion, requestedVersion) > 0;
    }

    private static boolean isRevisionDate(final String str) {
        return str != null
                && Revision.STRING_FORMAT_PATTERN.matcher(str).matches();
    }

    private static LocalDate parseDate(final String str) {
        try {
            return LocalDate.parse(str, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Parses "1.2.3" (or "1.2", "9", "1.2.3.4") into int segments.
     * Returns an empty array if any segment is non-numeric or empty.
     */
    private static int[] parseDottedNumeric(final String version) {
        final String[] parts = version.split("\\.");
        if (parts.length == 0) {
            return new int[0];
        }

        final int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            final String part = parts[i];
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
    private static int compareDottedNumeric(final int[] v1, final int[] v2) {
        final int maxLength = Math.max(v1.length, v2.length);
        for (int i = 0; i < maxLength; i++) {
            final int ai = i < v1.length ? v1[i] : 0;
            final int bi = i < v2.length ? v2[i] : 0;
            if (ai != bi) {
                return Integer.compare(ai, bi);
            }
        }
        return 0;
    }

    private Set<GnmiDeviceCapability> getDependenciesOfModels(final Set<GnmiYangModel> toCheck,
            final SchemaException schemaException) {
        final Set<GnmiDeviceCapability> dependencies = new HashSet<>();
        for (final GnmiYangModel model : toCheck) {
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

        final var textSource = new StringYangTextSource(
            new SourceIdentifier(model.getName()), model.getBody());

        final var irSource = this.textToIrTransformer.transformSource(textSource);
        final var rootStmt = irSource.statement();

        for (final IRStatement stmt : rootStmt.statements()) {
            final String keyword = stmt.keyword().identifier();

            if ("import".equals(keyword) || "include".equals(keyword)) {
                final String moduleName = extractArgumentString(stmt.argument());

                Revision revision = null;
                SemVer semVer = null;

                for (final IRStatement subStmt : stmt.statements()) {
                    final String subKw = subStmt.keyword().identifier();

                    if ("revision-date".equals(subKw)) {
                        final String revStr = extractArgumentString(subStmt.argument());
                        if (revStr != null && !revStr.isEmpty()) {
                            revision = Revision.ofNullable(revStr).orElse(null);
                        }
                    } else if ("semantic-version".equals(subKw) || "openconfig-version".equals(subKw)) {
                        final String semVerStr = extractArgumentString(subStmt.argument());
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
    private static String extractArgumentString(final IRArgument argument) {
        if (argument instanceof IRArgument.Single) {
            return ((IRArgument.Single) argument).string();
        }
        return null;
    }

    private Set<GnmiYangModel> readDependencyModels(final Set<GnmiDeviceCapability> dependencyCaps,
            final Set<String> processedModuleNames, final SchemaException schemaException)
            throws InterruptedException, ExecutionException, TimeoutException {
        final Set<GnmiYangModel> models = new HashSet<>();
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

    private static YangTextSource makeTextSchemaSource(final GnmiYangModel model) {
        return new StringYangTextSource(
            new SourceIdentifier(model.getName()), model.getBody());
    }
}
