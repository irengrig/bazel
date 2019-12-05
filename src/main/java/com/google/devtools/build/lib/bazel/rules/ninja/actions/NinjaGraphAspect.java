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
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.devtools.build.lib.actions.MutableActionGraph.ActionConflictException;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ConfiguredAspect;
import com.google.devtools.build.lib.analysis.ConfiguredAspectFactory;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.bazel.rules.ninja.file.GenericParsingException;
import com.google.devtools.build.lib.bazel.rules.ninja.parser.NinjaScope;
import com.google.devtools.build.lib.bazel.rules.ninja.parser.NinjaTarget;
import com.google.devtools.build.lib.bazel.rules.ninja.pipeline.NinjaPipeline;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.concurrent.ExecutorUtil;
import com.google.devtools.build.lib.packages.AspectDefinition;
import com.google.devtools.build.lib.packages.AspectParameters;
import com.google.devtools.build.lib.packages.NativeAspectClass;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetAndData;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public class NinjaGraphAspect extends NativeAspectClass implements ConfiguredAspectFactory {

  @Override
  public ConfiguredAspect create(ConfiguredTargetAndData ctadBase, RuleContext ruleContext,
      AspectParameters parameters, String toolsRepository)
      throws ActionConflictException, InterruptedException {
    ConfiguredAspect.Builder aspect = new ConfiguredAspect.Builder(this, parameters, ruleContext);
    NinjaGraphFilesProvider graphFilesProvider = ctadBase.getConfiguredTarget()
        .getProvider(NinjaGraphFilesProvider.class);
    Preconditions.checkNotNull(graphFilesProvider);

    NinjaGraphProvider graphProvider = createGraphProvider(graphFilesProvider.getMain(),
        graphFilesProvider.getChildren(),
        ruleContext);
    aspect.addProvider(graphProvider);

    return aspect.build();
  }

  @Override
  public AspectDefinition getDefinition(AspectParameters aspectParameters) {
        return new AspectDefinition.Builder(this).propagateAlongAttribute("ninja_build").build();
  }

  private static NinjaGraphProvider createGraphProvider(
      Label mainLabel,
      Collection<Label> children,
      RuleContext ruleContext) throws InterruptedException {
    RepositoryName repository = mainLabel.getPackageIdentifier().getRepository();
    BlazeDirectories directories = Preconditions.checkNotNull(ruleContext.getConfiguration())
        .getDirectories();
    Path workspace = directories.getWorkspace(); // todo?
    // Path basePath = directories.getExecRoot(repository.strippedName());

    ThreadFactory threadFactory = new ThreadFactoryBuilder()
        .setNameFormat(NinjaGraphAspect.class.getSimpleName() + "-%d")
        .build();
    int numThreads = Math.min(25, Runtime.getRuntime().availableProcessors() - 1);
    ListeningExecutorService service =
        MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(numThreads, threadFactory));
    try {
      // todo add assertion about only children accessed in include/subninja
      Path mainPath = workspace.getRelative(mainLabel.toPathFragment());
      NinjaPipeline pipeline = new NinjaPipeline(mainPath.getParentDirectory(), service);
      Pair<NinjaScope, ImmutableSortedMap<PathFragment, NinjaTarget>> pipelineResult =
          pipeline.pipeline(mainPath);

      return
          new NinjaGraphProvider(pipelineResult.getFirst(), pipelineResult.getSecond(), repository);
    } catch (GenericParsingException | IOException e) {
      ruleContext.ruleError(e.getMessage());
    } finally {
      ExecutorUtil.interruptibleShutdown(service);
    }
    return null;
  }
}
