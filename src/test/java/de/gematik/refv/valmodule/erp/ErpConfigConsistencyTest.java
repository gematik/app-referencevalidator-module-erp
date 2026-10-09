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
package de.gematik.refv.valmodule.erp;

import de.gematik.refv.lib.config_parser.boundary.YAMLMapperProvider;
import de.gematik.refv.lib.valmodule.boundary.ModuleConfigImporter;
import de.gematik.refv.valmodule.api.entity.ValidationModuleManifest;
import java.io.FileInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Consistency tests for the ERP module's {@code config.yaml}. */
class ErpConfigConsistencyTest {

  private static ValidationModuleManifest config;

  @BeforeAll
  static void loadConfig() throws Exception {
    final var moduleConfigImporter = new ModuleConfigImporter(YAMLMapperProvider.getMapper());
    final var modulePath = Path.of("src/main/resources/");
    try (var is = new FileInputStream(modulePath.resolve("META-INF", "config.yaml").toFile())) {
      final var moduleConfig = moduleConfigImporter.importFromStream(is);
      Assertions.assertTrue(moduleConfig.isPresent());
      config = moduleConfig.get();
    }
  }

  // =========================================================================
  // Reference integrity
  // =========================================================================

  @Nested
  class ReferenceIntegrity {
    @Test
    void allDependencyListRefsInVersionBindings_existInDependencyLists() {
      final Set<String> knownComponents = config.packageGroups().keySet();
      final List<String> dangling = new ArrayList<>();

      config
          .profileFamilies()
          .forEach(
              (groupName, group) ->
                  Objects.requireNonNull(group.versions())
                      .forEach(
                          (version, binding) ->
                              binding
                                  .groups()
                                  .forEach(
                                      entry -> {
                                        if (!knownComponents.contains(entry.packageGroupName())) {
                                          dangling.add(
                                              groupName
                                                  + " / "
                                                  + version
                                                  + " -> "
                                                  + entry.packageGroupName());
                                        }
                                      })));

      Assertions.assertTrue(
          dangling.isEmpty(),
          "Dangling component references found in profileGroups:\n  "
              + String.join("\n  ", dangling));
    }

    @Test
    void allMessageTransformRefs_existInMessageTransformTemplates() {
      final Set<String> knownTemplates =
          Objects.requireNonNull(config.messageTransformations()).keySet();
      final List<String> dangling = new ArrayList<>();

      config
          .packageGroups()
          .forEach(
              (compName, def) ->
                  Objects.requireNonNull(def.messageTransformations())
                      .forEach(
                          templateRef -> {
                            if (!knownTemplates.contains(templateRef)) {
                              dangling.add(compName + " -> " + templateRef);
                            }
                          }));

      Assertions.assertTrue(
          dangling.isEmpty(),
          "Dangling messageTransform references found in dependencyLists:\n  "
              + String.join("\n  ", dangling));
    }
  }

  // =========================================================================
  // validFrom / validTill well-formedness
  // =========================================================================

  @Nested
  class DateWellFormedness {

    @Test
    void noComponent_hasNullValidFrom() {
      final List<String> missing = new ArrayList<>();
      config
          .profileFamilies()
          .forEach(
              (name, def) -> {
                if (def.versions() == null) {
                  missing.add(name);
                }
              });
      Assertions.assertTrue(
          missing.isEmpty(),
          "Components without validFrom cannot be indexed temporally:\n  "
              + String.join("\n  ", missing));
    }

    @Test
    void noComponent_hasValidTillBeforeValidFrom() {
      final List<String> invalid = new ArrayList<>();
      config
          .profileFamilies()
          .forEach(
              (name, versions) ->
                  Objects.requireNonNull(versions.versions())
                      .forEach(
                          (s, profileVersion) ->
                              profileVersion
                                  .groups()
                                  .forEach(
                                      validityPeriod -> {
                                        if (validityPeriod.validFrom() != null
                                            && validityPeriod.validTill() != null
                                            && validityPeriod
                                                .validTill()
                                                .isBefore(validityPeriod.validFrom())) {
                                          invalid.add(
                                              name
                                                  + " ("
                                                  + validityPeriod.validFrom()
                                                  + " > "
                                                  + validityPeriod.validTill()
                                                  + ")");
                                        }
                                      })));
      Assertions.assertTrue(
          invalid.isEmpty(),
          "Components whose validTill is before validFrom:\n  " + String.join("\n  ", invalid));
    }
  }
}
