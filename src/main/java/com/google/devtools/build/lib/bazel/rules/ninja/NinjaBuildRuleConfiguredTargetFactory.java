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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
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
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.Nullable;

public class NinjaBuildRuleConfiguredTargetFactory implements RuleConfiguredTargetFactory {
  private final static int CHUNK_SIZE = parseChunkSize();

  private static int parseChunkSize() {
    String chunkSizeStr = System.getenv("bazel.ninja.chunk.size");
    if (chunkSizeStr != null) {
      try {
        return Integer.parseInt(chunkSizeStr);
      } catch (NumberFormatException e) {
        //
      }
    }
    return 10 * 1024 * 1024;
  }

  private final static boolean ONLY_READ_FILE = "true".equals(System.getenv("bazel.ninja.only.read.file"));

  @Override
  public ConfiguredTarget create(RuleContext ruleContext)
      throws InterruptedException, RuleErrorException, ActionConflictException {
    Artifact srcArtifact = ruleContext.getPrerequisiteArtifact("build_ninja", Mode.TARGET);
    RootedPath rootedPath = getRootedPath(srcArtifact);
    PathFragment executable = PathFragment.create(ruleContext.attributes().get("executable_target",
        Type.STRING));

    AnalysisEnvironment analysisEnvironment = ruleContext.getAnalysisEnvironment();
    Environment env = analysisEnvironment.getSkyframeEnv();

    List<NinjaTargetsValue> bulkValues = Lists.newArrayList();
    List<NinjaTargetsValue.Key> keys = Lists.newArrayList();
    try {
      readNinjaTargetValuesWithAllIncludes(rootedPath, env,
          ruleContext.getPrerequisiteArtifacts("srcs", Mode.TARGET), keys, bulkValues);
    } catch (NinjaFileFormatException e) {
      ruleContext.getRuleErrorConsumer().throwWithRuleError(e.getMessage());
      return null;
    }
    if (env.valuesMissing()) {
      return null;
    }

    ImmutableSortedMap.Builder<String, NinjaRule> rulesBuilder = ImmutableSortedMap.naturalOrder();
    ImmutableSortedMap.Builder<String, String> variablesBuilder = ImmutableSortedMap.naturalOrder();
    List<NinjaTarget> targets = Lists.newArrayList();
    for (NinjaTargetsValue value : bulkValues) {
      rulesBuilder.putAll(value.getRules());
      variablesBuilder.putAll(value.getVariables());
      targets.addAll(value.getTargets());
      // todo defaults
    }

    ImmutableSortedMap<String, NinjaRule> rules = rulesBuilder.build();
    ImmutableSortedMap<String, String> variables = variablesBuilder.build();

    return createConfiguredTarget(ruleContext, executable, analysisEnvironment, env, targets, rules,
        variables);
  }

  private RootedPath getRootedPath(Artifact srcArtifact) {
    return RootedPath
        .toRootedPath(srcArtifact.getRoot().getRoot(), srcArtifact.getRootRelativePath());
  }

