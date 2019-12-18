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
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.DerivedArtifact;
import com.google.devtools.build.lib.actions.Artifact.SourceArtifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.MutableActionGraph.ActionConflictException;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetBuilder;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.RunfilesProvider;
import com.google.devtools.build.lib.analysis.actions.SymlinkAction;
import com.google.devtools.build.lib.analysis.actions.SymlinkTreeAction;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.skylarkbuildapi.FileApi;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;
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
    String buildRoot = ruleContext.attributes().get("build_root", Type.STRING);
    Preconditions.checkNotNull(buildRoot);
    Set<PathFragment> preExisting = copyBuildRootToOutputRoot(ruleContext, buildRoot, outputRoot);

    builder.addProvider(NinjaGraphFilesProvider.class,
        new NinjaGraphFilesProvider(mainArtifact.getOwnerLabel(), children, buildRoot, outputRoot, preExisting))
        .addProvider(RunfilesProvider.class, RunfilesProvider.EMPTY);

    return builder.build();
  }

  private Set<PathFragment> copyBuildRootToOutputRoot(
      RuleContext ruleContext,
      String buildRootPath,
      String outputRootPath) throws RuleErrorException {
    RepositoryName repositoryName = ruleContext.getLabel().getPackageIdentifier().getRepository();
    Path workspace = ruleContext.getConfiguration().getDirectories().getWorkspace();
    String workspaceName = repositoryName.isMain() ? ruleContext.getWorkspaceName()
        : repositoryName.strippedName();
    Path execRoot = Preconditions.checkNotNull(ruleContext.getConfiguration()).getDirectories()
        .getExecRoot(workspaceName);

    ArtifactRoot outputRoot = ArtifactRoot
        .asDerivedRoot(execRoot, PathFragment.create(outputRootPath));
    PathFragment sourcePathFragment = ruleContext.getLabel().getPackageIdentifier().getSourceRoot();
    Root sourceRoot = Root.fromPath(workspace.getRelative(sourcePathFragment));

    Set<PathFragment> set;
    try {
      Path buildRoot = sourceRoot.getRelative(buildRootPath);
      for (Path entry : buildRoot.getDirectoryEntries()) {
        PathFragment pf = PathFragment.create(entry.getBaseName());
        DerivedArtifact outputArtifact = ruleContext
            .getDerivedArtifact(pf, outputRoot);
        SourceArtifact inputArtifact = ruleContext.getAnalysisEnvironment()
            .getSourceArtifact(pf, sourceRoot);
        ruleContext.registerAction(SymlinkAction
            .toArtifact(ruleContext.getActionOwner(), inputArtifact, outputArtifact, ""));
      }

      set = Sets.newHashSet();
      ArrayDeque<Path> queue = new ArrayDeque<>();
      queue.add(buildRoot);
      while (!queue.isEmpty()) {
        Path path = queue.remove();
        Preconditions.checkState(!path.isSymbolicLink()); // todo ?
        if (path.isDirectory()) {
          queue.addAll(path.getDirectoryEntries());
        } else {
          set.add(path.relativeTo(buildRoot));
        }
      }
    } catch (IOException e) {
      throw new RuleErrorException(e.getMessage());
    }
    return set;
  }
}
