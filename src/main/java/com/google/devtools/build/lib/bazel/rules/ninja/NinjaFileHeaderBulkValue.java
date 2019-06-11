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
  private RootedPath path;
  private final List<String> includeStatements;
  private final List<String> variables;
  private final List<String> rules;
  private Pair<Long, Integer> position;

  public NinjaFileHeaderBulkValue(
      RootedPath path,
      List<String> includeStatements,
      List<String> variables,
      List<String> rules,
      Pair<Long, Integer> position) {
    this.path = path;
    this.includeStatements = includeStatements;
    this.variables = variables;
    this.rules = rules;
    this.position = position;
  }

  public RootedPath getPath() {
    return path;
  }

  public List<String> getIncludeStatements() {
    return includeStatements;
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
