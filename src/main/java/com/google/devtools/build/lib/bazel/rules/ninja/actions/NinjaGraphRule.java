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

package com.google.devtools.build.lib.bazel.rules.ninja.actions;

import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.packages.BuildType.LABEL;
import static com.google.devtools.build.lib.packages.BuildType.LABEL_LIST;
import static com.google.devtools.build.lib.packages.Type.STRING;

import com.google.devtools.build.lib.analysis.BaseRuleClasses;
import com.google.devtools.build.lib.analysis.RuleDefinition;
import com.google.devtools.build.lib.analysis.RuleDefinitionEnvironment;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClass.Builder.RuleClassType;
import com.google.devtools.build.lib.util.FileTypeSet;

public class NinjaGraphRule implements RuleDefinition {
  @Override
  public RuleClass build(RuleClass.Builder builder, RuleDefinitionEnvironment environment) {
    return builder
        .add(attr("srcs", LABEL_LIST).allowedFileTypes(FileTypeSet.ANY_FILE)
        .setDoc("All Ninja files describing the action graph."))
        .add(attr("main", LABEL).allowedFileTypes(FileTypeSet.ANY_FILE)
        .setDoc("Main Ninja file."))
        .add(attr("output_root", STRING)
            .nonconfigurable("Directory under workspace, specific to Ninja file.")
            .setDoc("Directory under workspace, where all the intermediate and out artifacts will be created."))
        .build();
  }

  @Override
  public Metadata getMetadata() {
    return RuleDefinition.Metadata.builder()
        .name("ninja_graph")
        .type(RuleClassType.NORMAL)
        .ancestors(BaseRuleClasses.BaseRule.class)
        .factoryClass(NinjaGraphRuleConfiguredTargetFactory.class)
        .build();
  }
}
