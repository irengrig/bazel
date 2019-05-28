package com.google.devtools.build.lib.bazel.rules.ninja;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Interner;
import com.google.devtools.build.lib.concurrent.BlazeInterners;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.List;
import java.util.Objects;

public class NinjaTargetsValue implements SkyValue {
  public static final SkyFunctionName NINJA_ACTIONS =
      SkyFunctionName.createHermetic("NINJA_ACTIONS");
  private final ImmutableList<NinjaTarget> actions;
  private final ImmutableSortedMap<String, NinjaTarget> aliases;
  private final ImmutableList<String> defaults;

  public NinjaTargetsValue(ImmutableList<NinjaTarget> actions,
      ImmutableSortedMap<String, NinjaTarget> aliases,
      ImmutableList<String> defaults) {
    this.actions = actions;
    this.aliases = aliases;
    this.defaults = defaults;
  }

  public List<NinjaTarget> getActions() {
    return actions;
  }

  @VisibleForTesting
  @ThreadSafe
  public static Key key(RootedPath ninjaFilePath, int chunkNumber, long bufferStart, int lineStart) {
    return Key.create(ninjaFilePath, chunkNumber, bufferStart, lineStart);
  }

  @AutoCodec.VisibleForSerialization
  @AutoCodec
  public static class Key implements SkyKey {
    private static final Interner<Key> interner = BlazeInterners.newWeakInterner();

    private final RootedPath path;
    private final int idx;
    private final long bufferStart;
    private final int lineStart;

    private Key(RootedPath path, int idx, long bufferStart, int lineStart) {
      this.path = path;
      this.idx = idx;
      this.bufferStart = bufferStart;
      this.lineStart = lineStart;
    }

    public RootedPath getPath() {
      return path;
    }

    public int getIdx() {
      return idx;
    }

    public long getBufferStart() {
      return bufferStart;
    }

    public int getLineStart() {
      return lineStart;
    }

    @AutoCodec.VisibleForSerialization
    @AutoCodec.Instantiator
    static Key create(RootedPath arg, int idx, long bufferStart, int lineStart) {
      return interner.intern(new Key(arg, idx, bufferStart, lineStart));
    }

    @Override
    public SkyFunctionName functionName() {
      return NINJA_ACTIONS;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      Key key = (Key) o;
      return idx == key.idx &&
          Objects.equals(path, key.path);
    }

    @Override
    public int hashCode() {
      return Objects.hash(path, idx);
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private final ImmutableList.Builder<NinjaTarget> actionsBuilder;
    private final ImmutableSortedMap.Builder<String, NinjaTarget> aliasesBuilder;
    private final ImmutableList.Builder<String> defaultsBuilder;

    private Builder() {
      actionsBuilder = new ImmutableList.Builder<>();
      aliasesBuilder = ImmutableSortedMap.naturalOrder();
      defaultsBuilder = new ImmutableList.Builder<>();
    }

    public Builder addNinjaTarget(NinjaTarget action) {
      actionsBuilder.add(action);
      return this;
    }

    public Builder addAlias(String alias, NinjaTarget target) {
      aliasesBuilder.put(alias, target);
      return this;
    }

    public Builder addDefault(String target) {
      defaultsBuilder.add(target);
      return this;
    }

    public NinjaTargetsValue build() {
      return new NinjaTargetsValue(actionsBuilder.build(), aliasesBuilder.build(),
          defaultsBuilder.build());
    }
  }

}
