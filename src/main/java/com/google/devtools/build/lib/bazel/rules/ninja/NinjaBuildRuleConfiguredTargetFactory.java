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

import static com.google.devtools.build.lib.bazel.rules.ninja.NinjaVariableReplacementUtil.replaceVariablesInVariables;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.CommandLines;
import com.google.devtools.build.lib.actions.CompositeRunfilesSupplier;
import com.google.devtools.build.lib.actions.MutableActionGraph.ActionConflictException;
import com.google.devtools.build.lib.analysis.AnalysisEnvironment;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.CommandHelper;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetBuilder;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.Runfiles;
import com.google.devtools.build.lib.analysis.RunfilesProvider;
import com.google.devtools.build.lib.analysis.ShToolchain;
import com.google.devtools.build.lib.analysis.actions.FileWriteAction;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.bazel.rules.ninja.NinjaRule.ParameterName;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.rules.genrule.GenRuleAction;
import com.google.devtools.build.lib.skyframe.BlacklistedPackagePrefixesValue;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.syntax.Type;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.lib.vfs.UnixGlob;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import javax.annotation.Nullable;

public class NinjaBuildRuleConfiguredTargetFactory implements RuleConfiguredTargetFactory {
  private final static int CHUNK_SIZE = 100 * 1024;

  @Override
  public ConfiguredTarget create(RuleContext ruleContext)
      throws InterruptedException, RuleErrorException, ActionConflictException {
    Artifact srcArtifact = ruleContext.getPrerequisiteArtifact("build_ninja", Mode.TARGET);
    RootedPath rootedPath = RootedPath
        .toRootedPath(srcArtifact.getRoot().getRoot(), srcArtifact.getRootRelativePath());
    PathFragment executable = PathFragment.create(ruleContext.attributes().get("executable_target",
        Type.STRING));

    AnalysisEnvironment analysisEnvironment = ruleContext.getAnalysisEnvironment();
    Environment env = analysisEnvironment.getSkyframeEnv();
    NinjaFileHeaderBulkValue ninjaHeaderBulkValue =
        (NinjaFileHeaderBulkValue) env.getValue(NinjaFileHeaderBulkValue.key(rootedPath));
    // We "schedule" file reading & caching the result, which will be used by
    // NinjaVariablesValue and NinjaRulesValue - they will request it from SkyFrame, so no need
    // in putting it into the SkyKey.
    if (env.valuesMissing()) {
      return null;
    }
    Preconditions.checkNotNull(ninjaHeaderBulkValue);

    NinjaVariablesValue ninjaVariables =
        (NinjaVariablesValue) env.getValue(NinjaVariablesValue.key(rootedPath));
    NinjaRulesValue ninjaRulesValue =
        (NinjaRulesValue) env.getValue(NinjaRulesValue.key(rootedPath));

    PathPackageLocator pkgLocator = PrecomputedValue.PATH_PACKAGE_LOCATOR.get(env);
    BlacklistedPackagePrefixesValue blacklistedPrefixes =
        (BlacklistedPackagePrefixesValue) env.getValue(BlacklistedPackagePrefixesValue.key());

    // Now it's time to parse the action graph.
    long length = rootedPath.asPath().getPathFile().length();
    long start = Preconditions.checkNotNull(ninjaHeaderBulkValue.getPosition().getFirst());
    long numBytesToRead = length - start;
    int numWorkers = (int) Math.ceil((double) numBytesToRead / CHUNK_SIZE);

    List<NinjaTargetsValue.Key> keys = Lists.newArrayListWithCapacity(numWorkers);
    int lineStart = ninjaHeaderBulkValue.getPosition().getSecond();
    for (int i = 0; i < numWorkers; i++) {
      NinjaTargetsValue.Key key = NinjaTargetsValue
          .key(rootedPath, i, start + i * CHUNK_SIZE, lineStart);
      keys.add(key);
      env.getValue(key);
      lineStart = 0;
    }
    if (env.valuesMissing()) {
      return null;
    }

    List<NinjaTarget> targets = Lists.newArrayList();
    // todo do we need it at all?
    TreeSet<String> defaults = Sets.newTreeSet();
    ImmutableSortedMap.Builder<String, NinjaTarget> aliases = ImmutableSortedMap.naturalOrder();

    for (NinjaTargetsValue.Key targetKey : keys) {
      NinjaTargetsValue chunkValue = Preconditions.checkNotNull(
          (NinjaTargetsValue) env.getValue(targetKey));
      targets.addAll(chunkValue.getTargets());
      defaults.addAll(chunkValue.getDefaults());
      aliases.putAll(chunkValue.getAliases());
    }

    BlazeDirectories directories = Preconditions.checkNotNull(ruleContext.getConfiguration())
        .getDirectories();
    Path workspaceRoot = directories.getWorkspace();
    RootsContext rootsContext = new RootsContext(Preconditions.checkNotNull(pkgLocator),
        workspaceRoot,
        Preconditions.checkNotNull(blacklistedPrefixes).getPatterns(), analysisEnvironment);

    NestedSetBuilder<Artifact> outputsBuilder = NestedSetBuilder.stableOrder();
    PathFragment shExecutable = ShToolchain.getPathOrError(ruleContext);
    if (ruleContext.hasErrors()) {
      return null;
    }

    ImmutableSortedMap<String, NinjaRule> rules = ninjaRulesValue.getRules();
    ImmutableSortedMap<String, String> variables = ninjaVariables.getVariables();
    Artifact executableArtifact = null;
    for (NinjaTarget target : targets) {
      String command = target.getCommand();
      NinjaRule ninjaRule = rules.get(command);
      if (ninjaRule == null) {
        ruleContext.getRuleErrorConsumer().throwWithRuleError("No such rule found: " + command);
      }
      try {
        NestedSet<Artifact> filesToBuild = registerNinjaAction(
            ruleContext, rootsContext, shExecutable, target, ninjaRule, variables);
        if (executableArtifact == null) {
          for (Artifact artifact : filesToBuild) {
            if (executable.equals(artifact.getExecPath())) {
              executableArtifact = artifact;
            }
          }
        }
        outputsBuilder.addTransitive(filesToBuild);
      } catch (NinjaFileFormatException | IOException e) {
        ruleContext.getRuleErrorConsumer().throwWithRuleError(e.getMessage());
      }
      if (ruleContext.hasErrors()) {
        return null;
      }
    }

    Artifact ninjaLog = FileWriteAction.createFile(ruleContext, "ninja.log",
        "This should be lazy!", false);
    outputsBuilder.add(ninjaLog);
    NestedSet<Artifact> transitiveOutputs = outputsBuilder.build();

    Runfiles.Builder runfilesBuilder = new Runfiles.Builder(
        ruleContext.getWorkspaceName(),
        ruleContext.getConfiguration().legacyExternalRunfiles());
    if (executableArtifact != null) {
      // todo ? all transitive artifacts
//      runfilesBuilder.addTransitiveArtifacts(transitiveOutputs);
      runfilesBuilder.addTransitiveArtifacts(NestedSetBuilder.<Artifact>stableOrder().add(executableArtifact).build());
    }
    RunfilesProvider runfilesProvider = RunfilesProvider.withData(
        // No runfiles provided if not a data dependency.
        Runfiles.EMPTY,
        runfilesBuilder.build());

    RuleConfiguredTargetBuilder builder = new RuleConfiguredTargetBuilder(ruleContext)
        .setFilesToBuild(transitiveOutputs)
        .setRunfilesSupport(null, executableArtifact)
        .addProvider(RunfilesProvider.class, runfilesProvider);
    return builder.build();
  }

