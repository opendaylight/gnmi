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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.regex.Pattern;
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
    // Semantic version with up to three numeric parts, without pre-release or build suffixes
    private static final Pattern SEMVER_PATTERN = Pattern.compile("\\d+(\\.\\d+){0,2}");
    // Total order over version strings: by scheme first, then by version within the scheme, then textually
    private static final Comparator<String> VERSION_ORDER = Comparator
            .comparing(SchemaContextHolderImpl::schemeOf)
            .thenComparing(SchemaContextHolderImpl::compareVersions)
            .thenComparing(Comparator.naturalOrder());

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
        // Submodules already selected for their parent, so that they are not looked up and selected again
        final var resolvedSubmodules = new HashMap<String, GnmiYangModel>();
        final SchemaException schemaException = new SchemaException();

        Set<GnmiYangModel> fullModelSet = new HashSet<>();
        try {
            // Read models reported in capabilities
            fullModelSet = readCapabilities(baseCaps, processedModuleNames, schemaException);
            // Get dependencies using native AST extractor
            Set<GnmiDeviceCapability> dependencyCaps = getDependenciesOfModels(fullModelSet, processedModuleNames,
                resolvedSubmodules, schemaException);

            boolean nonComplete = true;
            while (nonComplete) {
                // Read dependency models directly from the generated capabilities
                final Set<GnmiYangModel> dependencyModels = readDependencyModels(
                    dependencyCaps, processedModuleNames, resolvedSubmodules, schemaException);

                // See which models are new, if any, do it again
                final Sets.SetView<GnmiYangModel> newModels = Sets.difference(dependencyModels, fullModelSet);
                dependencyCaps = getDependenciesOfModels(newModels.immutableCopy(), processedModuleNames,
                    resolvedSubmodules, schemaException);
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
        final var name = capability.getName();
        final var requestedVersion = capability.getVersionString().orElse(null);
        if (requestedVersion != null) {
            // Try to find the model stored with version
            final var exactModel = yangDataStoreService.readYangModel(name, requestedVersion)
                    .get(TimeoutUtils.DATASTORE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            if (exactModel.isPresent()) {
                return exactModel;
            }
            LOG.debug("Requested gNMI (capability/dependency of capability) {} was not found with requested version"
                    + " {}.", name, requestedVersion);
        } else {
            LOG.debug("Capability {} has no version, using the highest stored version.", name);
        }

        final var models = yangDataStoreService.readYangModel(name)
                .get(TimeoutUtils.DATASTORE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        if (models.isEmpty()) {
            return Optional.empty();
        }
        if (requestedVersion == null) {
            return getHighestVersion(models);
        }

        final var compatibleModel = findCompatibleModel(models, requestedVersion);
        if (compatibleModel.isPresent()) {
            LOG.info("Model {} was found with version {}, which is backwards compatible with requested version {},"
                    + " using it for schema.", name, versionOf(compatibleModel.orElseThrow()), requestedVersion);
        } else {
            LOG.warn("None of the stored versions {} of model {} is backwards compatible with requested version {}.",
                    models.stream()
                        .map(model -> versionOf(model).isEmpty() ? "(none)" : versionOf(model))
                        .toList(), name, requestedVersion);
        }
        return compatibleModel;
    }

    /**
     * Selects the model with the highest version. {@link #VERSION_ORDER} ranks an empty or unparseable version
     * lowest, so a model stored without a version is selected only when no versioned model is stored.
     *
     * @param models candidate models
     * @return the highest-versioned model, or empty if there are no candidates
     */
    private static Optional<GnmiYangModel> getHighestVersion(final List<GnmiYangModel> models) {
        return models.stream()
            .max(Comparator.comparing(SchemaContextHolderImpl::versionOf, VERSION_ORDER));
    }

    /**
     * Selects, among the models backwards compatible with {@code requestedVersion}, the one closest to it.
     *
     * <p>Versions are compared in the scheme of the requested version. When a revision is requested and a stored
     * model's revision history references the semantic version introduced by that revision (as OpenConfig models
     * do), the semantic version is requested instead, so that a newer revision with a different major version is
     * not accepted. Otherwise, only models stored under a revision are compared, by date. Likewise, when a semantic
     * version is requested and no model stored under a semantic version is compatible, a model stored under
     * a revision is compared by the semantic version that revision references.
     *
     * @param models candidate models
     * @param requestedVersion version requested by the device
     * @return the closest compatible model, or empty if no candidate is compatible
     */
    private Optional<GnmiYangModel> findCompatibleModel(final List<GnmiYangModel> models,
            final String requestedVersion) {
        final var requested = requestedVersion.trim();
        if (!isRevisionDate(requested)) {
            final var compatibleModel = findClosestCompatible(models, requested, SchemaContextHolderImpl::versionOf);
            if (compatibleModel.isPresent()) {
                return compatibleModel;
            }
            return findClosestCompatible(models, requested, this::referencedSemVerOf);
        }
        // A referenced semantic version can only be matched against models stored under a semantic version
        final var referencedSemVer = models.stream()
            .filter(model -> schemeOf(versionOf(model)) == VersionScheme.SEMVER)
            .map(model -> semVerOfRevision(model, requested))
            .flatMap(Optional::stream)
            .min(VERSION_ORDER);
        if (referencedSemVer.isPresent()) {
            LOG.debug("Requested revision {} of model {} corresponds to semantic version {}", requested,
                models.getFirst().getName(), referencedSemVer.orElseThrow());
            // Same as the equivalent semantic version request, including models stored under a revision
            return findCompatibleModel(models, referencedSemVer.orElseThrow());
        }
        return findClosestCompatible(models, requested, SchemaContextHolderImpl::storedRevisionOf);
    }

    private static Optional<GnmiYangModel> findClosestCompatible(final List<GnmiYangModel> models,
            final String requestedVersion, final Function<GnmiYangModel, String> versionFunction) {
        return models.stream()
            .map(model -> new Candidate(model, versionFunction.apply(model)))
            .filter(candidate -> isBackwardsCompatible(candidate.version(), requestedVersion))
            // Ties (same revision under different stored versions) are broken by the stored version
            .min(Comparator.comparing(Candidate::version, VERSION_ORDER)
                .thenComparing(candidate -> versionOf(candidate.model()), VERSION_ORDER))
            .map(Candidate::model);
    }

    /**
     * Returns the revision a model is stored under, or {@code null} for a model stored under anything else. A model
     * stored under a semantic version is not compared by revision date, because a newer OpenConfig revision may change
     * the major version.
     */
    private static String storedRevisionOf(final GnmiYangModel model) {
        final var version = versionOf(model);
        return schemeOf(version) == VersionScheme.REVISION ? version : null;
    }

    /**
     * Returns the semantic version referenced by the revision a model is stored under, e.g. "0.5.0" for
     * openconfig-extensions, which has no openconfig-version and is stored under its latest revision 2020-06-16.
     * Returns {@code null} for a model not stored under a revision or without such a reference.
     */
    private String referencedSemVerOf(final GnmiYangModel model) {
        final var version = versionOf(model);
        return schemeOf(version) == VersionScheme.REVISION ? semVerOfRevision(model, version).orElse(null) : null;
    }

    /**
     * Returns the semantic version referenced by the given revision statement of the model, e.g. "2.4.3" for
     * {@code revision "2019-11-19" { reference "2.4.3"; }}.
     */
    private Optional<String> semVerOfRevision(final GnmiYangModel model, final String revision) {
        final var rootStmt = parseModel(model);
        if (rootStmt == null) {
            return Optional.empty();
        }
        for (final var stmt : rootStmt.statements()) {
            if ("revision".equals(stmt.keyword().identifier())
                    && revision.equals(extractArgumentString(stmt.argument()))) {
                for (final var subStmt : stmt.statements()) {
                    final var reference = "reference".equals(subStmt.keyword().identifier())
                            ? extractArgumentString(subStmt.argument()) : null;
                    if (reference != null && schemeOf(reference) == VersionScheme.SEMVER) {
                        return Optional.of(reference);
                    }
                }
            }
        }
        return Optional.empty();
    }

    private IRStatement parseModel(final GnmiYangModel model) {
        if (Strings.isNullOrEmpty(model.getBody())) {
            return null;
        }
        try {
            return textToIrTransformer.transformSource(makeTextSchemaSource(model)).statement();
        } catch (SourceSyntaxException | IOException e) {
            LOG.debug("Failed to parse model {} with version {}", model.getName(), versionOf(model), e);
            return null;
        }
    }

    private static String versionOf(final GnmiYangModel model) {
        return model.getVersion().getValue().trim();
    }

    private static SemVer storedSemVerOf(final GnmiYangModel model) {
        return parseSemVer(versionOf(model));
    }

    private static VersionScheme schemeOf(final String version) {
        if (isRevisionDate(version)) {
            return parseDate(version) != null ? VersionScheme.REVISION : VersionScheme.UNKNOWN;
        }
        return parseSemVer(version) != null ? VersionScheme.SEMVER : VersionScheme.UNKNOWN;
    }

    /**
     * Compares two version strings of the same scheme.
     *
     * <p>Supported schemes:
     * <ul>
     *   <li>Revision date: {@code yyyy-MM-dd}</li>
     *   <li>Semantic version: {@code X.Y.Z}, {@code X.Y} or {@code X}, missing parts default to 0</li>
     * </ul>
     *
     * <p>Both version strings must use the same scheme (date or semantic version).
     *
     * @return a negative value if {@code v1} is lower than {@code v2},
     *         zero if {@code v1} and {@code v2} are equal or not comparable,
     *         or a positive value if {@code v1} is higher than {@code v2}.
     */
    static int compareVersions(final String v1, final String v2) {
        final var scheme = schemeOf(v1);
        // Don't compare across schemes, treat as not comparable
        if (scheme != schemeOf(v2)) {
            return 0;
        }
        return switch (scheme) {
            case REVISION -> parseDate(v1).compareTo(parseDate(v2));
            case SEMVER -> parseSemVer(v1).compareTo(parseSemVer(v2));
            case UNKNOWN -> 0;
        };
    }

    /**
     * Returns true if a model with {@code foundVersion} can be used in place of {@code requestedVersion}.
     *
     * <p>Both versions must use the same scheme:
     * <ul>
     *   <li>Revision date: compatible when not older than the requested one, since later revisions must be
     *       backwards compatible (RFC 7950, section 11).</li>
     *   <li>Semantic version: compatible when the major version is the same and the version is not lower
     *       than the requested one (OpenConfig semantic versioning).</li>
     * </ul>
     *
     * <p>Major version 0 is treated as a single major version, so 0.9.0 is compatible with 0.5.0, although semantic
     * versioning allows breaking changes in 0.x minor releases. Many OpenConfig models are still 0.x and a stricter
     * rule would reject most of them.
     */
    static boolean isBackwardsCompatible(final String foundVersion, final String requestedVersion) {
        if (foundVersion == null || requestedVersion == null) {
            return false;
        }
        final var scheme = schemeOf(foundVersion);
        if (scheme != schemeOf(requestedVersion)) {
            return false;
        }
        return switch (scheme) {
            case REVISION -> !parseDate(foundVersion).isBefore(parseDate(requestedVersion));
            case SEMVER -> {
                final var found = parseSemVer(foundVersion);
                final var requested = parseSemVer(requestedVersion);
                yield found.major() == requested.major() && found.compareTo(requested) >= 0;
            }
            case UNKNOWN -> false;
        };
    }

    private static boolean isRevisionDate(final String str) {
        return str != null && Revision.STRING_FORMAT_PATTERN.matcher(str).matches();
    }

    private static LocalDate parseDate(final String str) {
        try {
            return LocalDate.parse(str, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Parses a semantic version such as "1.2.3", "1.2" or "1", missing parts default to 0.
     *
     * @return the semantic version, or {@code null} if the string is not one (e.g. "1.0.0-beta")
     */
    private static SemVer parseSemVer(final String version) {
        if (!SEMVER_PATTERN.matcher(version).matches()) {
            return null;
        }
        try {
            return SemVer.valueOf(version);
        } catch (NumberFormatException e) {
            // A part does not fit into int
            return null;
        }
    }

    private Set<GnmiDeviceCapability> getDependenciesOfModels(final Set<GnmiYangModel> toCheck,
            final Set<String> processedModuleNames, final Map<String, GnmiYangModel> resolvedSubmodules,
            final SchemaException schemaException) throws InterruptedException, ExecutionException, TimeoutException {
        final Set<GnmiDeviceCapability> dependencies = new HashSet<>();
        for (final GnmiYangModel model : toCheck) {
            try {
                dependencies.addAll(extractDependenciesFromYang(model, processedModuleNames, resolvedSubmodules));
            } catch (SourceSyntaxException | IOException e) {
                schemaException.addErrorMessage(e.getMessage());
            }
        }
        return dependencies;
    }

    private Set<GnmiDeviceCapability> extractDependenciesFromYang(final GnmiYangModel model,
            final Set<String> processedModuleNames, final Map<String, GnmiYangModel> resolvedSubmodules)
            throws SourceSyntaxException, IOException, InterruptedException, ExecutionException, TimeoutException {
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

                if ("include".equals(keyword) && revision == null && semVer == null && moduleName != null
                        && !processedModuleNames.contains(moduleName) && !resolvedSubmodules.containsKey(moduleName)) {
                    // OpenConfig submodules share the semantic version of their parent, prefer one of the parent's
                    // major version over the highest stored version. Other submodules are versioned independently.
                    final var parentVersion = storedSemVerOf(model);
                    if (parentVersion != null) {
                        preferredSubmodule(moduleName, parentVersion)
                            .ifPresent(submodule -> resolvedSubmodules.put(moduleName, submodule));
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
     * Selects the stored copy of a submodule included without a version by a parent stored under a semantic version:
     * the closest one compatible with the parent's version, otherwise the highest one of the parent's major version,
     * so that a submodule lagging behind its parent is still preferred over another major version.
     *
     * @param submoduleName name of the included submodule
     * @param parentVersion semantic version the parent is stored under
     * @return the selected submodule, or empty to use the highest stored version
     */
    private Optional<GnmiYangModel> preferredSubmodule(final String submoduleName, final SemVer parentVersion)
            throws InterruptedException, ExecutionException, TimeoutException {
        final var submodules = yangDataStoreService.readYangModel(submoduleName)
                .get(TimeoutUtils.DATASTORE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        return findClosestCompatible(submodules, parentVersion.toString(), SchemaContextHolderImpl::versionOf)
                .or(() -> submodules.stream()
                    .filter(submodule -> {
                        final var version = storedSemVerOf(submodule);
                        return version != null && version.major() == parentVersion.major();
                    })
                    .max(Comparator.comparing(SchemaContextHolderImpl::storedSemVerOf)
                        .thenComparing(SchemaContextHolderImpl::versionOf)));
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
            final Set<String> processedModuleNames, final Map<String, GnmiYangModel> resolvedSubmodules,
            final SchemaException schemaException) throws InterruptedException, ExecutionException, TimeoutException {
        final Set<GnmiYangModel> models = new HashSet<>();
        for (GnmiDeviceCapability importedCapability : dependencyCaps) {
            if (!processedModuleNames.contains(importedCapability.getName())) {
                final var resolvedSubmodule = resolvedSubmodules.get(importedCapability.getName());
                final var gnmiYangModel = resolvedSubmodule != null
                    ? Optional.of(resolvedSubmodule) : tryToReadModel(importedCapability);
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

    /**
     * Format of a version string. Only versions of the same scheme are comparable.
     *
     * <p>Declaration order is the order of schemes in {@link #VERSION_ORDER}: semantic version ranks above revision
     * date, matching the version the loader stores for a model carrying both.
     */
    private enum VersionScheme {
        /** Neither a valid revision date nor a dotted numeric version. */
        UNKNOWN,
        /** YANG revision date, {@code yyyy-MM-dd}. */
        REVISION,
        /** Dotted numeric (semantic) version, {@code X.Y.Z}. */
        SEMVER
    }

    /**
     * A candidate model together with the version it is compared by.
     */
    private record Candidate(GnmiYangModel model, String version) {
    }
}
