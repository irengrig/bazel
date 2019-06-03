// Copyright 2019 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//

package com.google.devtools.build.lib.bazel.rules.ninja;

import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.packages.BuildType.LABEL;
import static com.google.devtools.build.lib.syntax.Type.BOOLEAN;

import com.google.common.base.Preconditions;
import com.google.devtools.build.lib.analysis.BaseRuleClasses;
import com.google.devtools.build.lib.analysis.RuleDefinition;
import com.google.devtools.build.lib.analysis.RuleDefinitionEnvironment;
import com.google.devtools.build.lib.analysis.config.HostTransition;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.AttributeMap;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClass.Builder.RuleClassType;
import com.google.devtools.build.lib.syntax.Type;
import com.google.devtools.build.lib.syntax.Type.ConversionException;
import com.google.devtools.build.lib.util.FileTypeSet;

public class NinjaBuildRule implements RuleDefinition {
  @Override
  public RuleClass build(RuleClass.Builder builder, RuleDefinitionEnvironment environment) {
    try {
      return builder
          .setOutputToGenfiles()
          .add(attr("src", LABEL).allowedFileTypes(FileTypeSet.ANY_FILE)
              .cfg(HostTransition.createFactory()))
          .add(attr("executable_target", Type.STRING).defaultValue(""))
          .add(
              attr("$is_executable", BOOLEAN)
                  .nonconfigurable("Called from RunCommand.isExecutable, which takes a Target")
                  .value(
                      new Attribute.ComputedDefault() {
                        @Override
                        public Object getDefault(AttributeMap rule) {
                          return true;
                          // return !rule.get("executable_target", Type.STRING).trim().isEmpty();
                        }
                      }))
          .build();
    } catch (ConversionException e) {
      // should not happen
      Preconditions.checkArgument(false, e.getMessage());
      return null;
    }
  }

  @Override
  public Metadata getMetadata() {
    return RuleDefinition.Metadata.builder()
        .name("ninja_build")
        .type(RuleClassType.NORMAL)
        .ancestors(BaseRuleClasses.BaseRule.class)
        .factoryClass(NinjaBuildRuleConfiguredTargetFactory.class)
        .build();
  }
}