  private final static ImmutableSortedMap<String, String> ESCAPE_REPLACEMENTS =
      ImmutableSortedMap.of("$$", "$",
          "$ ", " ",
          "$:", ":");

  public static NestedSet<Artifact> registerNinjaAction(
      RuleContext ruleContext,
      RootsContext rootsContext,
      PathFragment shExecutable,
      NinjaTarget target,
      NinjaRule rule,
      ImmutableSortedMap<String, String> variables) throws NinjaFileFormatException, IOException {

    Map<String, String> parameters = Maps.newHashMap();
    rule.getParameters().forEach((key, value) -> parameters.put(key.name(), value));
    parameters.put(ParameterName.in.name(), String.join(" ", target.getInputs()));
    parameters.put(ParameterName.in_newline.name(), String.join("\n", target.getInputs()));
    parameters.put(ParameterName.out.name(), String.join(" ", target.getOutputs()));

    // Merge variable defined in target so that they override correctly.
    parameters.putAll(target.getVariables());

    ImmutableSortedMap<String, String> replacedParameters =
        replaceVariablesInVariables(variables, ImmutableSortedMap.copyOf(parameters));

    // todo just input & output for now
    CommandHelper commandHelper = CommandHelper.builder(ruleContext).build();

    NestedSetBuilder<Artifact> inputsBuilder = NestedSetBuilder.stableOrder();
    for (String s : target.getInputs()) {
      rootsContext.addArtifacts(inputsBuilder, s, true);
    }

    NestedSetBuilder<Artifact> indirectInputsBuilder = NestedSetBuilder.stableOrder();
    for (String input : target.getImplicitInputs()) {
      rootsContext.addArtifacts(indirectInputsBuilder, input, true);
    }
    for (String input : target.getOrderOnlyInputs()) {
      rootsContext.addArtifacts(indirectInputsBuilder, input, true);
    }

    NestedSetBuilder<Artifact> outputsBuilder = NestedSetBuilder.stableOrder();
    for (String output : target.getOutputs()) {
      rootsContext.addArtifacts(outputsBuilder, output, false);
    }

    NestedSetBuilder<Artifact> indirectOutputsBuilder = NestedSetBuilder.stableOrder();
    for (String output : target.getImplicitOutputs()) {
      rootsContext.addArtifacts(indirectOutputsBuilder, output, false);
    }

    // todo use description etc.
    String command = replacedParameters.get(ParameterName.command.name());
    for (Map.Entry<String, String> entry : ESCAPE_REPLACEMENTS.entrySet()) {
      command = command.replace(entry.getKey(), entry.getValue());
    }
    List<String> argv = commandHelper.buildCommandLine(shExecutable,
        command,
        inputsBuilder,
        ".ninjarule_script.sh");

    NestedSet<Artifact> filesToBuild = outputsBuilder.build();
    ruleContext.registerAction(
        new GenRuleAction(
            ruleContext.getActionOwner(),
            ImmutableList.copyOf(commandHelper.getResolvedTools()),
            inputsBuilder.build(),
            filesToBuild,
            CommandLines.of(argv),
            ruleContext.getConfiguration().getActionEnvironment(),
            ImmutableMap.copyOf(createExecutionInfo(ruleContext)),
            CompositeRunfilesSupplier.fromSuppliers(commandHelper.getToolsRunfilesSuppliers()),
            "NinjaBuild"));
    return filesToBuild;
  }

