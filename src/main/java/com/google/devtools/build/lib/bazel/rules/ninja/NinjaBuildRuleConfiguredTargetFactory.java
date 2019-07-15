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
import com.google.devtools.build.lib.analysis.AliasProvider;
import com.google.devtools.build.lib.analysis.AnalysisEnvironment;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.CommandHelper;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.PrerequisiteArtifacts;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetBuilder;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.Runfiles;
import com.google.devtools.build.lib.analysis.RunfilesProvider;
import com.google.devtools.build.lib.analysis.ShToolchain;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.actions.FileWriteAction;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.bazel.rules.ninja.NinjaRule.ParameterName;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.packages.BuildType;
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
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import javax.annotation.Nullable;

public class NinjaBuildRuleConfiguredTargetFactory implements RuleConfiguredTargetFactory {
  private final static int CHUNK_SIZE = 100 * 1024;

  @Override
  public ConfiguredTarget create(RuleContext ruleContext)
      throws InterruptedException, RuleErrorException, ActionConflictException {
    Artifact srcArtifact = ruleContext.getPrerequisiteArtifact("build_ninja", Mode.TARGET);
    RootedPath rootedPath = getRootedPath(srcArtifact);
    PathFragment executable = PathFragment.create(ruleContext.attributes().get("executable_target",
        Type.STRING));

    AnalysisEnvironment analysisEnvironment = ruleContext.getAnalysisEnvironment();
    Environment env = analysisEnvironment.getSkyframeEnv();

    List<NinjaFileHeaderBulkValue> bulkValues = Lists.newArrayList();
    try {
      readBulkValuesWithAllIncludes(rootedPath, env,
          ruleContext.getPrerequisiteArtifacts("srcs", Mode.TARGET), bulkValues);
    } catch (NinjaFileFormatException e) {
      ruleContext.getRuleErrorConsumer().throwWithRuleError(e.getMessage());
      return null;
    }
    if (env.valuesMissing()) {
      return null;
    }

    for (NinjaFileHeaderBulkValue bulkValue : bulkValues) {
      env.getValue(NinjaVariablesValue.key(bulkValue.getPath()));
      env.getValue(NinjaRulesValue.key(bulkValue.getPath()));
    }
    List<NinjaTargetsValue.Key> targetKeys = requestNinjaTargets(bulkValues, env);
    if (env.valuesMissing()) {
      return null;
    }
    List<NinjaTarget> targets = receiveNinjaTargets(targetKeys, env);
    Preconditions.checkNotNull(targets);

    ImmutableSortedMap.Builder<String, NinjaRule> rulesBuilder = ImmutableSortedMap.naturalOrder();
    ImmutableSortedMap.Builder<String, String> variablesBuilder = ImmutableSortedMap.naturalOrder();
    receiveVariableAndRules(variablesBuilder, rulesBuilder, bulkValues, env);

    ImmutableSortedMap<String, NinjaRule> rules = rulesBuilder.build();
    ImmutableSortedMap<String, String> variables = variablesBuilder.build();

    return createConfiguredTarget(ruleContext, executable, analysisEnvironment, env, targets, rules,
        variables);
  }

  private RootedPath getRootedPath(Artifact srcArtifact) {
    return RootedPath
        .toRootedPath(srcArtifact.getRoot().getRoot(), srcArtifact.getRootRelativePath());
  }

  private void receiveVariableAndRules(
      ImmutableSortedMap.Builder<String, String> variablesBuilder,
      ImmutableSortedMap.Builder<String, NinjaRule> rulesBuilder,
      List<NinjaFileHeaderBulkValue> bulkValues,
      Environment env) throws InterruptedException {
    for (NinjaFileHeaderBulkValue bulkValue : bulkValues) {
      RootedPath path = bulkValue.getPath();
      NinjaVariablesValue ninjaVariablesValue = (NinjaVariablesValue)
          Preconditions.checkNotNull(env.getValue(NinjaVariablesValue.key(path)));
      variablesBuilder.putAll(ninjaVariablesValue.getVariables());
      NinjaRulesValue ninjaRulesValue = (NinjaRulesValue)
          Preconditions.checkNotNull(env.getValue(NinjaRulesValue.key(path)));
      rulesBuilder.putAll(ninjaRulesValue.getRules());
    }
  }

