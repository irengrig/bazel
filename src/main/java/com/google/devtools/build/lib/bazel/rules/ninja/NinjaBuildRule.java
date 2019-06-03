package com.google.devtools.build.lib.bazel.rules.ninja;

import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.packages.BuildType.LABEL;
import static com.google.devtools.build.lib.syntax.Type.BOOLEAN;

import com.google.common.base.Preconditions;
import com.google.devtools.build.lib.analysis.BaseRuleClasses;
import com.google.devtools.build.lib.analysis.RuleDefinition;
import com.google.devtools.build.lib.analysis.RuleDefinitionEnvironment;
import com.google.devtools.build.lib.analysis.config.HostTransition;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.AttributeMap;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClass.Builder.RuleClassType;
import com.google.devtools.build.lib.syntax.Type;
import com.google.devtools.build.lib.syntax.Type.ConversionException;
import com.google.devtools.build.lib.util.FileTypeSet;

public class NinjaBuildRule implements RuleDefinition {
  @Override
  public RuleClass build(RuleClass.Builder builder, RuleDefinitionEnvironment environment) {
    try {
      return builder
          .setOutputToGenfiles()
          .add(attr("src", LABEL).allowedFileTypes(FileTypeSet.ANY_FILE)
              .cfg(HostTransition.createFactory()))
          .add(attr("executable_target", Type.STRING).defaultValue(""))
          .add(
              attr("$is_executable", BOOLEAN)
                  .nonconfigurable("Called from RunCommand.isExecutable, which takes a Target")
                  .value(
                      new Attribute.ComputedDefault() {
                        @Override
                        public Object getDefault(AttributeMap rule) {
                          return true;
                          // return !rule.get("executable_target", Type.STRING).trim().isEmpty();
                        }
                      }))
          .build();
    } catch (ConversionException e) {
      // should not happen
      Preconditions.checkArgument(false, e.getMessage());
      return null;
    }
  }

  @Override
  public Metadata getMetadata() {
    return RuleDefinition.Metadata.builder()
        .name("ninja_build")
        .type(RuleClassType.NORMAL)
        .ancestors(BaseRuleClasses.BaseRule.class)
        .factoryClass(NinjaBuildRuleConfiguredTargetFactory.class)
        .build();
  }
}
