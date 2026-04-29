/*
 * Copyright (c) 2021 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.gnmi.southbound.schema.impl;

import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
import org.opendaylight.yangtools.yang.parser.api.YangParserException;
import org.opendaylight.yangtools.yang.parser.api.YangParserFactory;
import org.opendaylight.yangtools.yang.parser.api.YangSyntaxErrorException;
import org.opendaylight.yangtools.yang.parser.spi.meta.ReactorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SchemaContextHolderImpl implements SchemaContextHolder {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaContextHolderImpl.class);
    // Semantic version with up to three numeric parts, without pre-release or build suffixes
    private static final Pattern SEMVER_PATTERN = Pattern.compile("\\d+(\\.\\d+){0,2}");
    // Total order over version strings by scheme first, then by version within the scheme, then textually
    private static final Comparator<String> VERSION_ORDER = Comparator
            .comparing(SchemaContextHolderImpl::schemeOf)
            .thenComparing(SchemaContextHolderImpl::compareVersions)
            .thenComparing(Comparator.naturalOrder());
    private static final Comparator<GnmiYangModel> HIGHEST_VERSION_FIRST =
        Comparator.comparing(SchemaContextHolderImpl::versionOf, VERSION_ORDER).reversed();
    // Attempts with a lower stored version, after the highest stored versions failed
    private static final int MAX_LOWER_VERSION_ATTEMPTS = 10;
    // Dependencies with a revision-date first, then those pinned to a semantic version, then by name
    private static final Comparator<Dependency> DEPENDENCY_ORDER = Comparator
            .comparing((Dependency dependency) -> dependency.capability().getRevision().isEmpty())
            .thenComparing(dependency -> dependency.capability().getSemVer().isEmpty())
            .thenComparing(dependency -> dependency.capability().getName())
            .thenComparing(dependency -> dependency.capability().toString());

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
     * @param versionless versions used for models requested without a version
     * @return set containing all models for building EffectiveModelContext
     */
    private Set<GnmiYangModel> prepareModelsForSchema(final List<GnmiDeviceCapability> baseCaps,
            final VersionlessModels versionless)
            throws SchemaException, InterruptedException, ExecutionException, TimeoutException {
        // The requirement each model was read for, by model name
        final var processedModules = new HashMap<String, GnmiDeviceCapability>();
        final var schemaException = new SchemaException();

        final var fullModelSet = new HashSet<GnmiYangModel>();
        // Read models reported in capabilities
        fullModelSet.addAll(readCapabilities(baseCaps, processedModules, versionless, schemaException));
        // Read dependencies of the newly read models, until no new model is found
        var newModels = Set.copyOf(fullModelSet);
        while (!newModels.isEmpty()) {
            final var dependencies = getDependenciesOfModels(newModels, schemaException);
            final var dependencyModels = readDependencyModels(dependencies, processedModules, versionless,
                schemaException);
            newModels = Sets.difference(dependencyModels, fullModelSet).immutableCopy();
            fullModelSet.addAll(newModels);
        }

        if (schemaException.getMissingModels().isEmpty() && schemaException.getErrorMessages().isEmpty()) {
            return fullModelSet;
        }
        throw schemaException;
    }

    private Set<GnmiYangModel> readCapabilities(final List<GnmiDeviceCapability> baseCaps,
                                                final Map<String, GnmiDeviceCapability> processedModules,
                                                final VersionlessModels versionless,
                                                final SchemaException schemaException)
            throws InterruptedException, ExecutionException, TimeoutException {
        final var readModels = new HashSet<GnmiYangModel>();
        for (final var capability : baseCaps) {
            if (!processedModules.containsKey(capability.getName())) {
                final var readModel = tryToReadModel(capability, versionless);
                if (readModel.isPresent()) {
                    readModels.add(readModel.orElseThrow());
                } else {
                    schemaException.addMissingModel(capability);
                }
                processedModules.put(capability.getName(), capability);
            }
        }
        return readModels;
    }

    private Optional<GnmiYangModel> tryToReadModel(final GnmiDeviceCapability capability,
            final VersionlessModels versionless)
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
            return versionless.select(name, models);
        }

        final var compatibleModel = findCompatibleModel(models, requestedVersion);
        if (compatibleModel.isPresent()) {
            LOG.debug("Model {} was found with version {}, which is backwards compatible with requested version {},"
                    + " using it for schema.", name, versionOf(compatibleModel.orElseThrow()), requestedVersion);
            return compatibleModel;
        }
        final var storedVersions = models.stream()
            .map(model -> versionOf(model).isEmpty() ? "(none)" : versionOf(model))
            .toList();
        // Nothing to compare with, use it as before, the model may have been stored without a version
        if (models.stream().allMatch(model -> schemeOf(versionOf(model)) == VersionScheme.UNKNOWN)) {
            LOG.warn("Model {} is stored only without a usable version {}, using it for requested version {}. Store "
                    + "it with its version to have the version checked.", name, storedVersions, requestedVersion);
            return getHighestVersion(models);
        }
        LOG.warn("None of the stored versions {} of model {} is backwards compatible with requested version {}. "
                + "Upload the model in the requested version, or force the capabilities of the node to a stored "
                + "version.", storedVersions, name, requestedVersion);
        return Optional.empty();
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
    private static @Nullable String storedRevisionOf(final GnmiYangModel model) {
        final var version = versionOf(model);
        return schemeOf(version) == VersionScheme.REVISION ? version : null;
    }

    /**
     * Returns the semantic version referenced by the revision a model is stored under, e.g. "0.5.0" for
     * openconfig-extensions, which has no openconfig-version and is stored under its latest revision 2020-06-16.
     * Returns {@code null} for a model not stored under a revision or without such a reference.
     */
    private @Nullable String referencedSemVerOf(final GnmiYangModel model) {
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

    /**
     * Returns the latest revision of the model, which is its revision (RFC 7950, section 7.1.9), e.g. "2021-04-06"
     * for a model stored under its openconfig-version.
     */
    private @Nullable Revision latestRevisionOf(final GnmiYangModel model) {
        final var rootStmt = parseModel(model);
        if (rootStmt == null) {
            return null;
        }
        return rootStmt.statements().stream()
            .filter(stmt -> "revision".equals(stmt.keyword().identifier()))
            .map(stmt -> extractArgumentString(stmt.argument()))
            .filter(Objects::nonNull)
            .map(SchemaContextHolderImpl::parseRevision)
            .filter(Objects::nonNull)
            .max(Revision::compareTo)
            .orElse(null);
    }

    private @Nullable IRStatement parseModel(final GnmiYangModel model) {
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

    private static @Nullable SemVer storedSemVerOf(final GnmiYangModel model) {
        return parseSemVer(versionOf(model));
    }

    private static VersionScheme schemeOf(final String version) {
        if (isRevisionDate(version)) {
            return parseRevision(version) != null ? VersionScheme.REVISION : VersionScheme.UNKNOWN;
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
            case REVISION -> parseRevision(v1).compareTo(parseRevision(v2));
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
    static boolean isBackwardsCompatible(final @Nullable String foundVersion,
            final @Nullable String requestedVersion) {
        if (foundVersion == null || requestedVersion == null) {
            return false;
        }
        final var scheme = schemeOf(foundVersion);
        if (scheme != schemeOf(requestedVersion)) {
            return false;
        }
        return switch (scheme) {
            case REVISION -> parseRevision(foundVersion).compareTo(parseRevision(requestedVersion)) >= 0;
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

    private static @Nullable Revision parseRevision(final String str) {
        try {
            return Revision.of(str);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Parses a semantic version such as "1.2.3", "1.2" or "1", missing parts default to 0.
     *
     * @return the semantic version, or {@code null} if the string is not one (e.g. "1.0.0-beta")
     */
    private static @Nullable SemVer parseSemVer(final String version) {
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

    private Set<Dependency> getDependenciesOfModels(final Set<GnmiYangModel> toCheck,
            final SchemaException schemaException) {
        final var dependencies = new HashSet<Dependency>();
        for (final var model : toCheck) {
            try {
                dependencies.addAll(extractDependenciesFromYang(model));
            } catch (SourceSyntaxException | IOException e) {
                schemaException.addErrorMessage(e.getMessage());
            }
        }
        return dependencies;
    }

    private Set<Dependency> extractDependenciesFromYang(final GnmiYangModel model)
            throws SourceSyntaxException, IOException {
        final var deps = new HashSet<Dependency>();
        if (Strings.isNullOrEmpty(model.getBody())) {
            return deps;
        }

        final var irSource = textToIrTransformer.transformSource(makeTextSchemaSource(model));
        final var sourceInfo = irSource.extractSourceInfo();
        final var importVersions = importVersionsOf(irSource.statement());
        for (final var dependency : sourceInfo.imports()) {
            final var name = dependency.name().getLocalName();
            deps.add(new Dependency(new GnmiDeviceCapability(name, importVersions.get(name), dependency.revision()),
                false));
        }
        for (final var include : sourceInfo.includes()) {
            final var name = include.name().getLocalName();
            // A submodule without a revision-date is looked up by the semantic version of its parent
            final var parentVersion = include.revision() == null ? storedSemVerOf(model) : null;
            deps.add(new Dependency(new GnmiDeviceCapability(name, parentVersion, include.revision()), true));
        }
        return deps;
    }

    /**
     * Reads a submodule included without a revision-date by a parent stored under a semantic version. OpenConfig
     * submodules share the semantic version of their parent, so the copy closest compatible with the parent's version
     * is used, otherwise the highest copy of the parent's major version, so that a submodule lagging behind its parent
     * is still used, otherwise a copy stored under a revision that references a compatible version. Another major
     * version is never used, so if copies are stored under semantic versions, but none fits, the submodule is missing.
     * Submodules stored under other versions are not constrained, the highest one is used.
     */
    private Optional<GnmiYangModel> readSubmodule(final String name, final SemVer parentVersion,
            final VersionlessModels versionless) throws InterruptedException, ExecutionException, TimeoutException {
        final var submodules = yangDataStoreService.readYangModel(name)
                .get(TimeoutUtils.DATASTORE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        if (submodules.isEmpty()) {
            return Optional.empty();
        }
        if (submodules.stream().noneMatch(copy -> storedSemVerOf(copy) != null)) {
            return versionless.select(name, submodules);
        }
        final var submodule = findClosestCompatible(submodules, parentVersion.toString(),
                SchemaContextHolderImpl::versionOf)
            .or(() -> submodules.stream()
                .filter(copy -> {
                    final var version = storedSemVerOf(copy);
                    return version != null && version.major() == parentVersion.major();
                })
                .max(Comparator.comparing(SchemaContextHolderImpl::storedSemVerOf)
                    .thenComparing(SchemaContextHolderImpl::versionOf)))
            .or(() -> findClosestCompatible(submodules, parentVersion.toString(), this::referencedSemVerOf));
        if (submodule.isEmpty()) {
            LOG.warn("None of the stored versions {} of submodule {} fits the version {} of its parent.",
                submodules.stream().map(SchemaContextHolderImpl::versionOf).toList(), name, parentVersion);
        }
        return submodule;
    }

    /**
     * Returns the semantic versions imports are pinned to, e.g. 1.0.0 for
     * {@code import dep { oc-ext:openconfig-version "1.0.0"; }}. The source info does not carry them.
     */
    private static Map<String, SemVer> importVersionsOf(final IRStatement rootStmt) {
        final var importVersions = new HashMap<String, SemVer>();
        for (final var stmt : rootStmt.statements()) {
            if (!"import".equals(stmt.keyword().identifier())) {
                continue;
            }
            final var name = extractArgumentString(stmt.argument());
            if (name == null) {
                continue;
            }
            for (final var subStmt : stmt.statements()) {
                final var keyword = subStmt.keyword().identifier();
                if ("openconfig-version".equals(keyword) || "semantic-version".equals(keyword)) {
                    final var version = extractArgumentString(subStmt.argument());
                    final var semVer = version == null ? null : parseSemVer(version);
                    if (semVer != null) {
                        importVersions.put(name, semVer);
                    } else {
                        LOG.warn("Failed to parse the semantic version {} of import {}", version, name);
                    }
                }
            }
        }
        return importVersions;
    }

    /**
     * Helper to safely extract the raw string from an IRArgument.
     */
    private static @Nullable String extractArgumentString(final IRArgument argument) {
        if (argument instanceof IRArgument.Single) {
            return ((IRArgument.Single) argument).string();
        }
        return null;
    }

    /**
     * Reads the dependencies, each model once. If models require the same dependency differently, the requirement with
     * a revision-date is used first, then one pinned to a semantic version, so the result does not depend on the order
     * the models were read in.
     */
    private Set<GnmiYangModel> readDependencyModels(final Set<Dependency> dependencies,
            final Map<String, GnmiDeviceCapability> processedModules, final VersionlessModels versionless,
            final SchemaException schemaException) throws InterruptedException, ExecutionException, TimeoutException {
        final var models = new HashSet<GnmiYangModel>();
        for (final var dependency : dependencies.stream().sorted(DEPENDENCY_ORDER).toList()) {
            final var capability = dependency.capability();
            final var used = processedModules.get(capability.getName());
            if (used == null) {
                final var gnmiYangModel = readDependencyModel(dependency, versionless);
                if (gnmiYangModel.isPresent()) {
                    models.add(gnmiYangModel.orElseThrow());
                } else {
                    schemaException.addMissingModel(capability);
                }
                processedModules.put(capability.getName(), capability);
            } else if (!used.equals(capability)) {
                LOG.debug("Requirement {} is skipped, model {} is already read for {}", capability,
                    capability.getName(), used);
            }
        }
        return models;
    }

    /**
     * Reads an imported or included model. A revision-date requires exactly that revision (RFC 7950, sections 7.1.5.1
     * and 7.1.6), so such a dependency is matched exactly and reported as missing otherwise, instead of failing later
     * in the parser.
     */
    private Optional<GnmiYangModel> readDependencyModel(final Dependency dependency,
            final VersionlessModels versionless) throws InterruptedException, ExecutionException, TimeoutException {
        final var capability = dependency.capability();
        final var revision = capability.getRevision();
        if (revision.isPresent()) {
            return readRevision(capability.getName(), revision.orElseThrow());
        }
        final var parentVersion = capability.getSemVer();
        if (dependency.submodule() && parentVersion.isPresent()) {
            return readSubmodule(capability.getName(), parentVersion.orElseThrow(), versionless);
        }
        return tryToReadModel(capability, versionless);
    }

    /**
     * Reads the model with exactly the given revision. A model with an openconfig-version is stored under it, so if
     * the model is not stored under the revision, the copy whose latest revision is the requested one is used.
     */
    private Optional<GnmiYangModel> readRevision(final String name, final Revision revision)
            throws InterruptedException, ExecutionException, TimeoutException {
        final var exactModel = yangDataStoreService.readYangModel(name, revision.toString())
            .get(TimeoutUtils.DATASTORE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        if (exactModel.isPresent()) {
            return exactModel;
        }
        return yangDataStoreService.readYangModel(name)
            .get(TimeoutUtils.DATASTORE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS).stream()
            .filter(model -> revision.equals(latestRevisionOf(model)))
            .min(HIGHEST_VERSION_FIRST);
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
        try {
            final var context = buildSchemaWithLowerVersions(capabilities);
            LOG.debug("Schema context created {}", context.getModules());
            contextCache.put(key, context);
            return context;
        } catch (ExecutionException | TimeoutException e) {
            LOG.error("Error reading yang model from datastore", e);
            throw schemaError(e);
        } catch (InterruptedException e) {
            LOG.error("Interrupted while reading model from datastore", e);
            Thread.currentThread().interrupt();
            throw schemaError(e);
        }
    }

    /**
     * Builds the schema. A model requested without a version uses the highest stored version, which may not fit
     * the other models. If the schema cannot be built, lower stored versions of such models involved in the failure
     * are tried, one model at a time while the others keep their highest version, dependencies before the models
     * reported by the device. If no attempt succeeds, the failure with the highest versions is reported.
     */
    private EffectiveModelContext buildSchemaWithLowerVersions(final List<GnmiDeviceCapability> capabilities)
            throws SchemaException, InterruptedException, ExecutionException, TimeoutException {
        final var lowerable = new ArrayList<List<GnmiYangModel>>();
        final var firstModels = new HashSet<GnmiYangModel>();
        final SchemaException firstFailure;
        try {
            firstModels.addAll(prepareModelsForSchema(capabilities, new VersionlessModels(null, lowerable)));
            return buildSchema(firstModels);
        } catch (SchemaException e) {
            // A missing model reported by the device is read the same way in every attempt
            if (lowerable.isEmpty() || e.getMissingModels().stream().anyMatch(capabilities::contains)) {
                throw e;
            }
            firstFailure = e;
        }
        final var failedModels = failedModelsOf(firstFailure);
        final var involved = lowerable.stream()
            .filter(versions -> isInvolved(versions.getFirst(), failedModels, firstModels))
            .toList();
        if (involved.isEmpty()) {
            throw firstFailure;
        }
        LOG.info("Schema context cannot be built with the highest stored versions, trying lower versions of {}: {} "
            + "missing {}", involved.stream().map(versions -> versions.getFirst().getName()).toList(),
            reasonOf(firstFailure), firstFailure.getMissingModels());
        var attempts = 0;
        for (final var versions : involved.reversed()) {
            for (final var lower : versions.subList(1, versions.size())) {
                if (++attempts > MAX_LOWER_VERSION_ATTEMPTS) {
                    LOG.info("Schema context cannot be built with lower stored versions after {} attempts",
                        MAX_LOWER_VERSION_ATTEMPTS);
                    throw firstFailure;
                }
                try {
                    final var context = buildSchema(prepareModelsForSchema(capabilities,
                        new VersionlessModels(lower, new ArrayList<>())));
                    LOG.info("Schema context built with {} {} instead of its highest stored version",
                        lower.getName(), versionOf(lower));
                    return context;
                } catch (SchemaException e) {
                    LOG.debug("Schema context cannot be built with {} {}: {} missing {}", lower.getName(),
                        versionOf(lower), reasonOf(e), e.getMissingModels());
                } catch (ExecutionException | TimeoutException | InterruptedException e) {
                    // Report the failure with the highest versions, not just the datastore error
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    LOG.error("Error reading yang model from datastore", e);
                    throw withDatastoreError(firstFailure, e);
                }
            }
        }
        throw firstFailure;
    }

    /**
     * Returns the names of the models the schema failed on, the sources the parser failed on and the missing models.
     */
    private static Set<String> failedModelsOf(final SchemaException schemaException) {
        final var names = new HashSet<String>();
        schemaException.getMissingModels().forEach(missing -> names.add(missing.getName()));
        for (var cause = schemaException.getCause(); cause != null; cause = cause.getCause()) {
            final SourceIdentifier source;
            if (cause instanceof ReactorException reactorException) {
                source = reactorException.getSourceIdentifier();
            } else if (cause instanceof YangSyntaxErrorException syntaxException) {
                source = syntaxException.getSource().orElse(null);
            } else {
                source = null;
            }
            if (source != null) {
                names.add(source.name().getLocalName());
            }
        }
        return names;
    }

    /**
     * Returns whether a lower version of a model can fix the failure, because the model failed, a model that failed
     * imports or includes it, or it imports or includes a model that failed or is missing.
     *
     * @param highest the highest stored version of the model, used in the failed attempt
     * @param failedModels names of the models the schema failed on
     * @param models models of the failed attempt
     */
    private boolean isInvolved(final GnmiYangModel highest, final Set<String> failedModels,
            final Set<GnmiYangModel> models) {
        final var name = highest.getName();
        return failedModels.contains(name)
            || dependencyNamesOf(highest).stream().anyMatch(failedModels::contains)
            || models.stream()
                .filter(model -> failedModels.contains(model.getName()))
                .anyMatch(model -> dependencyNamesOf(model).contains(name));
    }

    private Set<String> dependencyNamesOf(final GnmiYangModel model) {
        try {
            return extractDependenciesFromYang(model).stream()
                .map(dependency -> dependency.capability().getName())
                .collect(Collectors.toSet());
        } catch (SourceSyntaxException | IOException e) {
            LOG.debug("Failed to read the dependencies of model {} with version {}", model.getName(), versionOf(model),
                e);
            return Set.of();
        }
    }

    /**
     * Returns the reason of a failure. The parser exception only says that the sources could not be assembled,
     * the reason is its cause.
     */
    private static String reasonOf(final SchemaException schemaException) {
        final var cause = schemaException.getCause();
        return cause != null ? Throwables.getRootCause(cause).getMessage()
            : schemaException.getErrorMessages().toString();
    }

    private static SchemaException withDatastoreError(final SchemaException schemaException, final Exception cause) {
        schemaException.addErrorMessage(cause.getMessage());
        schemaException.addSuppressed(cause);
        return schemaException;
    }

    private static SchemaException schemaError(final Exception cause) {
        final var schemaException = new SchemaException();
        schemaException.addErrorMessage(cause.getMessage());
        return schemaException;
    }

    private EffectiveModelContext buildSchema(final Set<GnmiYangModel> models) throws SchemaException {
        final var parser = yangParserFactory.createParser();
        final var schemaException = new SchemaException();
        for (final var model : models) {
            try {
                parser.addSource(makeTextSchemaSource(model));
            } catch (IOException | YangSyntaxErrorException e) {
                LOG.debug("Adding YANG {} to parser failed", model.getName(), e);
                schemaException.addErrorMessage(e.getMessage());
                if (schemaException.getCause() == null) {
                    schemaException.initCause(e);
                }
            }
        }
        if (schemaException.getErrorMessages().isEmpty()) {
            try {
                return parser.buildEffectiveModel();
            } catch (YangParserException e) {
                LOG.debug("Parser failed processing schema context", e);
                schemaException.addErrorMessage(e.getMessage());
                schemaException.initCause(e);
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
     * <p>Declaration order is the order of schemes in {@link #VERSION_ORDER}, semantic version ranks above revision
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
     * Versions used for models requested without a version in one attempt to build the schema.
     *
     * @param lower a lower stored version used instead of the highest one, or {@code null} to use the highest versions
     * @param lowerable stored versions of the models with lower versions stored, highest first, in the order they are
     *     selected
     */
    private record VersionlessModels(@Nullable GnmiYangModel lower, List<List<GnmiYangModel>> lowerable) {
        Optional<GnmiYangModel> select(final String name, final List<GnmiYangModel> models) {
            if (lower != null && lower.getName().equals(name)) {
                return Optional.of(lower);
            }
            if (models.size() > 1) {
                lowerable.add(models.stream().sorted(HIGHEST_VERSION_FIRST).toList());
            }
            return getHighestVersion(models);
        }
    }

    /**
     * An imported module or an included submodule.
     */
    private record Dependency(GnmiDeviceCapability capability, boolean submodule) {
    }

    /**
     * A candidate model together with the version it is compared by.
     */
    private record Candidate(GnmiYangModel model, @Nullable String version) {
    }
}
