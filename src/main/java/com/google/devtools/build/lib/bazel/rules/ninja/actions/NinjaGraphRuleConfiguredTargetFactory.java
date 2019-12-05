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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.MutableActionGraph.ActionConflictException;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetBuilder;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.RunfilesProvider;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.skylarkbuildapi.FileApi;
import java.util.List;
import java.util.stream.Collectors;

public class NinjaGraphRuleConfiguredTargetFactory implements RuleConfiguredTargetFactory {

  @Override
  public ConfiguredTarget create(RuleContext ruleContext)
      throws InterruptedException, RuleErrorException, ActionConflictException {
    Artifact mainArtifact = ruleContext.getPrerequisiteArtifact("main", Mode.TARGET);

    ImmutableList<Artifact> srcs = ruleContext.getPrerequisiteArtifacts("srcs", Mode.TARGET).list();
    List<Label> children = srcs.stream().map(FileApi::getOwnerLabel).collect(Collectors.toList());

    NestedSetBuilder<Artifact> filesToBuildBuilder = NestedSetBuilder.stableOrder();
    filesToBuildBuilder.addAll(srcs);
    filesToBuildBuilder.add(mainArtifact);

    RuleConfiguredTargetBuilder builder = new RuleConfiguredTargetBuilder(ruleContext);
    builder.setFilesToBuild(filesToBuildBuilder.build());

    String outputRoot = ruleContext.attributes().get("output_root", Type.STRING);
    Preconditions.checkNotNull(outputRoot);

    builder.addProvider(NinjaGraphFilesProvider.class,
        new NinjaGraphFilesProvider(mainArtifact.getOwnerLabel(), children, outputRoot))
        .addProvider(RunfilesProvider.class, RunfilesProvider.EMPTY);

    return builder.build();
  }
}
