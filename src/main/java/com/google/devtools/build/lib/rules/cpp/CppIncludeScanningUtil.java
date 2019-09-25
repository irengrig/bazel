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

package com.google.devtools.build.lib.rules.cpp;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;

public class CppIncludeScanningUtil {

  public static List<PathFragment> getSystemIncludeDirs(
      List<String> compilerOptions,
      Supplier<String> actionName) {
    // TODO(bazel-team): parsing the command line flags here couples us to gcc-style compiler
    // command lines; use a different way to specify system includes (for example through a
    // system_includes attribute in cc_toolchain); note that that would disallow users from
    // specifying system include paths via the copts attribute.
    // Currently, this works together with the include_paths features because getCommandLine() will
    // get the system include paths from the {@code CcCompilationContext} instead.
    ImmutableList.Builder<PathFragment> result = ImmutableList.builder();
    Iterator<String> iterator = compilerOptions.iterator();
    while (iterator.hasNext()) {
      String next = iterator.next();
      String includeDir = readOptionValue(next, iterator, "-isystem");
      if (includeDir == null) {
        includeDir = readOptionValue(next, iterator, "-I");
      }
      if (includeDir != null) {
        result.add(PathFragment.create(includeDir));
      }
    }
    for (int i = 0; i < compilerOptions.size(); i++) {
      String opt = compilerOptions.get(i);
      if (opt.startsWith("-isystem")) {
        if (opt.length() > 8) {
          result.add(PathFragment.create(opt.substring(8).trim()));
        } else if (i + 1 < compilerOptions.size()) {
          i++;
          result.add(PathFragment.create(compilerOptions.get(i)));
        } else {
          System.err.println("WARNING: dangling -isystem flag in options for " + actionName.get());
        }
      }
    }
    return result.build();
  }

  private static String readOptionValue(String option, Iterator<String> iterator, String optionPrefix) {
    if (!option.startsWith(optionPrefix)) {
      return null;
    }
    int startLength = optionPrefix.length();
    if (option.length() > startLength) {
      return option.substring(startLength).trim();
    } else if (iterator.hasNext()) {
      return iterator.next();
    } else {
      System.err.println("WARNING: dangling " + optionPrefix + " flag in options.");
      return null;
    }
  }

  public static List<String> getCmdlineIncludes(List<String> args) {
    ImmutableList.Builder<String> cmdlineIncludes = ImmutableList.builder();
    for (Iterator<String> argi = args.iterator(); argi.hasNext();) {
      String arg = argi.next();
      if (arg.equals("-include") && argi.hasNext()) {
        cmdlineIncludes.add(argi.next());
      }
    }
    return cmdlineIncludes.build();
  }
}
