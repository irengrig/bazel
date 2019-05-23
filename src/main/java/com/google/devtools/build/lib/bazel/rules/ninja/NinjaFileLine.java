package com.google.devtools.build.lib.bazel.rules.ninja;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSortedMap;

public class NinjaFileLine {
  private final static ImmutableSortedMap<String, String> ESCAPE_REPLACEMENTS =
      ImmutableSortedMap.of("$\n", "",
          "$$", "$",
          "$ ", " ",
          "$:", ":");
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

    for (String escapeKey : ESCAPE_ORDER) {
      replaceAll(line, escapeKey, Preconditions.checkNotNull(ESCAPE_REPLACEMENTS.get(escapeKey)));
    }

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
