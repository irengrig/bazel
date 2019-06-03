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

public class NinjaFileLine {
  private final static ImmutableSortedMap<String, String> ESCAPE_REPLACEMENTS =
      ImmutableSortedMap.of("$\n", "");
  // Still ambiguous situations are possible, like $$:, but ignore that for now.
  // At least this way we protect $$ to not be used with a space after it.
  private final static String[] ESCAPE_ORDER = {"$\n", "$:", "$ ", "$$"};
  private final StringBuilder sb = new StringBuilder();
  private boolean eol;
  private boolean firstDollar = false;

  public void append(char[] chars) {
    if (chars.length == 0) {
      return;
    }
    StringBuilder line = new StringBuilder();
    if (firstDollar) {
      line.append('$');
    }
    line.append(chars);
    firstDollar = false;
    if (line.charAt(line.length() - 1) == '$'
        && (line.length() < 2 || line.charAt(line.length() - 2) != '$')) {
      firstDollar = true;
      line.setLength(line.length() - 1);
    }

    replaceAll(line, "$\n", "");
    // for (String escapeKey : ESCAPE_ORDER) {
    //   replaceAll(line, escapeKey, Preconditions.checkNotNull(ESCAPE_REPLACEMENTS.get(escapeKey)));
    // }

    if (line.length() > 0 && line.charAt(line.length() - 1) == '\n') {
      eol = true;
      line.setLength(line.length() - 1);
    }
    sb.append(line);
  }

  private void replaceAll(StringBuilder s, String from, String to) {
    int idx = 0;
    while ((idx = s.indexOf(from, idx)) >= 0) {
      s.replace(idx, idx + from.length(), to);
      idx += to.length();
    }
  }

  public String getLine() {
    return sb.toString();
  }

  public boolean isEol() {
    return eol;
  }
}
