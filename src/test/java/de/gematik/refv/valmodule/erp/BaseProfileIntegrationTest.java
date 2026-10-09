/*-
 * #%L
 * valmodule-base
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
package de.gematik.refv.valmodule.erp;

import de.gematik.refv.lib.fhir_context.entity.ContextConfiguration;
import de.gematik.refv.lib.fhir_context.entity.DisplayBehaviorConfiguration;
import de.gematik.refv.lib.fhir_context.entity.FhirRelease;
import de.gematik.refv.lib.fhir_context.entity.IssueSeverity;
import de.gematik.refv.lib.fhir_context.entity.PackageDownloadConfiguration;
import de.gematik.refv.lib.fhir_context.entity.TerminologyConfiguration;
import de.gematik.refv.lib.fhir_context.entity.ValidationModuleIndex;
import de.gematik.refv.lib.fhir_context.entity.ValidationPolicyConfiguration;
import de.gematik.refv.lib.validation.boundary.ValidationPackageSelector;
import de.gematik.refv.lib.validation.boundary.Validator;
import de.gematik.refv.lib.validation.boundary.ValidatorFactory;
import de.gematik.refv.lib.validation.entity.ValidationOptions;
import de.gematik.refv.lib.validation.entity.ValidationResult;
import de.gematik.refv.lib.valmodule.entity.ValidationModule;
import de.gematik.refv.valmodule.erp.util.ValidFolderDetector;
import de.gematik.refv.valmodule.erp.util.ValidatorSetup;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.util.Strings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Execution(ExecutionMode.CONCURRENT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class BaseProfileIntegrationTest {
  private static final Logger log = LoggerFactory.getLogger(BaseProfileIntegrationTest.class);
  private static final ConcurrentMap<String, Validator> validators = new ConcurrentHashMap<>();

  private static ContextConfiguration contextConfiguration;
  private ValidationPackageSelector validationPackageSelector;

  @AfterAll
  static void afterAll() {
    try {
      FileUtils.deleteDirectory(
          contextConfiguration.packageLoading().cachePath().getParent().toFile());
    } catch (IOException e) {
      log.warn("Failed to delete directory {}", e.getLocalizedMessage(), e);
    }
  }

  public static void printResultMessages(ValidationResult validationResult) {
    log.info("# Validation Messages");
    validationResult
        .messages()
        .forEach(message -> log.info("## [{}] {}", message.severity(), message.messageContent()));
  }

  public static ContextConfiguration getContextConfiguration() throws IOException {
    final var tempDir = Files.createTempDirectory("refv-test");
    return new ContextConfiguration(
        FhirRelease.asR4(),
        "de",
        DisplayBehaviorConfiguration.defaultConfiguration(),
        new PackageDownloadConfiguration(
            PackageDownloadConfiguration.RemoteDownloadPolicy.DISALLOWED, tempDir.resolve("cache")),
        TerminologyConfiguration.defaultConfiguration(),
        new ValidationPolicyConfiguration(
            ValidationPolicyConfiguration.ValidationLevelPolicy.SHOW_WARNINGS_AND_ERRORS,
            ValidationPolicyConfiguration.UnknownCodeSystemsPolicy.ALLOWED,
            ValidationPolicyConfiguration.ExampleCodeSystemsUsagePolicy.DISALLOWED,
            ValidationPolicyConfiguration.ExampleUrlsUsagePolicy.ALLOWED,
            ValidationPolicyConfiguration.ExampleRestReferencesPolicy.ALLOWED,
            ValidationPolicyConfiguration.RecursiveModePolicy.DISALLOWED,
            ValidationPolicyConfiguration.AnyExtensionPolicy.ALLOWED));
  }

  protected void initFhirEngine(String selectedModuleName) {
    try {
      contextConfiguration = getContextConfiguration();
      ValidationModule validationModule = ValidatorSetup.getValidationModule();
      ValidatorSetup.preloadCache(contextConfiguration, validationModule);
      final var validationModuleIndex = new ValidationModuleIndex(validationModule);
      validationPackageSelector = new ValidationPackageSelector(validationModuleIndex);
    } catch (Exception e) {
      Assertions.fail("Failed to initialize validationController for " + selectedModuleName, e);
    }
  }

  protected void validateFile(Path path) {
    boolean isValidExpected = ValidFolderDetector.isInValidFolder(path);
    try {
      final var packagesToLoad =
          ValidatorSetup.detectPackagesToLoad(
              contextConfiguration, validationPackageSelector, path);
      final var hash = computeHash(packagesToLoad);
      final Validator validator;
      if (validators.containsKey(hash)) {
        validator = validators.get(hash);
      } else {
        validator = ValidatorFactory.withCustomPackages(contextConfiguration, packagesToLoad);
        validators.put(hash, validator);
      }
      final var validationRequest = ValidatorSetup.createRequest(path);
      var result = validator.validate(validationRequest, ValidationOptions.defaultConfiguration());
      printResultMessages(result);
      Assertions.assertEquals(
          isValidExpected,
          result.messages().stream()
              .noneMatch(
                  resultMessage ->
                      IssueSeverity.ERROR.equals(resultMessage.severity())
                          || IssueSeverity.FATAL.equals(resultMessage.severity())),
          path + " failed:\n" + result);
    } catch (Exception e) {
      if (isValidExpected) {
        Assertions.fail("Validation failed: " + e.getLocalizedMessage(), e);
      }
      log.info("Validation threw exception: {} ", e.getLocalizedMessage(), e);
    }
  }

  protected Stream<DynamicTest> testValidationBase(String folder) throws IOException {
    return testValidationBase(folder, "xml");
  }

  protected Stream<DynamicTest> testValidationBase(String folder, String fileExtension)
      throws IOException {
    return Files.walk(Paths.get(String.format("src/test/resources/%s", folder)))
        .filter(path -> path.toString().endsWith(String.format(".%s", fileExtension)))
        .map(f -> DynamicTest.dynamicTest(f.toString(), () -> validateFile(f)));
  }

  private String computeHash(List<String> packageToLoad) {
    return DigestUtils.sha256Hex(Strings.join(packageToLoad, ',').getBytes(StandardCharsets.UTF_8));
  }
}
