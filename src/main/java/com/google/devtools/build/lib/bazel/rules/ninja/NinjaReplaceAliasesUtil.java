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

import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class NinjaReplaceAliasesUtil {
  public static void replaceAliasesInAliasesMap(
      Multimap<PathFragment, PathFragment> changeable) {
    Set<PathFragment> interestingAtAll = Sets.newHashSet();
    for (PathFragment key : changeable.keys()) {
      for (PathFragment inner : changeable.keys()) {
        if (key.equals(inner)) {
          continue;
        }
        if (changeable.get(inner).contains(key)) {
          interestingAtAll.add(inner);
        }
      }
    }
    Set<PathFragment> copy = Sets.newHashSet(changeable.keys());
    for (PathFragment path : copy) {
      for (PathFragment inner : interestingAtAll) {
        replaceValue(path, changeable.get(inner), changeable.get(path));
      }
    }
  }

  private static void replaceValue(
      PathFragment key,
      Collection<PathFragment> fragments,
      Collection<PathFragment> replacement) {
    if (fragments.contains(key)) {
      List<PathFragment> filteredReplacement = replacement.stream()
          .filter(item -> !fragments.contains(item))
          .collect(Collectors.toList());
      while (fragments.remove(key));
      fragments.addAll(filteredReplacement);
    }
  }
}
