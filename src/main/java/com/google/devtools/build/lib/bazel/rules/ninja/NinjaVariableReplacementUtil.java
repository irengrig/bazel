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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.util.Pair;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class NinjaVariableReplacementUtil {
  public static ImmutableSortedMap<String, String> replaceVariablesInVariables(
      ImmutableSortedMap<String, String> readOnly,
      ImmutableSortedMap<String, String> changeable) throws NinjaFileFormatException {

    Map<String, String> copy = Maps.newHashMap();
    copy.putAll(changeable);

    // We can not modify collection during iteration.
    for (String name : Sets.newHashSet(changeable.keySet())) {
      String value = replaceVariablesInString(
          copy.get(name), readOnly, copy, Sets.newHashSet(), true);
      copy.put(name, value);
    }

    return ImmutableSortedMap.copyOf(copy);
  }

  public static String replaceVariables(
      String string,
      ImmutableSortedMap<String, String> globals,
      ImmutableSortedMap<String, String> locals) throws NinjaFileFormatException {
    return replaceVariablesInString(string, globals, locals, Sets.newHashSet(), false);
  }

  private static String replaceVariablesInString(
      String string,
      ImmutableSortedMap<String, String> readOnly,
      Map<String, String> changeable,
      Set<String> requested,
      boolean recursive) throws NinjaFileFormatException {
    StringBuilder sb = new StringBuilder(string);
    int startIdx = 0;
    while (startIdx < sb.length()) {
      startIdx = sb.indexOf("$", startIdx);
      int endIdx;
      String name;
      if (startIdx < 0) {
        break;
      } else if (startIdx < (sb.length() - 1) && sb.charAt(startIdx + 1) == '{') {
        endIdx = sb.indexOf("}", startIdx + 1);
        if (endIdx < 0) {
          throw new NinjaFileFormatException(
              String.format("Wrong variable usages in: '%s'", sb.toString()));
        }
        name = sb.substring(startIdx + 2, endIdx);
      } else {
        endIdx = sb.length() - 1;
        for (int i = startIdx + 1; i < sb.length(); i++) {
          char ch = sb.charAt(i);
          if (!Character.isAlphabetic(ch)
              && !Character.isDigit(ch)
              && ch != '.'
              && ch != '-'
              && ch != '_') {
            endIdx = i - 1;
            break;
          }
        }
        name = sb.substring(startIdx + 1, endIdx + 1);
      }
      if (name.length() < 2 || name.charAt(1) == '$' || name.charAt(1) == ':') {
        startIdx = Math.max(endIdx, startIdx + 2);
        continue;
      }

      if (requested.contains(name)) {
        throw new NinjaFileFormatException(
            String.format("Recursive variables replacement in: '%s'.",
                String.join(", ", requested)));
      }
      if (name.trim().isEmpty()) {
        throw new NinjaFileFormatException(String.format("Empty variable name in: '%s'.", string));
      }

      boolean exact = sb.substring(startIdx, startIdx + 2).equals("${");
      Pair<String, String> pair = replace(readOnly, changeable, requested, recursive, name, exact);

      int endIdxForReplace = exact ? (endIdx + 1) : (startIdx + pair.getFirst().length() + 1);
      sb.replace(startIdx, endIdxForReplace, pair.getSecond());
      startIdx = (startIdx + pair.getSecond().length());
    }
    return sb.toString();
  }

  private static Pair<String, String> replace(ImmutableSortedMap<String, String> readOnly,
      Map<String, String> changeable, Set<String> requested, boolean recursive, String name,
      boolean exact)
      throws NinjaFileFormatException {
    List<String> possibleNames = exact ? ImmutableList.of(name) : possibleNames(name);
    for (String possibleName : possibleNames) {
      String value = changeable.get(possibleName);
      if (recursive && value != null) {
        requested.add(possibleName);
        value = replaceVariablesInString(value, readOnly, changeable, requested, true);
        // As we successfully replaced the variable, the possible next calls for replacing
        // adjacent variables do not have it on stack
        requested.remove(possibleName);
        changeable.put(possibleName, value);
      }
      if (value == null) {
        value = readOnly.get(possibleName);
      }
      if (value != null) {
        return Pair.of(possibleName, value);
      }
    }
    // todo still bad
    return Pair.of(possibleNames.get(possibleNames.size() - 1), "");
  }

  private static List<String> possibleNames(String name) {
    List<String> result = Lists.newArrayList();
    result.add(name);
    while (!name.isEmpty()) {
      int idx = name.lastIndexOf(".");
      if (idx > 0) {
        name = name.substring(0, idx);
        result.add(name);
      } else {
        name = "";
      }
    }
    return result;
  }
}
