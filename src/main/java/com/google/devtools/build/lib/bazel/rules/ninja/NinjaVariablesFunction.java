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
      throws NinjaWrongVariablesFormatException {
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
      throw new NinjaWrongVariablesFormatException(
          String.format("Some of the lines do not contain variable definitions: [%s]",
              String.join(",\n", problematic)));
    }
    return new NinjaVariablesValue(builder.build());
  }

  @Nullable
  @Override
  public String extractTag(SkyKey skyKey) {
    return null;
  }

  public static class NinjaWrongVariablesFormatException extends SkyFunctionException {
    public NinjaWrongVariablesFormatException(String message) {
      super(new Exception(message), Transience.PERSISTENT);
    }
  }
}
