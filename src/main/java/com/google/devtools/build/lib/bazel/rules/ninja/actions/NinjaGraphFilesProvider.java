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

import com.google.devtools.build.lib.analysis.TransitiveInfoProvider;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.Collection;
import java.util.Set;

public class NinjaGraphFilesProvider implements TransitiveInfoProvider {
  private final Label main;
  private final Collection<Label> children;
  private final String buildRoot;
  private final String outputRoot;
  private final Set<PathFragment> preExisting;

  public NinjaGraphFilesProvider(
      Label main,
      Collection<Label> children,
      String buildRoot, String outputRoot, Set<PathFragment> preExisting) {
    this.main = main;
    this.children = children;
    this.buildRoot = buildRoot;
    this.outputRoot = outputRoot;
    this.preExisting = preExisting;
  }

  public Label getMain() {
    return main;
  }

  public Collection<Label> getChildren() {
    return children;
  }

  public String getBuildRoot() {
    return buildRoot;
  }

  public String getOutputRoot() {
    return outputRoot;
  }

  public Set<PathFragment> getPreExisting() {
    return preExisting;
  }
}
