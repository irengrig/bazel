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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.analysis.AnalysisEnvironment;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

class RootsContext {
  private final PathPackageLocator pkgLocator;
  // private final ArtifactRoot absoluteRoot;
  private Root workspaceRoot;
  private final Map<String, Artifact> artifactCache;
  private final FileSystem fs;
  private final ImmutableSet<PathFragment> blacklistedPackages;
  private final AnalysisEnvironment analysisEnvironment;
  private final Map<PathFragment, Artifact> aliases;
  private final Set<String> phonyArtifacts;
  private final Set<String> generatedFiles;
  private ArtifactRoot execRoot;

  RootsContext(PathPackageLocator pkgLocator, Path workspaceRoot,
      ImmutableSet<PathFragment> blacklistedPackages,
      AnalysisEnvironment analysisEnvironment,
      Map<PathFragment, Artifact> aliases,
      Set<String> phonyArtifacts,
      Set<String> generatedFiles) {
    this.pkgLocator = pkgLocator;
    this.workspaceRoot = Root.fromPath(workspaceRoot);
    this.blacklistedPackages = blacklistedPackages;
    this.analysisEnvironment = analysisEnvironment;
    this.aliases = aliases;
    this.phonyArtifacts = phonyArtifacts;
    this.generatedFiles = generatedFiles;
    artifactCache = Maps.newHashMap();
    fs = pkgLocator.getOutputBase().getFileSystem();
    execRoot = ArtifactRoot.underWorkspaceOutputRoot(workspaceRoot, PathFragment.EMPTY_FRAGMENT);
  }

  public Root getWorkspaceRoot() {
    return workspaceRoot;
  }

  public ImmutableList<String> maybeReplaceAliases(ImmutableList<String> paths) {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    for (String path : paths) {
      builder.add(maybeReplaceAlias(path));
    }
    return builder.build();
  }

  public String maybeReplaceAlias(String path) {
    Artifact alias = aliases.get(PathFragment.create(path));
    if (alias != null) {
      return alias.getPath().asFragment().getPathString();
    }
    return path;
  }

  public void addArtifacts(NestedSetBuilder<Artifact> builder,
      String path, boolean isInput) throws IOException {
    PathFragment fragment = PathFragment.create(path);
    Preconditions.checkArgument(fragment.segmentCount() > 0);

    Artifact mappedArtifact = aliases.get(fragment);
    if (mappedArtifact != null) {
      builder.add(mappedArtifact);
      return;
    }

    Path fsPath = getFsPath(fragment);

    // todo better use path fragments for comparing paths
    if (isInput && !generatedFiles.contains(path)) {
      if (fsPath.isDirectory()) {
        return;
      }
      // todo use path fragments for comparison
      if (phonyArtifacts.contains(path) && !fsPath.exists()) {
        // do not register this always dirty dependency;
        // todo in future, make it always dirty
        return;
      }
      Root sourceRoot = getSourceRoot(fragment, fsPath);
      if (sourceRoot != null) {
        builder.add(analysisEnvironment
            .getSourceArtifact(sourceRoot.relativize(fsPath), sourceRoot));
        return;
      }
    }
    String artifactPathString = fsPath.asFragment().getPathString();
    Artifact cachedArtifact = artifactCache.get(artifactPathString);
    if (cachedArtifact != null) {
      builder.add(cachedArtifact);
      return;
    }
    // Otherwise, this can be .intermediate artifact, either input of output.
    // execRoot = ArtifactRoot.underWorkspaceOutputRoot(workspaceRoot.asPath(),
    //     PathFragment.EMPTY_FRAGMENT);
    Path rootPath = execRoot.getRoot().getRelative(execRoot.getExecPath());
    Artifact artifact;
    if (fsPath.asFragment().startsWith(rootPath.asFragment())) {
      artifact = analysisEnvironment
          .getUnderWorkspaceArtifact(fsPath.relativeTo(rootPath), execRoot);
    } else {
      artifact = analysisEnvironment
          .getSourceArtifact(fsPath.asFragment(), Root.absoluteRoot(fs));
    }
    artifactCache.put(artifactPathString, artifact);
    builder.add(artifact);
  }

  private Path getFsPath(PathFragment fragment) {
    Path fsPath;
    if (fragment.isAbsolute()) {
      fsPath = fs.getPath(fragment);
    } else {
      fsPath = workspaceRoot.getRelative(fragment);
    }
    return fsPath;
  }

  Artifact createUnderWorkspaceArtifact(String path) {
    // todo this is a hack here
    return analysisEnvironment
        .getUnderWorkspaceArtifact(PathFragment.create(path), execRoot);
  }

  @Nullable
  private Root getSourceRoot(PathFragment fragment, Path fsPath) {
    if (!fsPath.exists()) {
      return null;
    }
    // for (PathFragment blacklistedPackage : blacklistedPackages) {
    //   if (fragment.startsWith(blacklistedPackage)
    //       // todo this is a temporary hack for the external directory under sources
    //       && !"external".equals(blacklistedPackage.getPathString())) {
    //     return null;
    //   }
    // }

    ImmutableList<Root> pathEntries = pkgLocator.getPathEntries();
    for (Root pathEntry : pathEntries) {
      if (pathEntry.contains(fsPath)) {
        return pathEntry;
      }
    }
    return null;
  }
}
