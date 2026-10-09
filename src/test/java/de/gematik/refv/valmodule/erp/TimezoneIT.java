/*-
 * #%L
 * valmodule-erp
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
import de.gematik.refv.lib.fhir_context.entity.IssueSeverity;
import de.gematik.refv.lib.fhir_context.entity.ValidationModuleIndex;
import de.gematik.refv.lib.validation.boundary.ValidationPackageSelector;
import de.gematik.refv.lib.validation.boundary.ValidatorFactory;
import de.gematik.refv.lib.validation.entity.ValidationOptions;
import de.gematik.refv.lib.valmodule.entity.ValidationModule;
import de.gematik.refv.valmodule.erp.util.ValidFolderDetector;
import de.gematik.refv.valmodule.erp.util.ValidatorSetup;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedList;
import java.util.List;
import java.util.TimeZone;
import java.util.stream.Stream;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Execution(ExecutionMode.SAME_THREAD)
class TimezoneIT {
  private static final Logger log = LoggerFactory.getLogger(TimezoneIT.class);
  private static final String DIR = "Timezones";
  private static final TimeZone originalSystemTimezone = TimeZone.getDefault();

  private static ContextConfiguration contextConfiguration;
  private static ValidationModule validationModule;
  private static ValidationPackageSelector validationPackageSelector;

  @BeforeAll
  static void setup() {
    initValidationModule();
  }

  @AfterAll
  static void afterAll() {
    TimeZone.setDefault(originalSystemTimezone);
    try {
      FileUtils.deleteDirectory(
          contextConfiguration.packageLoading().cachePath().getParent().toFile());
    } catch (IOException e) {
      log.warn("Failed to delete directory {}", e.getLocalizedMessage(), e);
    }
  }

  @TestFactory
  Stream<DynamicTest> testValidation() throws IOException {

    var allFiles =
        Files.walk(Paths.get(String.format("src/test/resources/%s", DIR)))
            .filter(path -> path.toString().endsWith(String.format(".%s", "xml")))
            .toList();
    var timeZones = List.of("Asia/Taipei", "Europe/Berlin", "America/Nome");

    var allDynamicTests = new LinkedList<DynamicTest>();
    for (var file : allFiles) {
      for (var timezone : timeZones) {
        allDynamicTests.add(
            DynamicTest.dynamicTest(file + " in " + timezone, () -> validateFile(file, timezone)));
      }
    }
    return allDynamicTests.stream();
  }

  protected void validateFile(Path path, String timezone) {
    TimeZone.setDefault(TimeZone.getTimeZone(timezone));
    boolean isValidExpected = ValidFolderDetector.isInValidFolder(path);
    try {
      final var packagesToLoad =
          ValidatorSetup.detectPackagesToLoad(
              contextConfiguration, validationPackageSelector, path);
      final var validationRequest = ValidatorSetup.createRequest(path);
      final var validator =
          ValidatorFactory.withCustomPackages(contextConfiguration, packagesToLoad);
      var result = validator.validate(validationRequest, ValidationOptions.defaultConfiguration());
      BaseProfileIntegrationTest.printResultMessages(result);

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
        Assertions.fail("Failed to validate file in Timezone " + timezone, e);
      }
      log.info("Validation threw exception: {} ", e.getLocalizedMessage(), e);
    }
  }

  private static void initValidationModule() {
    if (validationModule != null) {
      log.info("Validation Module has been already initialized");
      return;
    }

    try {
      contextConfiguration = BaseProfileIntegrationTest.getContextConfiguration();
      validationModule = ValidatorSetup.getValidationModule();
      ValidatorSetup.preloadCache(contextConfiguration, validationModule);
      final var validationModuleIndex = new ValidationModuleIndex(validationModule);
      validationPackageSelector = new ValidationPackageSelector(validationModuleIndex);
    } catch (Exception e) {
      Assertions.fail("Failed to initialize Validation Module", e);
    }
  }
}
