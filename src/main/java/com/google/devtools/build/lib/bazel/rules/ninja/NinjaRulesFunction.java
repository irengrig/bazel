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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSortedMap;
import com.google.devtools.build.lib.bazel.rules.ninja.NinjaRule.ParameterName;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.List;
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

  public static NinjaRulesValue compute(List<String> lines)
      throws NinjaFileFormatSkyFunctionException {
    ImmutableSortedMap.Builder<String, NinjaRule> builder = ImmutableSortedMap.naturalOrder();
    String name = null;
    ImmutableSortedMap.Builder<NinjaRule.ParameterName, String> parametersBuilder =
        ImmutableSortedMap.naturalOrder();
    boolean inPool = false;
    for (String line : lines) {
      if (line.startsWith("rule ")) {
        inPool = false;
        String[] parts = line.split(" ");
        if (parts.length != 2) {
          throw new NinjaFileFormatSkyFunctionException(
              String.format("Wrong rule name: '%s'", line));
        }
        if (name != null) {
          builder.put(name, new NinjaRule(checkAndBuildParameters(name, parametersBuilder)));
          parametersBuilder = ImmutableSortedMap.naturalOrder();
        }
        name = parts[1];
      } else if (line.trim().startsWith("#") || line.trim().isEmpty()) {
        // Skip the line.
      } else if (line.startsWith("pool ")) {
        // skip the pool statement; if there was some rule before, it will be processed when the
        // definition of the next rule comes, or after iteration
        inPool = true;
      } else if (line.startsWith(" ")) {
        if (inPool) {
          continue;
        }
        int idx = line.indexOf("=");
        if (idx >= 0) {
          String key = line.substring(0, idx).trim();
          String value = line.substring(idx + 1).trim();
          ParameterName parameterName = ParameterName.nullOrValue(key);
          if (parameterName == null) {
            throw new NinjaFileFormatSkyFunctionException(
                String.format("Unknown rule parameter: '%s' in rule '%s'", key, name));
          }
          if (parameterName.isDefinedByTarget()) {
            throw new NinjaFileFormatSkyFunctionException(
                String.format("Parameter '%s' should not be defined in rule '%s'", key, name));
          }
          parametersBuilder.put(parameterName, value);
        } else {
          throw new NinjaFileFormatSkyFunctionException(
              String.format("Can not parse rule parameter: '%s' in rule '%s'", line, name));
        }
      } else {
        throw new NinjaFileFormatSkyFunctionException(
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
      throws NinjaFileFormatSkyFunctionException {
    ImmutableSortedMap<ParameterName, String> parameters = parametersBuilder.build();
    if (!parameters.containsKey(ParameterName.command)) {
      throw new NinjaFileFormatSkyFunctionException(
          String.format("Rule %s should have command, rule text: '%s'", name, parameters));
    }
    return parameters;
  }

  @Nullable
  @Override
  public String extractTag(SkyKey skyKey) {
    return null;
  }
}
