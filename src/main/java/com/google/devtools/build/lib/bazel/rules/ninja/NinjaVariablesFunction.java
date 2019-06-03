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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Lists;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.List;
import javax.annotation.Nullable;

public class NinjaVariablesFunction implements SkyFunction {

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
    return compute(Preconditions.checkNotNull(ninjaHeaderBulkValue).getVariables());
  }

  @VisibleForTesting
  public static NinjaVariablesValue compute(List<String> lines)
      throws NinjaFileFormatSkyFunctionException {
    return new NinjaVariablesValue(parseVariables(lines));
  }

  public static ImmutableSortedMap<String, String> parseVariables(List<String> lines)
      throws NinjaFileFormatSkyFunctionException {
    ImmutableSortedMap.Builder<String, String> builder = ImmutableSortedMap.naturalOrder();
    List<String> problematic = Lists.newArrayList();
    lines.forEach(line -> {
      line = line.trim();
      if (line.isEmpty() || line.startsWith("#")) {
        return;
      }
      int idx = line.indexOf("=");
      if (idx >= 0) {
        builder.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
      } else {
        problematic.add(line);
      }
    });
    if (!problematic.isEmpty()) {
      throw new NinjaFileFormatSkyFunctionException(
          String.format("Some of the lines do not contain variable definitions: [%s]",
              String.join(",\n", problematic)));
    }
    return builder.build();
  }

  @Nullable
  @Override
  public String extractTag(SkyKey skyKey) {
    return null;
  }
}
