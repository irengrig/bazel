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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class NinjaVariablesFunctionTest {

  @Test
  public void testParseVariables() throws Exception {
    ImmutableList<String> lines = ImmutableList.of("# build.ninja",
        "cc     = clang",
        "cflags = -Weverything",
        "other = one two ",
        "");
    NinjaVariablesValue value = NinjaVariablesFunction.compute(lines);
    assertThat(value).isNotNull();
    ImmutableSortedMap.Builder<String, String> builder = ImmutableSortedMap.naturalOrder();
    builder.put("cc", "clang");
    builder.put("cflags", "-Weverything");
    builder.put("other", "one two");
    assertThat(value.getVariables()).containsExactlyEntriesIn(builder.build());
  }

  @Test
  public void testParseEmptyVariables() throws Exception {
    NinjaVariablesValue value = NinjaVariablesFunction.compute(ImmutableList.of());
    assertThat(value).isNotNull();
    assertThat(value.getVariables()).isEmpty();
  }
}
