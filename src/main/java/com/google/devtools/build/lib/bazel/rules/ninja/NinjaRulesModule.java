package com.google.devtools.build.lib.bazel.rules.ninja;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.runtime.WorkspaceBuilder;

public class NinjaRulesModule extends BlazeModule {

  @Override
  public void workspaceInit(BlazeRuntime runtime, BlazeDirectories directories,
      WorkspaceBuilder builder) {
    builder.addSkyFunctions(ImmutableMap.of(
        NinjaFileHeaderBulkValue.NINJA_HEADER_BULK, new NinjaFileHeaderBulkFunction(),
        NinjaVariablesValue.NINJA_VARIABLES, new NinjaVariablesFunction(),
        NinjaRulesValue.NINJA_RULES, new NinjaRulesFunction(),
        NinjaTargetsValue.NINJA_TARGETS, new NinjaTargetsFunction()
    ));
  }

  @Override
  public void initializeRuleClasses(ConfiguredRuleClassProvider.Builder builder) {
    builder.addRuleDefinition(new NinjaBuildRule());
  }
}
