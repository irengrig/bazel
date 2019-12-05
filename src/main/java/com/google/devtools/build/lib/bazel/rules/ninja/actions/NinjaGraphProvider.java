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

import com.google.common.collect.ImmutableSortedMap;
import com.google.devtools.build.lib.analysis.TransitiveInfoProvider;
import com.google.devtools.build.lib.bazel.rules.ninja.parser.NinjaScope;
import com.google.devtools.build.lib.bazel.rules.ninja.parser.NinjaTarget;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.vfs.PathFragment;

public class NinjaGraphProvider implements TransitiveInfoProvider {
  private final NinjaScope scope;
  private final ImmutableSortedMap<PathFragment, NinjaTarget> targets;
  private final RepositoryName repositoryName;

  public NinjaGraphProvider(NinjaScope scope,
      ImmutableSortedMap<PathFragment, NinjaTarget> targets,
      RepositoryName repositoryName) {
    this.scope = scope;
    this.targets = targets;
    this.repositoryName = repositoryName;
  }

  public NinjaScope getScope() {
    return scope;
  }

  public ImmutableSortedMap<PathFragment, NinjaTarget> getTargets() {
    return targets;
  }

  public RepositoryName getRepositoryName() {
    return repositoryName;
  }
}
