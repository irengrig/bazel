package com.google.devtools.build.lib.bazel.rules.ninja;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Lists;
import com.google.devtools.build.lib.bazel.rules.ninja.NinjaRule.ParameterName;
import com.google.devtools.build.lib.bazel.rules.ninja.NinjaVariablesFunction.NinjaWrongVariablesFormatException;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

public class NinjaRulesFunction implements SkyFunction {
  @Nullable
  @Override
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws SkyFunctionException, InterruptedException {
    RootedPath ninjaFilePath = ((RootedPath) skyKey.argument());
    NinjaFileHeaderBulkValue ninjaHeaderBulkValue =
        (NinjaFileHeaderBulkValue) env.getValue(NinjaFileHeaderBulkValue.key(ninjaFilePath));
    if (env.valuesMissing()) {
      return null;
    }
    return compute(Preconditions.checkNotNull(ninjaHeaderBulkValue).getRules());
  }

  public static NinjaRulesValue compute(List<String> lines) throws NinjaWrongRulesFormatException {
    ImmutableSortedMap.Builder<String, NinjaRule> builder = ImmutableSortedMap.naturalOrder();
    String name = null;
    ImmutableSortedMap.Builder<NinjaRule.ParameterName, String> parametersBuilder =
        ImmutableSortedMap.naturalOrder();
    for (String line : lines) {
      if (line.startsWith("rule ")) {
        String[] parts = line.split(" ");
        if (parts.length != 2) {
          throw new NinjaWrongRulesFormatException(
              String.format("Wrong rule name: '%s'", line));
        }
        if (name != null) {
          builder.put(name, new NinjaRule(checkAndBuildParameters(name, parametersBuilder)));
          parametersBuilder = ImmutableSortedMap.naturalOrder();
        }
        name = parts[1];
      } else if (line.trim().startsWith("#") || line.trim().isEmpty()) {
        // Skip the line.
      } else if (line.startsWith(" ")) {
        int idx = line.indexOf("=");
        if (idx >= 0) {
          String key = line.substring(0, idx).trim();
          String value = line.substring(idx + 1).trim();
          ParameterName parameterName = ParameterName.nullOrValue(key);
          if (parameterName == null) {
            throw new NinjaWrongRulesFormatException(
                String.format("Unknown rule parameter: '%s' in rule '%s'", key, name));
          }
          parametersBuilder.put(parameterName, value);
        } else {
          throw new NinjaWrongRulesFormatException(
              String.format("Can not parse rule parameter: '%s' in rule '%s'", line, name));
        }
      } else {
        throw new NinjaWrongRulesFormatException(
            String.format("Unknown top-level keyword in rules section: '%s'", line));
      }
    }

    // Last rule.
    if (name != null) {
      builder.put(name, new NinjaRule(checkAndBuildParameters(name, parametersBuilder)));
    }

    return new NinjaRulesValue(builder.build());
  }

  private static ImmutableSortedMap<ParameterName, String> checkAndBuildParameters(String name,
      ImmutableSortedMap.Builder<ParameterName, String> parametersBuilder)
      throws NinjaWrongRulesFormatException {
    ImmutableSortedMap<ParameterName, String> parameters = parametersBuilder.build();
    if (!parameters.containsKey(ParameterName.command)) {
      throw new NinjaWrongRulesFormatException(
          String.format("Rule %s should have command, rule text: '%s'", name, parameters));
    }
    return parameters;
  }

  @Nullable
  @Override
  public String extractTag(SkyKey skyKey) {
    return null;
  }

  public static class NinjaWrongRulesFormatException extends SkyFunctionException {
    public NinjaWrongRulesFormatException(String message) {
      super(new Exception(message), Transience.PERSISTENT);
    }
  }
}
