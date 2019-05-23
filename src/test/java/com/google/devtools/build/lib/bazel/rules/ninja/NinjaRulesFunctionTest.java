package com.google.devtools.build.lib.bazel.rules.ninja;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.devtools.build.lib.bazel.rules.ninja.NinjaRule.ParameterName;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class NinjaRulesFunctionTest {

  @Test
  public void testParseRules() throws Exception {
    ImmutableList<String> lines = ImmutableList.of("rule compile",
        "  command = $cc $cflags -c $in -o $out",
        "",
        "rule link",
        "  command = $cc $in -o $out",
        "");
    NinjaRulesValue value = NinjaRulesFunction.compute(lines);
    assertThat(value).isNotNull();
    ImmutableSortedMap.Builder<String, NinjaRule> builder = ImmutableSortedMap.naturalOrder();
    builder.put("compile", new NinjaRule(ImmutableSortedMap.of(ParameterName.command, "$cc $cflags -c $in -o $out")));
    builder.put("link", new NinjaRule(ImmutableSortedMap.of(ParameterName.command, "$cc $in -o $out")));
    assertThat(value.getRules()).containsExactlyEntriesIn(builder.build());
  }

  @Test
  public void testParseEmptyRules() throws Exception {
    NinjaRulesValue value = NinjaRulesFunction.compute(ImmutableList.of());
    assertThat(value).isNotNull();
    assertThat(value.getRules()).isEmpty();
  }
}
