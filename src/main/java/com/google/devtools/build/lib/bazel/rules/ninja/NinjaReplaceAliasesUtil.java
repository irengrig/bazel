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

import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class NinjaReplaceAliasesUtil {
  public static void replaceAliasesInAliasesMap(
      Multimap<PathFragment, PathFragment> changeable) throws NinjaFileFormatException {
    List<PathFragment> copy = Lists.newArrayList(changeable.keys());
    HashSet<PathFragment> set = Sets.newHashSet();
    for (PathFragment path : copy) {
      Collection<PathFragment> replacement = NinjaReplaceAliasesUtil
          .replaceAliasesInAliasesMapRecursively(path, changeable, set);
      if (replacement != null) {
        changeable.putAll(path, replacement);
      }
    }
  }

  private static Collection<PathFragment> replaceAliasesInAliasesMapRecursively(
      PathFragment value,
      Multimap<PathFragment, PathFragment> changeable,
      Set<PathFragment> requested) throws NinjaFileFormatException {
    Collection<PathFragment> replacement = changeable.get(value);
    if (replacement != null && !replacement.isEmpty()) {
      List<PathFragment> copy = Lists.newArrayList(replacement);
      for (PathFragment replacementFragment : copy) {
        if (!requested.add(replacementFragment)) {
          throw new NinjaFileFormatException("Cyclic usage of aliases: " +
              replacementFragment.getPathString());
        }

        Collection<PathFragment> replaceWith =
            replaceAliasesInAliasesMapRecursively(replacementFragment, changeable, requested);
        if (replaceWith != null) {
          replacement.remove(replacementFragment);
          replacement.addAll(replaceWith);
        }
        requested.remove(replacementFragment);
      }
      return replacement;
    }
    return null;
  }
}