  private ConfiguredTarget createConfiguredTarget(RuleContext ruleContext, PathFragment executable,
      AnalysisEnvironment analysisEnvironment, Environment env, List<NinjaTarget> targets,
      ImmutableSortedMap<String, NinjaRule> rules, ImmutableSortedMap<String, String> variables)
      throws InterruptedException, RuleErrorException, ActionConflictException {
    PathPackageLocator pkgLocator = PrecomputedValue.PATH_PACKAGE_LOCATOR.get(env);
    BlacklistedPackagePrefixesValue blacklistedPrefixes =
        (BlacklistedPackagePrefixesValue) env.getValue(BlacklistedPackagePrefixesValue.key());

    BlazeDirectories directories = Preconditions.checkNotNull(ruleContext.getConfiguration())
        .getDirectories();
    Path workspaceRoot = directories.getWorkspace();
    Map<PathFragment, Artifact> aliases = fillDepsMapping(ruleContext);
    RootsContext rootsContext = new RootsContext(Preconditions.checkNotNull(pkgLocator),
        workspaceRoot,
        Preconditions.checkNotNull(blacklistedPrefixes).getPatterns(), analysisEnvironment,
        aliases);

    NestedSetBuilder<Artifact> outputsBuilder = NestedSetBuilder.stableOrder();
    PathFragment shExecutable = ShToolchain.getPathOrError(ruleContext);
    if (ruleContext.hasErrors()) {
      return null;
    }
    Map<String, String> exportTargets = Preconditions.checkNotNull(ruleContext.attributes()
        .get("export_targets", Type.STRING_DICT));
    Map<String, NestedSet<Artifact>> outputGroups =
        Maps.newHashMapWithExpectedSize(exportTargets.size());
    Map<PathFragment, String> requestedTargets =
        Maps.newHashMapWithExpectedSize(exportTargets.size());
    for (Map.Entry<String, String> entry : exportTargets.entrySet()) {
      requestedTargets.put(PathFragment.create(entry.getKey()), entry.getValue());
    }

    // filter only targets, needed for output
    targets = filterOnlyNeededTargets(executable, targets, requestedTargets);

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
        if (executableArtifact == null || !requestedTargets.isEmpty()) {
          for (Artifact artifact : filesToBuild) {
            PathFragment currentPathFragment = artifact.getPath().asFragment();
            Optional<PathFragment> requestedPathFragment = requestedTargets.keySet().stream()
                .filter(currentPathFragment::endsWith).findFirst();
            if (requestedPathFragment.isPresent()) {
              String outputGroupName =
                  Preconditions.checkNotNull(requestedTargets.remove(requestedPathFragment.get()));
              outputGroups.put(outputGroupName,
                  NestedSetBuilder.<Artifact>stableOrder().add(artifact).build());
            }
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
      runfilesBuilder.addTransitiveArtifacts(NestedSetBuilder.<Artifact>stableOrder()
          .add(executableArtifact).build());
    }
    RunfilesProvider runfilesProvider = RunfilesProvider.withData(
        // No runfiles provided if not a data dependency.
        Runfiles.EMPTY,
        runfilesBuilder.build());

    RuleConfiguredTargetBuilder builder = new RuleConfiguredTargetBuilder(ruleContext)
        .setFilesToBuild(transitiveOutputs)
        .setRunfilesSupport(null, executableArtifact)
        .addOutputGroups(outputGroups)
        .addProvider(RunfilesProvider.class, runfilesProvider);
    return builder.build();
  }

  private List<NinjaTarget> filterOnlyNeededTargets(PathFragment executable,
      List<NinjaTarget> targets, Map<PathFragment, String> requestedTargets) {
    ArrayDeque<PathFragment> queue = new ArrayDeque<>();
    queue.addAll(requestedTargets.keySet());
    if (executable != null && !executable.isEmpty()) {
      queue.add(executable);
    }
    if (queue.isEmpty()) {
      return targets;
    }

    Set<NinjaTarget> filteredTargets = Sets.newHashSet();
    Set<PathFragment> checkedPf = Sets.newHashSet();

    // TODO: express it better
    Map<PathFragment, NinjaTarget> pf2target = Maps.newHashMap();
    for (NinjaTarget target : targets) {
      for (String output : target.getOutputs()) {
        pf2target.put(PathFragment.create(output), target);
      }
      for (String output : target.getImplicitOutputs()) {
        pf2target.put(PathFragment.create(output), target);
      }
    }

    while (!queue.isEmpty()) {
      PathFragment pf = queue.removeFirst();
      checkedPf.add(pf);
      NinjaTarget ninjaTarget = pf2target.get(pf);
      if (ninjaTarget == null) {
        // must be an input file then; further: check it
        continue;
      }

      filteredTargets.add(ninjaTarget);

      List<String> inputs = Lists.newArrayList(ninjaTarget.getInputs());
      inputs.addAll(ninjaTarget.getImplicitInputs());
      inputs.addAll(ninjaTarget.getOrderOnlyInputs());
      for (String input : inputs) {
        PathFragment inputPf = PathFragment.create(input);
        if (!checkedPf.contains(inputPf)) {
          queue.add(inputPf);
        }
      }
    }
    return Lists.newArrayList(filteredTargets);
  }

  private Map<PathFragment, Artifact> fillDepsMapping(RuleContext ruleContext)
      throws RuleErrorException {
    Map<Label, String> depsMapping = Preconditions.checkNotNull(ruleContext.attributes().get(
        "deps_mapping", BuildType.LABEL_KEYED_STRING_DICT));
    List<? extends TransitiveInfoCollection> depsMappingPrerequisites =
        Preconditions.checkNotNull(ruleContext.getPrerequisites("deps_mapping", Mode.TARGET));
    Map<PathFragment, Artifact> aliases = Maps.newHashMapWithExpectedSize(depsMapping.size());
    for (TransitiveInfoCollection dep : depsMappingPrerequisites) {
      NestedSet<Artifact> files = dep.getProvider(FileProvider.class).getFilesToBuild();
      Label specifiedLabel = AliasProvider.getDependencyLabel(dep);
      PathFragment mappingFragment =
          PathFragment.create(Preconditions.checkNotNull(depsMapping.get(specifiedLabel)));

      // we expect only 1 item, so that's fine to flatten
      ImmutableList<Artifact> artifactsList = files.toList();
      if (artifactsList.isEmpty()) {
        ruleContext.getRuleErrorConsumer().
            throwWithRuleError("Can not find mapping for: " + mappingFragment);
      } else if (artifactsList.size() > 1) {
        PathFragment mappingName = mappingFragment.subFragment(mappingFragment.segmentCount() - 1);
        boolean found = false;
        List<String> candidates = Lists.newArrayList();
        for (Artifact artifact : artifactsList) {
          candidates.add(artifact.getFilename());
          if (artifact.getExecPath().endsWith(mappingName)) {
            aliases.put(mappingFragment, artifact);
            found = true;
            break;
          }
        }
        if (!found) {
          ruleContext.getRuleErrorConsumer().
              throwWithRuleError(String
                  .format("Multiple files [%s] specified for mapping: %s, but expected only one.",
                      String.join(", ", candidates), mappingFragment));
        }
      } else {
        aliases.put(mappingFragment, artifactsList.get(0));
      }
    }
    return aliases;
  }

  private void readBulkValuesWithAllIncludes(
      RootedPath rootedPath,
      Environment env,
      PrerequisiteArtifacts srcs,
      List<NinjaFileHeaderBulkValue> bulkValues)
      throws InterruptedException, NinjaFileFormatException {

    ArrayDeque<RootedPath> queue = new ArrayDeque<>();
    queue.add(rootedPath);

    while (!queue.isEmpty()) {
      RootedPath path = queue.removeFirst();
      NinjaFileHeaderBulkValue ninjaHeaderBulkValue =
          (NinjaFileHeaderBulkValue) env.getValue(NinjaFileHeaderBulkValue.key(path));
      if (ninjaHeaderBulkValue != null) {
        bulkValues.add(ninjaHeaderBulkValue);
        queue.addAll(parseIncludes(srcs, ninjaHeaderBulkValue.getIncludeStatements()));
      }
    }
  }

  private List<RootedPath> parseIncludes(PrerequisiteArtifacts srcs,
      List<String> includeStatements)
      throws NinjaFileFormatException {
    List<RootedPath> result = Lists.newArrayList();
    for (String includeStatement : includeStatements) {
      // precondition check as it was already checked in bulk parser
      Preconditions.checkArgument(includeStatement.startsWith("include ")
          || includeStatement.startsWith("subninja "));
      int spaceIdx = includeStatement.indexOf(' ');
      Preconditions.checkArgument(spaceIdx > 0);
      PathFragment includedPath = PathFragment.create(
          includeStatement.substring(spaceIdx + 1).trim());
      if (includedPath.isAbsolute()) {
        throw new NinjaFileFormatException("Do not expect absolute files to be included: "
            + includedPath);
      }

      Optional<Artifact> artifact = srcs.list().stream()
          .filter(a -> a.getExecPath().equals(includedPath)).findFirst();
      if (artifact.isPresent()) {
        result.add(getRootedPath(artifact.get()));
      } else {
        throw new NinjaFileFormatException("Can not find artifact for included: " +
            includedPath.getPathString());
      }
    }
    return result;
  }

  private List<NinjaTargetsValue.Key> requestNinjaTargets(List<NinjaFileHeaderBulkValue> bulkValues,
      Environment env) throws InterruptedException {
    List<NinjaTargetsValue.Key> keys = Lists.newArrayList();
    for (NinjaFileHeaderBulkValue bulkValue : bulkValues) {
      requestNinjaTarget(bulkValue, env, keys);
    }
    return keys;
  }

  private List<NinjaTarget> receiveNinjaTargets(List<NinjaTargetsValue.Key> ninjaTargetKeys,
      Environment env) throws InterruptedException {
    List<NinjaTarget> targets = Lists.newArrayList();
    // todo do we need it at all?
    TreeSet<String> defaults = Sets.newTreeSet();
    ImmutableSortedMap.Builder<String, NinjaTarget> aliases = ImmutableSortedMap.naturalOrder();

    for (NinjaTargetsValue.Key targetKey : ninjaTargetKeys) {
      NinjaTargetsValue chunkValue = Preconditions.checkNotNull(
          (NinjaTargetsValue) env.getValue(targetKey));
      targets.addAll(chunkValue.getTargets());
      defaults.addAll(chunkValue.getDefaults());
      aliases.putAll(chunkValue.getAliases());
    }
    return targets;
  }

  private void requestNinjaTarget(
      NinjaFileHeaderBulkValue ninjaHeaderBulkValue,
      Environment env,
      List<NinjaTargetsValue.Key> keys) throws InterruptedException {
    RootedPath rootedPath = ninjaHeaderBulkValue.getPath();
    long length = rootedPath.asPath().getPathFile().length();
    long start = Preconditions.checkNotNull(ninjaHeaderBulkValue.getPosition().getFirst());
    long numBytesToRead = length - start;
    int numWorkers = (int) Math.ceil((double) numBytesToRead / CHUNK_SIZE);

    int lineStart = ninjaHeaderBulkValue.getPosition().getSecond();
    for (int i = 0; i < numWorkers; i++) {
      NinjaTargetsValue.Key key = NinjaTargetsValue
          .key(rootedPath, i, start + i * CHUNK_SIZE, lineStart);
      keys.add(key);
      env.getValue(key);
      lineStart = 0;
    }
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
    parameters.put(ParameterName.in.name(), String.join(" ", rootsContext.maybeReplaceAliases(target.getInputs())));
    parameters.put(ParameterName.in_newline.name(), String.join("\n", rootsContext.maybeReplaceAliases(target.getInputs())));
    parameters.put(ParameterName.out.name(), String.join(" ", rootsContext.maybeReplaceAliases(target.getOutputs())));

    // Merge variable defined in target so that they override correctly.
    parameters.putAll(target.getVariables());

    ImmutableSortedMap<String, String> replacedParameters =
        replaceVariablesInVariables(variables, ImmutableSortedMap.copyOf(parameters));

    NestedSetBuilder<Artifact> inputsBuilder = NestedSetBuilder.stableOrder();

    ImmutableMap.Builder<Label, NestedSet<Artifact>> labelMap = ImmutableMap.builder();
    for (TransitiveInfoCollection dep : ruleContext.getPrerequisites("srcs", Mode.TARGET)) {
      NestedSet<Artifact> files = dep.getProvider(FileProvider.class).getFilesToBuild();
      inputsBuilder.addTransitive(files);
      labelMap.put(AliasProvider.getDependencyLabel(dep), files);
    }
    TransitiveInfoCollection buildNinja = ruleContext.getPrerequisite("build_ninja", Mode.TARGET);
    NestedSet<Artifact> files = buildNinja.getProvider(FileProvider.class).getFilesToBuild();
    inputsBuilder.addTransitive(files);

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

    // todo just input & output for now
    CommandHelper commandHelper = CommandHelper
        .builder(ruleContext)
        .addLabelMap(labelMap.build())
        .build();

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
    private final Map<PathFragment, Artifact> aliases;
    private ArtifactRoot execRoot;

    private RootsContext(PathPackageLocator pkgLocator, Path workspaceRoot,
        ImmutableSet<PathFragment> blacklistedPackages,
        AnalysisEnvironment analysisEnvironment,
        Map<PathFragment, Artifact> aliases) {
      this.pkgLocator = pkgLocator;
      this.workspaceRoot = Root.fromPath(workspaceRoot);
      this.blacklistedPackages = blacklistedPackages;
      this.analysisEnvironment = analysisEnvironment;
      this.aliases = aliases;
      artifactCache = Maps.newHashMap();
      fs = pkgLocator.getOutputBase().getFileSystem();
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
