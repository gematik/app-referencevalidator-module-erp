/*-
 * #%L
 * Validation Module for E-Rezept
 * %%
 * Copyright (C) 2024 - 2026 gematik GmbH
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * *******
 *
 * For additional notes and disclaimer from gematik and in case of changes
 * by gematik, find details in the "Readme" file.
 * #L%
 */
package de.gematik.refv.valmodule.erp.util;

import de.gematik.refv.lib.config_parser.boundary.YAMLMapperProvider;
import de.gematik.refv.lib.exceptions.InitializationException;
import de.gematik.refv.lib.exceptions.ParsingException;
import de.gematik.refv.lib.fhir_context.entity.ContextConfiguration;
import de.gematik.refv.lib.package_resolver.boundary.PackageResolverFactory;
import de.gematik.refv.lib.package_resolver.entity.LocalArchive;
import de.gematik.refv.lib.validation.boundary.ValidationPackageSelector;
import de.gematik.refv.lib.validation.entity.FhirResource;
import de.gematik.refv.lib.validation.entity.ValidationOptions;
import de.gematik.refv.lib.validation.entity.ValidationRequest;
import de.gematik.refv.lib.valmodule.boundary.ModuleConfigImporter;
import de.gematik.refv.lib.valmodule.entity.ValidationModule;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

public final class ValidatorSetup {
  private ValidatorSetup() {}

  public static void preloadCache(
      @NonNull ContextConfiguration contextConfiguration,
      @NonNull ValidationModule validationModule) {
    final var packageResolver =
        PackageResolverFactory.withConfiguration(contextConfiguration.packageLoading());
    packageResolver.loadCore(contextConfiguration.fhirRelease());
    packageResolver.resolvePath(validationModule.modulePath().resolve("package"));
  }

  public static @NonNull List<String> detectPackagesToLoad(
      @NonNull ContextConfiguration contextConfiguration,
      @NonNull ValidationPackageSelector validationPackageSelector,
      @NonNull FhirResource resource) {
    // Core packages must always be loaded
    final List<String> packagesToLoad =
        new ArrayList<>(contextConfiguration.fhirRelease().packages());
    packagesToLoad.addAll(
        validationPackageSelector.getPackagesForResource(
            resource,
            contextConfiguration.fhirRelease(),
            ValidationOptions.defaultConfiguration()));
    // Translate all the tgz coordinate packages into remote ones (they are already in cache)
    return packagesToLoad.stream()
        .map(
            s ->
                s.contains(LocalArchive.PACKAGE_SEPARATOR)
                        && s.endsWith(LocalArchive.ARCHIVE_PACKAGE_EXTENSION)
                    ? LocalArchive.parse(s).coordinates()
                    : s)
        .toList();
  }

  public static @NonNull List<String> detectPackagesToLoad(
      @NonNull ContextConfiguration contextConfiguration,
      @NonNull ValidationPackageSelector validationPackageSelector,
      @NonNull Path resource) {
    // Core packages must always be loaded
    final List<String> packagesToLoad =
        new ArrayList<>(contextConfiguration.fhirRelease().packages());
    packagesToLoad.addAll(
        validationPackageSelector.getPackagesForResource(
            createResource(resource),
            contextConfiguration.fhirRelease(),
            ValidationOptions.defaultConfiguration()));
    // Translate all the tgz coordinate packages into remote ones (they are already in cache)
    return packagesToLoad.stream()
        .map(
            s ->
                s.contains(LocalArchive.PACKAGE_SEPARATOR)
                        && s.endsWith(LocalArchive.ARCHIVE_PACKAGE_EXTENSION)
                    ? LocalArchive.parse(s).coordinates()
                    : s)
        .toList();
  }

  public static ValidationModule getValidationModule() throws Exception {
    final var moduleConfigImporter = new ModuleConfigImporter(YAMLMapperProvider.getMapper());
    final var modulePath = Path.of("src/main/resources/");
    try (var is = new FileInputStream(modulePath.resolve("META-INF", "config.yaml").toFile())) {
      final var moduleConfig = moduleConfigImporter.importFromStream(is);
      if (moduleConfig.isEmpty()) {
        throw new InitializationException("Failed to parse the module configuration from file");
      }

      return new ValidationModule(moduleConfig.get(), modulePath);
    }
  }

  public static @NonNull FhirResource createResource(@NonNull Path filePath) {
    Objects.requireNonNull(filePath);

    try {
      final var contentType = Files.probeContentType(filePath);
      if (Objects.isNull(contentType)) {
        throw new ParsingException("Failed to detect the type of file for " + filePath);
      }

      if (contentType.toLowerCase(Locale.ROOT).contains("json")) {
        return FhirResource.fromJson(filePath);
      } else if (contentType.toLowerCase(Locale.ROOT).contains("xml")) {
        return FhirResource.fromXml(filePath);
      }

      throw new ParsingException(filePath + " has an unsupported file type: " + contentType);
    } catch (IOException _) {
      throw new ParsingException("Could not detect the type of file");
    }
  }

  public static @NonNull ValidationRequest createRequest(@NonNull Path filePath) {
    return new ValidationRequest(createResource(filePath));
  }
}
