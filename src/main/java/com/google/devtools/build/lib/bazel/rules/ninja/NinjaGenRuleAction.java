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

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.actions.ActionEnvironment;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.CommandLines;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.RunfilesSupplier;
import com.google.devtools.build.lib.rules.cpp.CppFileTypes;
import com.google.devtools.build.lib.rules.cpp.CppIncludeScanningUtil;
import com.google.devtools.build.lib.rules.cpp.IncludeScanner.IncludeScannerSupplier;
import com.google.devtools.build.lib.rules.cpp.IncludeScanner.IncludeScanningHeaderData;
import com.google.devtools.build.lib.rules.cpp.IncludeScanning;
import com.google.devtools.build.lib.rules.genrule.GenRuleAction;
import com.google.devtools.build.lib.util.DependencySet;
import com.google.devtools.build.lib.util.io.process.PathUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;

public class NinjaGenRuleAction extends GenRuleAction {

  private final Path workspaceRoot;
  private final Path depfilePath;
  private final String rawCommandLine;
  private final IncludeScannerSupplier includeScannerSupplier;
  private final Artifact grepIncludes;
  private Set<Path> discoveredInputs;

  public NinjaGenRuleAction(ActionOwner owner,
      Iterable<Artifact> tools,
      Iterable<Artifact> inputs,
      Iterable<Artifact> outputs,
      CommandLines commandLines,
      ActionEnvironment env,
      ImmutableMap<String, String> executionInfo,
      RunfilesSupplier runfilesSupplier,
      CharSequence progressMessage,
      Path workspaceRoot,
      Path depfilePath,
      String rawCommandLine,
      IncludeScannerSupplier includeScannerSupplier,
      Artifact grepIncludes) {
    super(owner, tools, inputs, outputs, commandLines, env, executionInfo, runfilesSupplier,
        progressMessage);
    this.workspaceRoot = workspaceRoot;
    this.depfilePath = depfilePath;
    this.rawCommandLine = rawCommandLine;
    // todo use that
    this.includeScannerSupplier = includeScannerSupplier;
    this.grepIncludes = grepIncludes;
  }

  @Override
  public boolean discoversInputs() {
    return rawCommandLine != null;
  }

  @Override
  public Iterable<Artifact> getAllowedDerivedInputs() {
    return getInputs();
  }

  @Override
  public Iterable<Artifact> discoverInputs(ActionExecutionContext actionExecutionContext)
      throws ActionExecutionException, InterruptedException {
    // todo see CppCompileAction#discoverInputs

    Iterable<Artifact> discoveredInputArtifacts = discoverCppHeadersBazel(actionExecutionContext);
    discoveredInputs = Sets.newHashSet();
    for (Artifact artifact : discoveredInputArtifacts) {
      discoveredInputs.add(artifact.getPath());
    }
    return discoveredInputArtifacts;
  }

