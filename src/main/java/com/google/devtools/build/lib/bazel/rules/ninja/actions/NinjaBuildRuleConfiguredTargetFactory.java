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
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.DerivedArtifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.CommandLines;
import com.google.devtools.build.lib.actions.EmptyRunfilesSupplier;
import com.google.devtools.build.lib.actions.MutableActionGraph.ActionConflictException;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetBuilder;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.RunfilesProvider;
import com.google.devtools.build.lib.analysis.ShToolchain;
import com.google.devtools.build.lib.analysis.actions.FileWriteAction;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.bazel.rules.ninja.parser.NinjaRule;
import com.google.devtools.build.lib.bazel.rules.ninja.parser.NinjaRuleVariable;
import com.google.devtools.build.lib.bazel.rules.ninja.parser.NinjaScope;
import com.google.devtools.build.lib.bazel.rules.ninja.parser.NinjaScopeId;
import com.google.devtools.build.lib.bazel.rules.ninja.parser.NinjaTarget;
import com.google.devtools.build.lib.bazel.rules.ninja.parser.NinjaVariableValue;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.packages.AttributeMap;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class NinjaBuildRuleConfiguredTargetFactory implements RuleConfiguredTargetFactory {
  @Override
  public ConfiguredTarget create(RuleContext ruleContext)
      throws InterruptedException, RuleErrorException, ActionConflictException {
    NinjaGraphProvider graphProvider =
        ruleContext.getPrerequisite("ninja_graph", Mode.TARGET, NinjaGraphProvider.class);
    Preconditions.checkNotNull(graphProvider);
    NinjaGraphFilesProvider graphFilesProvider =
        ruleContext.getPrerequisite("ninja_graph", Mode.TARGET, NinjaGraphFilesProvider.class);
    Preconditions.checkNotNull(graphFilesProvider);

    AttributeMap attributes = ruleContext.attributes();
    List<String> targets = attributes.get("targets", Type.STRING_LIST);
    Preconditions.checkNotNull(targets);

    List<PathFragment> targetPaths = targets.stream().map(PathFragment::create)
        .collect(Collectors.toList());
    // todo move to provider?
    ImmutableSortedSet<PathFragment> phonyTargets = getPhonyTargets(graphProvider.getTargets());
    // todo maybe just pass graph provider?
    ActionCreator actionCreator = new ActionCreator(ruleContext, graphProvider.getScope(),
        PathFragment.create(graphFilesProvider.getOutputRoot()), graphProvider.getRepositoryName(), phonyTargets,
        graphProvider.getTargets(), targetPaths);
    reduce(graphProvider.getTargets(), targetPaths, actionCreator::createAction);

    // todo later
    // Map<String, String> exportTargets = Preconditions.checkNotNull(attributes
    //     .get("export_targets", Type.STRING_DICT));

    Artifact ninjaLog = FileWriteAction.createFile(ruleContext, "ninja.log",
        "This should be lazy!", false);
    NestedSetBuilder<Artifact> outputsBuilder = NestedSetBuilder.stableOrder();
    outputsBuilder.add(ninjaLog);
    outputsBuilder.addAll(actionCreator.getDirectlyRequestedArtifacts());
    NestedSet<Artifact> transitiveOutputs = outputsBuilder.build();

    RuleConfiguredTargetBuilder builder = new RuleConfiguredTargetBuilder(ruleContext)
        .setFilesToBuild(transitiveOutputs)
        .setRunfilesSupport(null, null)
        .addProvider(RunfilesProvider.class, RunfilesProvider.EMPTY);
    // if (outputGroups != null) {
    //   builder.addOutputGroups(outputGroups);
    // }
    return builder.build();
  }

  private ImmutableSortedSet<PathFragment> getPhonyTargets(
      ImmutableSortedMap<PathFragment, NinjaTarget> targets) {
    ImmutableSortedSet.Builder<PathFragment> builder = ImmutableSortedSet.naturalOrder();
    targets.forEach((key, value) -> {
      if ("phony".equals(value.getRuleName())) {
        builder.add(key);
      }
    });
    return builder.build();
  }

  private static class ActionCreator {
    private final ImmutableSortedMap<NinjaScopeId, NinjaScope> map;
    private final RuleContext ruleContext;
    private final PathFragment outputRootPath;
    private final RepositoryName repositoryName;
    private final ImmutableSortedSet<PathFragment> phonyTargets;
    private final ImmutableSortedMap<PathFragment, NinjaTarget> targets;
    private final List<PathFragment> directlyRequestedTargets;
    private final List<Artifact> directlyRequestedArtifacts;
    private final Path execRoot;
    private final ArtifactRoot outputRoot;
    private final ImmutableSortedMap<String, String> executionInfo;
    private final PathFragment shExecutable;
    private final Root root;

    private ActionCreator(
        RuleContext ruleContext,
        NinjaScope scope,
        PathFragment outputRootPath,
        RepositoryName repositoryName,
        ImmutableSortedSet<PathFragment> phonyTargets,
        ImmutableSortedMap<PathFragment, NinjaTarget> targets,
        List<PathFragment> directlyRequestedTargets) {
      this.ruleContext = ruleContext;
      this.outputRootPath = outputRootPath;
      this.repositoryName = repositoryName;
      this.phonyTargets = phonyTargets;
      this.targets = targets;
      this.directlyRequestedTargets = directlyRequestedTargets;
      this.directlyRequestedArtifacts = Lists.newArrayList();
      ImmutableSortedMap.Builder<NinjaScopeId, NinjaScope> builder =
          ImmutableSortedMap.naturalOrder();
      scope.iterate(currentScope -> builder.put(currentScope.getNinjaScopeId(), currentScope));
      map = builder.build();
      String workspaceName = repositoryName.isMain() ? ruleContext.getWorkspaceName()
          : repositoryName.strippedName();
      execRoot = Preconditions.checkNotNull(ruleContext.getConfiguration()).getDirectories()
          .getExecRoot(workspaceName);
      this.outputRoot = ArtifactRoot.asDerivedRoot(execRoot, outputRootPath);
      executionInfo = createExecutionInfo(ruleContext);
      shExecutable = ShToolchain.getPathOrError(ruleContext);
      PathFragment sourcePathFragment = ruleContext.getLabel().getPackageIdentifier().getSourceRoot();
      root = Root.fromPath(ruleContext.getConfiguration().getDirectories().getWorkspace().getRelative(sourcePathFragment));
    }

    public void createAction(NinjaTarget target) {
      NinjaScope scope = map.get(target.getScopeId());
      Preconditions.checkNotNull(scope);
      String ruleName = target.getRuleName();

      if ("phony".equals(ruleName)) {
        if (target.getAllOutputs().size() != 1) {
          String allOutputs = target.getAllOutputs().stream()
              .map(PathFragment::getPathString).collect(Collectors.joining(" "));
          ruleContext.ruleError(
              String.format("Ninja phony alias can only be used for single output, but found '%s'.",
              allOutputs));
          // todo check for errors in the parent cycle
        }
        // we do not register phony actions
      } else {
        NinjaRule rule = scope.findRule(target.getOffset(), ruleName);
        addNinjaAction(scope, rule, target);
      }
    }

    private void addNinjaAction(
        NinjaScope scope, NinjaRule rule,
        NinjaTarget target) {
      NestedSetBuilder<Artifact> inputsBuilder = NestedSetBuilder.stableOrder();
      ImmutableList.Builder<Artifact> outputsBuilder = ImmutableList.builder();
      List<PathFragment> inputPathFragments = Lists.newArrayList();

      fillArtifacts(target, inputsBuilder, inputPathFragments, outputsBuilder);

      NinjaScope targetScope = createTargetScope(scope, target, inputPathFragments);
      int targetOffset = target.getOffset();
      maybeCreateRspFile(rule, targetScope, targetOffset, inputsBuilder);

      CommandLines commandLines = createCommandLine(rule, targetScope, targetOffset);
      ruleContext.registerAction(new NinjaGenericAction(
          ruleContext.getActionOwner(),
          ImmutableList.of(),
          inputsBuilder.build(),
          outputsBuilder.build(),
          commandLines,
          ruleContext.getConfiguration().getActionEnvironment(),
          executionInfo,
          EmptyRunfilesSupplier.INSTANCE
      ));
    }

    private static ImmutableSortedMap<String, String> createExecutionInfo(RuleContext ruleContext) {
      ImmutableSortedMap.Builder<String, String> builder = ImmutableSortedMap.naturalOrder();
      builder.putAll(TargetUtils.getExecutionInfo(ruleContext.getRule()));
      builder.put("local", "");
      ImmutableSortedMap<String, String> map = builder.build();
      ruleContext.getConfiguration().modifyExecutionInfo(map, "NinjaRule");
      return map;
    }

    private void maybeCreateRspFile(NinjaRule rule, NinjaScope targetScope, int targetOffset,
        NestedSetBuilder<Artifact> inputsBuilder) {
      NinjaVariableValue value = rule.getVariables().get(NinjaRuleVariable.RSPFILE);
      NinjaVariableValue content = rule.getVariables().get(NinjaRuleVariable.RSPFILE_CONTENT);
      if (value == null && content == null) {
        return;
      }
      if (value == null || content == null) {
        // todo move to parsing error?
        ruleContext.ruleError(String.format("Both rspfile and rspfile_content should be defined for rule '%s'.", rule.getName()));
        return;
      }
      String fileName = targetScope.getExpandedValue(targetOffset, value);
      String contentString = targetScope.getExpandedValue(targetOffset, content);
      if (!fileName.trim().isEmpty()) {
        DerivedArtifact derivedArtifact =
            ruleContext.getDerivedArtifact(PathFragment.create(fileName), outputRoot);
        FileWriteAction fileWriteAction = FileWriteAction
            .create(ruleContext, derivedArtifact, contentString, false);
        ruleContext.registerAction(fileWriteAction);
        inputsBuilder.add(derivedArtifact);
      }
    }

    private CommandLines createCommandLine(NinjaRule rule, NinjaScope targetScope, int targetOffset) {
      String command = targetScope
          .getExpandedValue(targetOffset, rule.getVariables().get(NinjaRuleVariable.COMMAND));
      return CommandLines.of(ImmutableList.of(shExecutable.getPathString(), "-c", command));
    }

    private NinjaScope createTargetScope(NinjaScope scope, NinjaTarget target,
        List<PathFragment> inputPathFragments) {
      ImmutableSortedMap.Builder<String, List<Pair<Integer, String>>> builder =
          ImmutableSortedMap.naturalOrder();
      target.getVariables()
          .forEach((key, value) -> builder.put(key, ImmutableList.of(Pair.of(0, value))));
      String inNewline = inputPathFragments.stream().map(PathFragment::getPathString)
          .collect(Collectors.joining("\n"));
      String out = target.getAllOutputs().stream().map(PathFragment::getPathString)
          .collect(Collectors.joining(" "));
      builder.put("in", ImmutableList.of(Pair.of(0, inNewline.replace("\n", " "))));
      builder.put("in_newline", ImmutableList.of(Pair.of(0, inNewline)));
      builder.put("out", ImmutableList.of(Pair.of(0, out)));

      return scope.createTargetsScope(builder.build());
    }

    private void fillPhonyInputs(PathFragment phonyInput, NestedSetBuilder<Artifact> inputsBuilder,
        List<PathFragment> inputPathFragments) {
      ArrayDeque<PathFragment> queue = new ArrayDeque<>();
      queue.add(phonyInput);
      while (!queue.isEmpty()) {
        PathFragment fragment = queue.remove();
        Collection<PathFragment> inputs = Preconditions.checkNotNull(targets.get(fragment))
            .getAllInputs();
        for (PathFragment input : inputs) {
          if (phonyTargets.contains(input)) {
            queue.add(input);
          } else {
            inputsBuilder.add(getInputArtifact(input, inputPathFragments));
          }
        }
      }
    }

    private Artifact getInputArtifact(PathFragment input,
        List<PathFragment> inputPathFragments) {
      inputPathFragments.add(input);
      if (input.startsWith(outputRootPath)) {
        // output of other action
        return ruleContext.getDerivedArtifact(input.relativeTo(outputRootPath), outputRoot);
      } else {
        return ruleContext.getAnalysisEnvironment().getSourceArtifact(input, root);
      }
    }

    private void fillArtifacts(NinjaTarget target, NestedSetBuilder<Artifact> inputsBuilder,
        List<PathFragment> inputPathFragments,
        ImmutableList.Builder<Artifact> outputsBuilder) {
      for (PathFragment input : target.getAllInputs()) {
        if (phonyTargets.contains(input)) {
          fillPhonyInputs(input, inputsBuilder, inputPathFragments);
        } else {
          inputsBuilder.add(getInputArtifact(input, inputPathFragments));
        }
      }
      for (PathFragment output : target.getAllOutputs()) {
        DerivedArtifact derivedArtifact =
            ruleContext.getDerivedArtifact(output.relativeTo(outputRootPath), outputRoot);
        outputsBuilder.add(derivedArtifact);
        // todo this is probably not effective, move out
        if (directlyRequestedTargets.contains(output)) {
          directlyRequestedArtifacts.add(derivedArtifact);
        }
      }
    }

    public List<Artifact> getDirectlyRequestedArtifacts() {
      return directlyRequestedArtifacts;
    }
  }

  public void reduce(
      ImmutableSortedMap<PathFragment, NinjaTarget> targets,
      Collection<PathFragment> directlyRequestedOutputs,
      Consumer<NinjaTarget> targetConsumer) {
    Set<PathFragment> visited = Sets.newHashSet();
    visited.addAll(directlyRequestedOutputs);
    ArrayDeque<PathFragment> queue = new ArrayDeque<>(directlyRequestedOutputs);
    while (!queue.isEmpty()) {
      PathFragment fragment = queue.remove();
      NinjaTarget target = targets.get(fragment);
      if (target != null) {
        Collection<PathFragment> inputs = target.getAllInputs();
        for (PathFragment input : inputs) {
          if (!visited.contains(input)) {
            visited.add(input);
            queue.add(input);
          }
        }
      }
    }
    Set<NinjaTarget> filtered = Sets.newHashSet();
    for (NinjaTarget target : targets.values()) {
      for (PathFragment output : target.getAllOutputs()) {
        if (visited.contains(output)) {
          // If target is visited, do not check other outputs, break.
          if (filtered.add(target)) {
            // If the target has not been reported before, report it to consumer.
            targetConsumer.accept(target);
          }
          break;
        }
      }
    }
  }
}