  private static Map<String, String> createExecutionInfo(RuleContext ruleContext) {
    Map<String, String> executionInfo = Maps.newLinkedHashMap();
    executionInfo.putAll(TargetUtils.getExecutionInfo(ruleContext.getRule()));
    executionInfo.put("local", "");
    ruleContext.getConfiguration().modifyExecutionInfo(executionInfo, "NinjaRule");
    return executionInfo;
  }

  private static class RootsContext {
    private final PathPackageLocator pkgLocator;
    private Root workspaceRoot;
    private final Map<String, Artifact> artifactCache;
    private final FileSystem fs;
    private final ImmutableSet<PathFragment> blacklistedPackages;
    private final AnalysisEnvironment analysisEnvironment;
    private ArtifactRoot execRoot;

    private RootsContext(PathPackageLocator pkgLocator, Path workspaceRoot,
        ImmutableSet<PathFragment> blacklistedPackages,
        AnalysisEnvironment analysisEnvironment) {
      this.pkgLocator = pkgLocator;
      this.workspaceRoot = Root.fromPath(workspaceRoot);
      this.blacklistedPackages = blacklistedPackages;
      this.analysisEnvironment = analysisEnvironment;
      artifactCache = Maps.newHashMap();
      fs = pkgLocator.getOutputBase().getFileSystem();
    }

    public void addArtifacts(NestedSetBuilder<Artifact> builder,
        String path, boolean isInput) throws IOException {
      PathFragment fragment = PathFragment.create(path);
      Preconditions.checkArgument(fragment.segmentCount() > 0);

      Path fsPath;
      if (fragment.isAbsolute()) {
        fsPath = fs.getPath(fragment);
      } else {
        fsPath = workspaceRoot.getRelative(fragment);
      }

      List<Path> list;
      if (fsPath.isDirectory()) {
        list = new UnixGlob.Builder(fsPath)
            // .setFilesystemCalls(syscallCache) // todo
            .addPattern("*")
            .glob();
      } else {
        list = ImmutableList.of(fsPath);
      }

      if (isInput) {
        Root sourceRoot = getSourceRoot(fragment, fsPath);
        if (sourceRoot != null) {
          for (Path listPath : list) {
            builder.add(analysisEnvironment
                .getSourceArtifact(sourceRoot.relativize(listPath), sourceRoot));
          }
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

      execRoot = ArtifactRoot.underWorkspaceOutputRoot(workspaceRoot.asPath(),
          PathFragment.EMPTY_FRAGMENT);
      for (Path listPath : list) {
        Path rootPath = execRoot.getRoot().getRelative(execRoot.getExecPath());
        Artifact artifact = analysisEnvironment
            .getUnderWorkspaceArtifact(listPath.relativeTo(rootPath), execRoot);
        artifactCache.put(artifactPathString, artifact);
        builder.add(artifact);
      }
    }

    // private ArtifactRoot getSpecialMiddlemanRoot(PathFragment fragment) {
    //   String dirName = fragment.getSegment(0);
    //   ArtifactRoot root = roots.get(dirName);
    //   if (root == null) {
    //     root = ArtifactRoot.underWorkspaceOutputRoot(workspaceRoot.asPath(),
    //         PathFragment.create(dirName));
    //     roots.put(dirName, root);
    //   }
    //   return root;
    // }

    @Nullable
    private Root getSourceRoot(PathFragment fragment, Path fsPath) {
      for (PathFragment blacklistedPackage : blacklistedPackages) {
        if (fragment.startsWith(blacklistedPackage)) {
          return null;
        }
      }

      ImmutableList<Root> pathEntries = pkgLocator.getPathEntries();
      for (Root pathEntry : pathEntries) {
        if (pathEntry.contains(fsPath)) {
          return pathEntry;
        }
      }
      return null;
    }

    // public void addTreeArtifacts(NestedSetBuilder<Artifact> indirectInputsBuilder,
    //     String pathString, boolean b) {
    //   analysisEnvironment.getTreeArtifact();
    // }

    /*new UnixGlob.Builder(root)
              .setFilesystemCalls(syscallCache)
              .addPattern(rule.findFilter)
              .glob()*/
  }
}
