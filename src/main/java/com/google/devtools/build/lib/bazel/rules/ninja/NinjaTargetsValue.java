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
  public static final SkyFunctionName NINJA_TARGETS =
      SkyFunctionName.createHermetic("NINJA_TARGETS");
  private final ImmutableList<NinjaTarget> targets;
  private final ImmutableSortedMap<String, NinjaTarget> aliases;
  private final ImmutableList<String> defaults;

  public NinjaTargetsValue(ImmutableList<NinjaTarget> targets,
      ImmutableSortedMap<String, NinjaTarget> aliases,
      ImmutableList<String> defaults) {
    this.targets = targets;
    this.aliases = aliases;
    this.defaults = defaults;
  }

  public List<NinjaTarget> getTargets() {
    return targets;
  }

  public ImmutableSortedMap<String, NinjaTarget> getAliases() {
    return aliases;
  }

  public ImmutableList<String> getDefaults() {
    return defaults;
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
      return NINJA_TARGETS;
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
    private final ImmutableList.Builder<NinjaTarget> targetsBuilder;
    private final ImmutableSortedMap.Builder<String, NinjaTarget> aliasesBuilder;
    private final ImmutableList.Builder<String> defaultsBuilder;

    private Builder() {
      targetsBuilder = new ImmutableList.Builder<>();
      aliasesBuilder = ImmutableSortedMap.naturalOrder();
      defaultsBuilder = new ImmutableList.Builder<>();
    }

    public Builder addNinjaTarget(NinjaTarget action) {
      targetsBuilder.add(action);
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
      return new NinjaTargetsValue(targetsBuilder.build(), aliasesBuilder.build(),
          defaultsBuilder.build());
    }
  }

}
