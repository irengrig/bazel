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

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class NinjaAliasesReplacementTest {
  @Test
  public void testReplacement() throws Exception {
    Multimap<PathFragment, PathFragment> data = Multimaps.newSetMultimap(
        Maps.newHashMap(), Sets::newHashSet
    );
    alias(data, "a", "a1", "b1", "c1");
    alias(data, "b1", "b2", "b3", "c2");
    alias(data, "c1", "c2", "c3");
    alias(data, "c2", "c4", "c5");
    alias(data, "c3", "c18");

    NinjaReplaceAliasesUtil.replaceAliasesInAliasesMap(data);

    assertThat(toStrings(data.get(PathFragment.create("a"))))
        .containsExactly("a1", "b2", "b3", "c18", "c4", "c5");
    assertThat(toStrings(data.get(PathFragment.create("b1"))))
        .containsExactly("b2", "b3", "c4", "c5");
    assertThat(toStrings(data.get(PathFragment.create("c1"))))
        .containsExactly("c18", "c4", "c5");
    assertThat(toStrings(data.get(PathFragment.create("c2"))))
        .containsExactly("c4", "c5");
    assertThat(toStrings(data.get(PathFragment.create("c3"))))
        .containsExactly("c18");
  }

  private void alias(Multimap<PathFragment, PathFragment> data, String alias, String... mapped) {
    Arrays.stream(mapped).forEach(mappedPath ->
        data.put(PathFragment.create(alias), PathFragment.create(mappedPath)));
  }

  private Set<String> toStrings(Collection<PathFragment> paths) {
    return paths.stream().map(PathFragment::getPathString).collect(Collectors.toSet());
  }
}
