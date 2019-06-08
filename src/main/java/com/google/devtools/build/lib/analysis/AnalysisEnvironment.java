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

package com.google.devtools.build.lib.analysis;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionRegistry;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.MiddlemanFactory;
import com.google.devtools.build.lib.analysis.buildinfo.BuildInfoFactory.BuildInfoKey;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.syntax.StarlarkSemantics;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.skyframe.SkyFunction;

/**
 * The set of services that are provided to {@link ConfiguredTarget} objects
 * during initialization.
 *
 * <p>These objects should not outlast the analysis phase. Do not pass them to {@link Action}
 * objects or other persistent objects. There are internal tests to ensure that AnalysisEnvironment
 * objects are not persisted that check the name of this class, so update those tests you change the
 * names of any implementation of this class.
 */
public interface AnalysisEnvironment extends ActionRegistry {
  /** Returns a callback to be used in this build for reporting analysis errors. */
  ExtendedEventHandler getEventHandler();

  /**
   * Returns whether any errors were reported to this instance.
   */
  boolean hasErrors();

  Artifact getSourceArtifact(PathFragment rootRelativePath, Root root);

  /**
   * Returns the artifact for the derived file {@code rootRelativePath}.
   *
   * <p><b>DO NOT USE</b> in rule implementations. Use {@link
   * RuleContext#getPackageRelativeArtifact(PathFragment, ArtifactRoot)} or {@link
   * RuleContext#getUniqueDirectoryArtifact(String, PathFragment, ArtifactRoot)}, or, if there is
   * really no other way, {@link RuleContext#getShareableArtifact(PathFragment, ArtifactRoot)}
   * instead.
   *
   * <p>Creates the artifact if necessary and sets the root of that artifact to {@code root}.
   *
   * <p>This method can create artifacts anywhere in the output tree, thus making it possible for
   * artifacts generated by two different rules to clash. To avoid this, use the artifact creation
   * method on {@link RuleContext} mentioned above.
   */
  Artifact.DerivedArtifact getDerivedArtifact(PathFragment rootRelativePath, ArtifactRoot root);

  Artifact getUnderWorkspaceArtifact(PathFragment rootRelativePath, ArtifactRoot root);

  /**
   * Same as {@link #getDerivedArtifact(PathFragment, ArtifactRoot)} but includes the option to use
   * a content-based path for this artifact (see {@link
   * BuildConfiguration#useContentBasedOutputPaths()}).
   */
  Artifact.DerivedArtifact getDerivedArtifact(
      PathFragment rootRelativePath, ArtifactRoot root, boolean contentBasedPath);

  /**
   * Returns an artifact for the derived file {@code rootRelativePath} whose changes do not cause a
   * rebuild.
   *
   * <p>Creates the artifact if necessary and sets the root of that artifact to {@code root}.
   *
   * <p>This is useful for files that store data that changes very frequently (e.g. current time)
   * but does not substantially affect the result of the build.
   */
  Artifact getConstantMetadataArtifact(PathFragment rootRelativePath, ArtifactRoot root);

  /**
   * Returns the artifact for the derived TreeArtifact with directory {@code rootRelativePath},
   * creating it if necessary, and setting the root of that artifact to {@code root}. The artifact
   * will be a TreeArtifact.
   */
  SpecialArtifact getTreeArtifact(PathFragment rootRelativePath, ArtifactRoot root);

  /**
   * Returns the artifact for the derived file {@code rootRelativePath}, creating it if necessary,
   * and setting the root of that artifact to {@code root}. The artifact will represent the output
   * directory of a {@code Fileset}.
   */
  Artifact getFilesetArtifact(PathFragment rootRelativePath, ArtifactRoot root);

  /**
   * Returns the middleman factory associated with the build.
   */
  // TODO(bazel-team): remove this method and replace it with delegate methods.
  MiddlemanFactory getMiddlemanFactory();

  /**
   * Returns the generating action for the given local artifact.
   *
   * If the artifact was created in another analysis environment (e.g. by a different configured
   * target instance) or the artifact is a source artifact, it returns null.
   */
  ActionAnalysisMetadata getLocalGeneratingAction(Artifact artifact);

  /**
   * Returns the actions that were registered so far with this analysis environment, that is, all
   * the actions that were created by the current target being analyzed.
   */
  ImmutableList<ActionAnalysisMetadata> getRegisteredActions();

  /**
   * Returns the Skyframe SkyFunction.Environment if available. Otherwise, null.
   *
   * <p>If you need to use this for something other than genquery, please think long and hard
   * about that.
   */
  SkyFunction.Environment getSkyframeEnv();

  /**
   * Returns the options that affect the Skylark interpreter used for evaluating Skylark rule
   * implementation functions.
   */
  StarlarkSemantics getSkylarkSemantics() throws InterruptedException;

  /**
   * Returns the Artifact that is used to hold the non-volatile workspace status for the current
   * build request.
   */
  Artifact getStableWorkspaceStatusArtifact() throws InterruptedException;

  /**
   * Returns the Artifact that is used to hold the volatile workspace status (e.g. build changelist)
   * for the current build request.
   */
  Artifact getVolatileWorkspaceStatusArtifact() throws InterruptedException;

  /**
   * Returns the Artifacts that contain the workspace status for the current build request.
   *
   * @param stamp whether stamping is enabled
   * @param config the current build configuration.
   */
  ImmutableList<Artifact> getBuildInfo(boolean stamp, BuildInfoKey key, BuildConfiguration config)
      throws InterruptedException;

  /**
   * Returns the set of orphan Artifacts (i.e. Artifacts without generating action). Should only be
   * called after the ConfiguredTarget is created.
   */
  ImmutableSet<Artifact> getOrphanArtifacts();

  /**
   * Returns the set of tree artifacts that have the same exec path as some other artifacts. Should
   * only be called after the ConfiguredTarget is created.
   */
  ImmutableSet<Artifact> getTreeArtifactsConflictingWithFiles();

  ActionKeyContext getActionKeyContext();
}
