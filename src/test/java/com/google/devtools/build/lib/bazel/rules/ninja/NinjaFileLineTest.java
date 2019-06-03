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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.util.Pair;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class NinjaFileLineTest {
  private final static List<Pair<String[], String>> DATA = ImmutableList.of(
      Pair.of(new String[]{"abc"}, "abc"),
      Pair.of(new String[]{"abc\n"}, "abc"),
      Pair.of(new String[]{"abc", "def"}, "abcdef"),
      Pair.of(new String[]{"abc", "def\n"}, "abcdef"),
      Pair.of(new String[]{"abc", "def\n", "ignore"}, "abcdef"),
      Pair.of(new String[]{"abc$\n", "def\n", "ignore"}, "abcdef"),
      Pair.of(new String[]{"abc$ ", "def\n", "ignore"}, "abc$ def"),
      Pair.of(new String[]{"abc$:1", "def\n",}, "abc$:1def"),
      Pair.of(new String[]{"abc$", "$def\n"}, "abc$$def"),
      Pair.of(new String[]{"abc$", "\ndef\n"}, "abcdef"),
      Pair.of(new String[]{"$$\n"}, "$"),
      Pair.of(new String[]{"$\n"}, ""),
      Pair.of(new String[]{""}, ""),
      Pair.of(new String[]{"\n"}, "")
  );

  @Test
  public void testVariants() throws Exception {
    for (Pair<String[], String> pair : DATA) {
      NinjaFileLine line = new NinjaFileLine();
      String[] parts = Preconditions.checkNotNull(pair.getFirst());
      Arrays.stream(parts).forEach(part -> {
        if (!line.isEol()) {
          line.append(part.toCharArray());
        }
      });
      assertThat(line.getLine()).isEqualTo(pair.getSecond());
    }
  }
}