  private ConfiguredTarget createConfiguredTarget(RuleContext ruleContext, PathFragment executable,
      AnalysisEnvironment analysisEnvironment, Environment env, List<NinjaTarget> targets,
      ImmutableSortedMap<String, NinjaRule> rules, ImmutableSortedMap<String, String> variables)
      throws InterruptedException, RuleErrorException, ActionConflictException {
    NestedSetBuilder<Artifact> outputsBuilder = NestedSetBuilder.stableOrder();
    Map<String, NestedSet<Artifact>> outputGroups = null;

    Artifact executableArtifact = null;
    if (!ONLY_READ_FILE) {
      PathPackageLocator pkgLocator = PrecomputedValue.PATH_PACKAGE_LOCATOR.get(env);
      BlacklistedPackagePrefixesValue blacklistedPrefixes =
          (BlacklistedPackagePrefixesValue) env.getValue(BlacklistedPackagePrefixesValue.key());

      BlazeDirectories directories = Preconditions.checkNotNull(ruleContext.getConfiguration())
          .getDirectories();
      Path workspaceRoot = directories.getWorkspace();
      Map<PathFragment, Artifact> aliases = fillDepsMapping(ruleContext);

      PathFragment shExecutable = ShToolchain.getPathOrError(ruleContext);
      if (ruleContext.hasErrors()) {
        return null;
      }
      Map<String, String> exportTargets = Preconditions.checkNotNull(ruleContext.attributes()
          .get("export_targets", Type.STRING_DICT));
      outputGroups = Maps.newHashMapWithExpectedSize(exportTargets.size());
      Map<PathFragment, String> requestedTargets =
          Maps.newHashMapWithExpectedSize(exportTargets.size());
      for (Map.Entry<String, String> entry : exportTargets.entrySet()) {
        requestedTargets.put(PathFragment.create(entry.getKey()), entry.getValue());
      }

      // filter only targets, needed for output
      // todo if export targets are not set, defaults should be used for filtering
      targets = filterOnlyNeededTargetsAndReplaceAliases(executable, targets, requestedTargets);
      Set<String> generatedFiles = getGeneratedFiles(targets);
      RootsContext rootsContext = new RootsContext(Preconditions.checkNotNull(pkgLocator),
          workspaceRoot,
          Preconditions.checkNotNull(blacklistedPrefixes).getPatterns(), analysisEnvironment,
          aliases, generatedFiles);

      for (NinjaTarget target : targets) {
        String command = target.getCommand();
        NinjaRule ninjaRule = rules.get(command);
        if (ninjaRule == null) {
          ruleContext.getRuleErrorConsumer()
              .throwWithRuleError("Ninja: no such rule found: " + command);
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
                    Preconditions
                        .checkNotNull(requestedTargets.remove(requestedPathFragment.get()));
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
        .addProvider(RunfilesProvider.class, runfilesProvider);
    if (outputGroups != null) {
      builder.addOutputGroups(outputGroups);
    }
    return builder.build();
  }

  private Set<String> getGeneratedFiles(List<NinjaTarget> targets) {
    Set<String> set = Sets.newHashSet();
    for (NinjaTarget target : targets) {
      set.addAll(target.getImplicitOutputs());
      set.addAll(target.getOutputs());
      // But not order only outputs.
    }
    return set;
  }

  private List<NinjaTarget> filterOnlyNeededTargetsAndReplaceAliases(PathFragment executable,
      List<NinjaTarget> targets, Map<PathFragment, String> requestedTargets) {
    ArrayDeque<PathFragment> queue = new ArrayDeque<>();
    queue.addAll(requestedTargets.keySet());
    if (executable != null && !executable.isEmpty()) {
      queue.add(executable);
    }
    if (queue.isEmpty()) {
      return replaceAliases(Lists.newArrayList(targets));
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

    return replaceAliases(filteredTargets);
  }

  @VisibleForTesting
  public static List<NinjaTarget> replaceAliases(Collection<NinjaTarget> filteredTargets) {
    // now replace aliases
    // todo we could also cache that
    Multimap<PathFragment, PathFragment> inputsReplaceMap = Multimaps.newListMultimap(
        Maps.newHashMap(), Lists::newArrayList
    );

    for (NinjaTarget ninjaTarget : filteredTargets) {
      if ("phony".equals(ninjaTarget.getCommand())) {
        PathFragment alias = PathFragment.create(ninjaTarget.getOutputs().get(0));
        List<PathFragment> inputs = Lists.newArrayList();

        Consumer<String> consumer = p -> inputs.add(PathFragment.create(p));
        ninjaTarget.getInputs().forEach(consumer);
        ninjaTarget.getImplicitInputs().forEach(consumer);
        ninjaTarget.getOrderOnlyInputs().forEach(consumer);

        inputsReplaceMap.putAll(alias, inputs);
      }
    }
    // also replace in in replace map
    ArrayList<PathFragment> copy = Lists.newArrayList(inputsReplaceMap.keys());
    for (PathFragment alias : copy) {
      Collection<PathFragment> inputs = inputsReplaceMap.get(alias);

      for (PathFragment key : copy) {
        if (alias.equals(key)) {
          // no self replace
          continue;
        }
        List<PathFragment> fragments = (List<PathFragment>) inputsReplaceMap.get(key);
        if (fragments.remove(alias)) {
          fragments.addAll(inputs);
        }
      }
    }
    // we do not need aliases any more
    filteredTargets.removeIf(ninjaTarget -> "phony".equals(ninjaTarget.getCommand()));

    List<NinjaTarget> replacedAliases = Lists.newArrayListWithCapacity(filteredTargets.size());
    for (NinjaTarget ninjaTarget : filteredTargets) {
      ImmutableList<String> inputs = replaceAliases(ninjaTarget.getInputs(), inputsReplaceMap);
      ImmutableList<String> implicitInputs = replaceAliases(ninjaTarget.getImplicitInputs(),
          inputsReplaceMap);
      ImmutableList<String> orderOnlyInputs = replaceAliases(ninjaTarget.getOrderOnlyInputs(),
          inputsReplaceMap);
      if (inputs == null && implicitInputs == null && orderOnlyInputs == null) {
        replacedAliases.add(ninjaTarget);
      } else {
        NinjaTarget newTarget = new NinjaTarget(ninjaTarget.getCommand(),
            selectNonNull(inputs, ninjaTarget.getInputs()),
            selectNonNull(implicitInputs, ninjaTarget.getImplicitInputs()),
            selectNonNull(orderOnlyInputs, ninjaTarget.getOrderOnlyInputs()),
            ninjaTarget.getOutputs(),
            ninjaTarget.getImplicitOutputs(),
            ninjaTarget.getVariables());
        replacedAliases.add(newTarget);
      }
    }

    return replacedAliases;
  }

  private static ImmutableList<String> selectNonNull(@Nullable ImmutableList<String> filtered,
      ImmutableList<String> defaultValue) {
    if (filtered != null) {
      return filtered;
    } else {
      return defaultValue;
    }
  }

  @Nullable
  private static ImmutableList<String> replaceAliases(ImmutableList<String> inputs,
      Multimap<PathFragment, PathFragment> inputsReplaceMap) {
    List<String> out = Lists.newArrayList();
    boolean anythingReplaced = false;
    for (String input : inputs) {
      Collection<PathFragment> fragments = inputsReplaceMap.get(PathFragment.create(input));
      if (fragments == null || fragments.isEmpty()) {
        out.add(input);
      } else {
        anythingReplaced = true;
        fragments.forEach(pf -> out.add(pf.getPathString()));
      }
    }
    return anythingReplaced ? ImmutableList.copyOf(out) : null;
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

  private void readNinjaTargetValuesWithAllIncludes(
      RootedPath rootedPath,
      Environment env,
      PrerequisiteArtifacts srcs,
      List<NinjaTargetsValue.Key> keys,
      List<NinjaTargetsValue> bulkValues)
      throws InterruptedException, NinjaFileFormatException {

    ArrayDeque<RootedPath> queue = new ArrayDeque<>();
    queue.add(rootedPath);

    while (!queue.isEmpty()) {
      RootedPath path = queue.removeFirst();
      List<NinjaTargetsValue> values = requestNinjaTargets(path, env, keys);
      for (NinjaTargetsValue value : values) {
        if (value != null) {
          bulkValues.add(value);
          queue.addAll(parseIncludes(srcs, value.getIncludeStatements()));
        }
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

  private List<NinjaTargetsValue> requestNinjaTargets(
      RootedPath rootedPath,
      Environment env,
      List<NinjaTargetsValue.Key> keys) throws InterruptedException {
    List<NinjaTargetsValue> result = Lists.newArrayList();

    int numFileChunks = getNumFileChunks(rootedPath);
    System.out.println("# ONLY READ: " + ONLY_READ_FILE + "# FILE CHUNKS: " + numFileChunks);
    for (int i = 0; i < numFileChunks; i++) {
      NinjaTargetsValue.Key key = NinjaTargetsValue.key(rootedPath, i, i * CHUNK_SIZE, CHUNK_SIZE);
      keys.add(key);
      result.add((NinjaTargetsValue) env.getValue(key));
    }
    return result;
  }

  private int getNumFileChunks(RootedPath rootedPath) {
    if (CHUNK_SIZE < 0) {
      return 1;
    }
    return (int) Math.ceil((double) rootedPath.asPath().getPathFile().length() / CHUNK_SIZE);
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

    for (String input : target.getImplicitInputs()) {
      rootsContext.addArtifacts(inputsBuilder, input, true);
    }
    for (String input : target.getOrderOnlyInputs()) {
      rootsContext.addArtifacts(inputsBuilder, input, true);
    }

    NestedSetBuilder<Artifact> outputsBuilder = NestedSetBuilder.stableOrder();
    for (String output : target.getOutputs()) {
      rootsContext.addArtifacts(outputsBuilder, output, false);
    }

    for (String output : target.getImplicitOutputs()) {
      rootsContext.addArtifacts(outputsBuilder, output, false);
    }

    // todo just input & output for now
    CommandHelper commandHelper = CommandHelper
        .builder(ruleContext)
        .addLabelMap(labelMap.build())
        .build();

    String command = replaceParameters(target, rule, variables, rootsContext::maybeReplaceAliases);
    String outPath = String.join(", ", target.getOutputs());
    List<String> argv = commandHelper.buildCommandLine(shExecutable,
        command,
        inputsBuilder,
        ".ninjarule_script.sh");

    NestedSet<Artifact> filesToBuild = outputsBuilder.build();
    GenRuleAction action = new GenRuleAction(
        ruleContext.getActionOwner(),
        ImmutableList.copyOf(commandHelper.getResolvedTools()),
        inputsBuilder.build(),
        filesToBuild,
        CommandLines.of(argv),
        ruleContext.getConfiguration().getActionEnvironment(),
        ImmutableMap.copyOf(createExecutionInfo(ruleContext)),
        CompositeRunfilesSupplier.fromSuppliers(commandHelper.getToolsRunfilesSuppliers()),
        String.format("Ninja: building '%s'", outPath));

    ruleContext.registerAction(action);
    return filesToBuild;
  }

  @VisibleForTesting
  public static String replaceParameters(NinjaTarget target, NinjaRule rule,
      ImmutableSortedMap<String, String> variables,
      Function<ImmutableList<String>, ImmutableList<String>> replacer)
      throws NinjaFileFormatException {
    Map<String, String> parameters = Maps.newHashMap();
    rule.getParameters().forEach((key, value) -> parameters.put(key.name(), value));
    parameters.put(ParameterName.in.name(), String.join(" ", replacer.apply(target.getInputs())));
    parameters.put(ParameterName.in_newline.name(), String.join("\n", replacer.apply(target.getInputs())));
    parameters.put(ParameterName.out.name(), String.join(" ", replacer.apply(target.getOutputs())));

    // Merge variable defined in target so that they override correctly.
    parameters.putAll(target.getVariables());

    ImmutableSortedMap<String, String> replacedParameters = replaceVariablesInVariables(
        variables, ImmutableSortedMap.copyOf(parameters));
    String command = replacedParameters.get(ParameterName.command.name());
    for (Map.Entry<String, String> entry : ESCAPE_REPLACEMENTS.entrySet()) {
      command = command.replace(entry.getKey(), entry.getValue());
    }
    return command;
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
    // private final ArtifactRoot absoluteRoot;
    private Root workspaceRoot;
    private final Map<String, Artifact> artifactCache;
    private final FileSystem fs;
    private final ImmutableSet<PathFragment> blacklistedPackages;
    private final AnalysisEnvironment analysisEnvironment;
    private final Map<PathFragment, Artifact> aliases;
    private final Set<String> generatedFiles;
    private ArtifactRoot execRoot;

    private RootsContext(PathPackageLocator pkgLocator, Path workspaceRoot,
        ImmutableSet<PathFragment> blacklistedPackages,
        AnalysisEnvironment analysisEnvironment,
        Map<PathFragment, Artifact> aliases, Set<String> generatedFiles) {
      this.pkgLocator = pkgLocator;
      this.workspaceRoot = Root.fromPath(workspaceRoot);
      this.blacklistedPackages = blacklistedPackages;
      this.analysisEnvironment = analysisEnvironment;
      this.aliases = aliases;
      this.generatedFiles = generatedFiles;
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

      // todo better use path fragments for comparing paths
      if (isInput && !generatedFiles.contains(path)) {
        if (fsPath.isDirectory()) {
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
      execRoot = ArtifactRoot.underWorkspaceOutputRoot(workspaceRoot.asPath(),
          PathFragment.EMPTY_FRAGMENT);
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

    @Nullable
    private Root getSourceRoot(PathFragment fragment, Path fsPath) {
      if (!fsPath.exists()) {
        return null;
      }
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
  }
}
