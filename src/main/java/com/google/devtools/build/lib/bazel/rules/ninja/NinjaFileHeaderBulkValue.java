package com.google.devtools.build.lib.bazel.rules.ninja;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Interner;
import com.google.devtools.build.lib.concurrent.BlazeInterners;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.AbstractSkyKey;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.List;

public class NinjaFileHeaderBulkValue implements SkyValue {
  public static final SkyFunctionName NINJA_HEADER_BULK =
      SkyFunctionName.createHermetic("NINJA_HEADER_BULK");
  private final List<String> variables;
  private final List<String> rules;
  private Pair<Long, Integer> position;

  public NinjaFileHeaderBulkValue(List<String> variables,
      List<String> rules,
      Pair<Long, Integer> position) {
    this.variables = variables;
    this.rules = rules;
    this.position = position;
  }

  public List<String> getVariables() {
    return variables;
  }

  public List<String> getRules() {
    return rules;
  }

  public Pair<Long, Integer> getPosition() {
    return position;
  }

  @VisibleForTesting
  @ThreadSafe
  public static Key key(RootedPath ninjaFilePath) {
    return Key.create(ninjaFilePath);
  }

  @AutoCodec.VisibleForSerialization
  @AutoCodec
  static class Key extends AbstractSkyKey<RootedPath> {
    private static final Interner<Key> interner = BlazeInterners.newWeakInterner();

    private Key(RootedPath arg) {
      super(arg);
    }

    @AutoCodec.VisibleForSerialization
    @AutoCodec.Instantiator
    static Key create(RootedPath arg) {
      return interner.intern(new Key(arg));
    }

    @Override
    public SkyFunctionName functionName() {
      return NINJA_HEADER_BULK;
    }
  }
}
