package com.google.devtools.build.lib.bazel.rules.ninja;

import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
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

      String value = changeable.get(name);
      if (recursive && value != null) {
        requested.add(name);
        value = replaceVariablesInString(value, readOnly, changeable, requested, true);
        // As we successfully replaced the variable, the possible next calls for replacing
        // adjacent variables do not have it on stack
        requested.remove(name);
        changeable.put(name, value);
      }
      if (value == null) {
        value = readOnly.get(name);
      }
      if (value == null) {
        throw new NinjaFileFormatException(String.format("Variable '%s' is not defined.", name));
      }

      sb.replace(startIdx, endIdx + 1, value);
      startIdx = (startIdx + value.length());
    }
    return sb.toString();
  }

}
