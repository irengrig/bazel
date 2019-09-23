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
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.ActionEnvironment;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.CommandLines;
import com.google.devtools.build.lib.actions.RunfilesSupplier;
import com.google.devtools.build.lib.rules.cpp.CppFileTypes;
import com.google.devtools.build.lib.rules.genrule.GenRuleAction;
import com.google.devtools.build.lib.util.DependencySet;
import com.google.devtools.build.lib.util.io.process.PathUtils;
import com.google.devtools.build.lib.util.io.process.ProcessParameters;
import com.google.devtools.build.lib.util.io.process.ProcessResult;
import com.google.devtools.build.lib.util.io.process.ProcessRunner;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.Root;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NinjaGenRuleAction extends GenRuleAction {

  private final Path workspaceRoot;
  private final Path depfilePath;
  private final String rawCommandLine;
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
      String rawCommandLine) {
    super(owner, tools, inputs, outputs, commandLines, env, executionInfo, runfilesSupplier,
        progressMessage);
    this.workspaceRoot = workspaceRoot;
    this.depfilePath = depfilePath;
    this.rawCommandLine = rawCommandLine;
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

    // todo - have a shared executor :(
    ExecutorService executorService = Executors.newFixedThreadPool(2);
    // todo should not be the exec root because of caching
    try {
      String jsonPath = generateJsonInput(actionExecutionContext);
      ProcessParameters processParameters = ProcessParameters.builder()
          // currently it is hardcoded, but should not be
          .setName("clang-scan-deps")
          .setWorkingDirectory(actionExecutionContext.getExecRoot().getPathFile())
          .setArguments("--compilation-database", jsonPath)
          .build();
      // commented out as it does not work now
      // ProcessResult processResult = new ProcessRunner(processParameters, executorService)
      //     .runSynchronously();
      // System.out.println(processResult.outString());

      List<Artifact> discoveredInputArtifacts = Collections.emptyList();
      discoveredInputs = Sets.newHashSet();
      for (Artifact artifact : discoveredInputArtifacts) {
        discoveredInputs.add(artifact.getPath());
      }
      updateInputs(discoveredInputArtifacts);
      return discoveredInputArtifacts;
    } catch (Exception e) {
      throw new ActionExecutionException(e, this, true);
    }
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
    if (depfilePath != null && rawCommandLine != null) {
      DependencySet dependencySet;
      try {
        byte[] bytes = Files.readAllBytes(depfilePath.getPathFile().toPath());
        dependencySet = new DependencySet(workspaceRoot).process(bytes);
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
          throw new RuntimeException(String.format(
              "Missing actually used input: '%s' for building with command:\n'%s'",
              dependency.getPathString(), rawCommandLine));
        }
      }
    }
    super.afterExecute(actionExecutionContext);
  }

  // todo describeKey() can be overloaded to create always-dirty actions if we ever need them
}
