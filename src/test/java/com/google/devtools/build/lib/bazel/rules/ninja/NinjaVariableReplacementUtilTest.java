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
import static com.google.devtools.build.lib.bazel.rules.ninja.NinjaVariableReplacementUtil.*;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.bazel.rules.ninja.NinjaRule.ParameterName;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class NinjaVariableReplacementUtilTest {
  @Test
  public void testReplacementInString() throws Exception {
    StringReplacementTester tester = new StringReplacementTester();

    tester.test("", "");
    tester.test("abcde", "abcde");
    tester.test("$one", "ein");
    tester.test("$two", "duo");
    tester.test("${two}", "duo");
    tester.test("${three.3}", "tres");
    tester.test("${with-slash}", "-");
    tester.test(" ${with-slash} ", " - ");
    tester.test("$one ${with-slash} ", "ein - ");
    tester.test("345$one 5 ${with-slash} ", "345ein 5 - ");
    tester.test("345$one${two}5 ${with-slash} ", "345einduo5 - ");
  }

  @Test
  public void testReplaceGlobals() throws Exception {
    new MapReplacementTester().test();
    new MapReplacementTester("one", "$two", "two", "!${three.3}")
        .withExpected("one", "!tres 3", "two", "!tres 3")
        .test();
    try {
      new MapReplacementTester("one", "+++$two", "two", "!${three.3}", "cycle", "$one.15")
          .test();
      Assert.fail();
    } catch (NinjaFileFormatException e) {
      // cycle caught
    }
  }

  @Test
  public void testReplaceInLinkerCommand() throws Exception {
    Map<String, String> parameters = Maps.newHashMap();
    parameters.put("cc", "clang");
    parameters.put("cclinkerflags", "-lstdc++");

    parameters.put(ParameterName.in.name(), String.join(" ", new String[]{"out/hello.o"}));
    parameters.put(ParameterName.out.name(), String.join(" ", new String[]{"out/hello"}));

    parameters.put(ParameterName.command.name(), "$cc $cclinkerflags $in -o $out");

    ImmutableSortedMap<String, String> replacedParameters =
        replaceVariablesInVariables(ImmutableSortedMap.of(), ImmutableSortedMap.copyOf(parameters));

    assertThat(replacedParameters.get(ParameterName.command.name()))
        .isEqualTo("clang -lstdc++ out/hello.o -o out/hello");
  }

  private static class MapReplacementTester {
    private final static ImmutableSortedMap<String, String> EXPECTED = ImmutableSortedMap.of(
        "one", "uno 1",
        "two", "duo 2",
        "three.3", "tres 3",
        "four", "cuatro 4/4",
        "five", "replaced!"
    );
    private ImmutableSortedMap<String, String> expected = EXPECTED;
    private final String[] modifications;

    private MapReplacementTester(String... modifications) {
      this.modifications = modifications;
    }

    public MapReplacementTester withExpected(String... expectedModifications) {
      Preconditions.checkArgument(expectedModifications.length % 2 == 0);
      Map<String, String> copy = Maps.newHashMap();
      copy.putAll(expected);
      for (int i = 0; i < expectedModifications.length; i+=2) {
        copy.put(expectedModifications[i], expectedModifications[i + 1]);
      }
      expected = ImmutableSortedMap.copyOf(copy);
      return this;
    }

    public void test() throws Exception {
      Map<String, String> copy = Maps.newHashMap();
      copy.putAll(expected);
      Preconditions.checkArgument(modifications.length % 2 == 0);
      for (int i = 0; i < modifications.length; i+=2) {
        copy.put(modifications[i], modifications[i + 1]);
      }
      copy.put("five", "$global");
      assertThat(
          replaceVariablesInVariables(ImmutableSortedMap.of("global", "replaced!"),
              ImmutableSortedMap.copyOf(copy)))
          .containsExactlyEntriesIn(expected);
    }
  }

  private static class StringReplacementTester {
    private static final ImmutableSortedMap<String, String> GLOBALS = ImmutableSortedMap.of(
        "one", "uno",
        "two", "duo",
        "three.3", "tres",
        "four", "cuatro",
        "with-slash", "-"
    );
    private static final ImmutableSortedMap<String, String> LOCALS = ImmutableSortedMap.of(
        "one", "ein",
        "four", "vier"
    );

    public void test(String source, String target) throws Exception {
      assertThat(replaceVariables(source, GLOBALS, LOCALS)).isEqualTo(target);
    }
  }
}
