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

import de.gematik.refv.lib.exceptions.RefValException;
import de.gematik.refv.lib.fhir_context.entity.ContextConfiguration;
import de.gematik.refv.lib.fhir_context.entity.ValidationModuleIndex;
import de.gematik.refv.lib.validation.boundary.ValidationPackageSelector;
import de.gematik.refv.lib.validation.entity.FhirResource;
import de.gematik.refv.lib.valmodule.entity.ValidationModule;
import de.gematik.refv.valmodule.erp.util.ValidatorSetup;
import java.io.IOException;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ErpModuleExceptionsIT {
  private static final Logger log = LoggerFactory.getLogger(ErpModuleExceptionsIT.class);

  private static ContextConfiguration contextConfiguration;
  private static ValidationModule validationModule;
  private static ValidationPackageSelector validationPackageSelector;

  @BeforeAll
  static void init() {
    initValidationModule();
  }

  @AfterAll
  static void afterAll() {
    try {
      FileUtils.deleteDirectory(
          contextConfiguration.packageLoading().cachePath().getParent().toFile());
    } catch (IOException e) {
      log.warn("Failed to delete directory {}", e.getLocalizedMessage(), e);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        // Profile without Version
        """
                            <Bundle xmlns="http://hl7.org/fhir">
                                <id value="fb16b9fb-eca9-4a64-b257-083ac87c9c9c"/>
                                <meta>
                                    <profile value="https://fhir.kbv.de/StructureDefinition/KBV_PR_ERP_Bundle"/>
                                   \s
                                </meta>
                            </Bundle>"""
      })
  void testProfileWithoutVersionLeadsToDefault(String content) {
    final var fhirResource = FhirResource.fromXml(content);
    final var packagesToLoad =
        Assertions.assertDoesNotThrow(
            () ->
                ValidatorSetup.detectPackagesToLoad(
                    contextConfiguration, validationPackageSelector, fhirResource));
    Assertions.assertFalse(packagesToLoad.isEmpty());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        // No profile given
        """
                            <Bundle xmlns="http://hl7.org/fhir">
                              <id value="fb16b9fb-eca9-4a64-b257-083ac87c9c9c"/>
                            </Bundle>""",
        // Unknown profile
        """
                            <Bundle xmlns="http://hl7.org/fhir">
                                <id value="fb16b9fb-eca9-4a64-b257-083ac87c9c9c"/>
                                <meta>
                                    <profile value="https://bla.bla|1.0.2"/>
                                   \s
                                </meta>
                            </Bundle>"""
      })
  void testInvalidResourcesLeadToException(String content) {
    final var fhirResource = FhirResource.fromXml(content);
    Assertions.assertThrows(
        RefValException.class,
        () ->
            ValidatorSetup.detectPackagesToLoad(
                contextConfiguration, validationPackageSelector, fhirResource));
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