  private Iterable<Artifact> discoverCppHeadersBazel(ActionExecutionContext actionExecutionContext)
      throws ActionExecutionException, InterruptedException {
    String[] parts = rawCommandLine.split(" ");
    List<String> options = Lists.newArrayList(Arrays.asList(parts).subList(1, parts.length));
    List<PathFragment> systemIncludeDirs = CppIncludeScanningUtil.getSystemIncludeDirs(
        options, this::prettyPrint);
    List<String> cmdlineIncludes = CppIncludeScanningUtil.getCmdlineIncludes(options);

    ImmutableMap.Builder<PathFragment, Artifact> pathToLegalOutputArtifact =
        ImmutableMap.builder();
    ImmutableList<Artifact> inputsCopy;
    synchronized (this) {
      for (Artifact path : outputs) {
        pathToLegalOutputArtifact.put(path.getExecPath(), path);
      }
      inputsCopy = ImmutableList.copyOf(inputs);
    }

    Artifact mainSource = inputsCopy.stream()
        .filter(input -> input.getExtension().equals("cpp")
            || input.getExtension().equals("cxx")
            || input.getExtension().equals("c"))
        .findFirst().orElse(null);

    try {
      Set<Artifact> includes = Sets.newConcurrentHashSet();
      IncludeScanningHeaderData includeScanningHeaderData = new IncludeScanningHeaderData.Builder(
          pathToLegalOutputArtifact.build(), ImmutableSet.of())
          .setSystemIncludeDirs(systemIncludeDirs)
          .setCmdlineIncludes(cmdlineIncludes)
          .build();
      // todo clarify if to pass discovered include directories
      // todo into both system and quote includes
      ImmutableList<PathFragment> includePaths = ImmutableList.copyOf(systemIncludeDirs);
      ListenableFuture<?> listenableFuture = includeScannerSupplier
          .scannerFor(includePaths, includePaths)
          .processAsync(mainSource, inputsCopy, includeScanningHeaderData,
              cmdlineIncludes, includes, this, actionExecutionContext, grepIncludes);
      ListenableFuture<Iterable<Artifact>> future = IncludeScanning
          .collectIncludes(listenableFuture, actionExecutionContext,
              includes, ImmutableList.of());
      Iterable<Artifact> artifacts = future.get();
      artifacts = filterDiscoveredIncludes(inputsCopy, artifacts);
      updateInputs(artifacts);
      return artifacts;
    } catch (ExecutionException | IOException | ExecException e) {
      throw new ActionExecutionException(e, this, true);
    }
  }

  private Iterable<Artifact> filterDiscoveredIncludes(
      ImmutableList<Artifact> inputsCopy,
      Iterable<Artifact> artifacts) {
    List<Artifact> filtered = Lists.newArrayList(artifacts);
    filtered.removeAll(inputsCopy);
    if (!filtered.isEmpty()) {
      // System.out.println("DISCOVERED: " + filtered);
    }
    return filtered;
  }

  private synchronized String generateJsonInput(ActionExecutionContext actionExecutionContext)
      throws IOException {
    List<String> parts = Lists.newArrayList();
    for (Artifact input : inputs) {
      if (input.isFileType(CppFileTypes.C_SOURCE) || input.isFileType(CppFileTypes.CPP_SOURCE)) {
        Root root = actionExecutionContext.getRoot(input);
        parts.add(String.format("{\"command\": \"%s\", \"directory\": \"%s\", \"file\": \"%s\"}",
            rawCommandLine,
            root.asPath().getPathString(),
            input.getPath().getPathString()));
      }
    }
    //
    File tempFile = File.createTempFile("clang-deps", ".json");
    java.nio.file.Path path = tempFile.toPath();
    PathUtils.writeFile(path, String.format("[%s]", Joiner.on(',').join(parts)));
    return path.toString();
  }

  @Override
  protected void afterExecute(ActionExecutionContext actionExecutionContext) {
    checkInputPaths();
    super.afterExecute(actionExecutionContext);
  }

  private void checkInputPaths() {
    if (depfilePath != null && rawCommandLine != null) {
      DependencySet dependencySet;
      try {
        byte[] bytes = Files.readAllBytes(depfilePath.getPathFile().toPath());
        dependencySet = new DependencySet(workspaceRoot).process(bytes, true);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      Collection<Path> dependencies = dependencySet.getDependencies();
      Set<Path> inputsPaths = Sets.newHashSet();
      synchronized (this) {
        for (Artifact input : inputs) {
          inputsPaths.add(input.getPath());
        }
      }
      for (Path dependency : dependencies) {
        if (!inputsPaths.contains(dependency) && !discoveredInputs.contains(dependency)) {
          String message = String.format("Missing actually used input: ##'%s'##", dependency.getPathString());
          System.out.println(message);
          // throw new RuntimeException(message);
        }
      }
    }
  }

  // todo describeKey() can be overloaded to create always-dirty actions if we ever need them
}
