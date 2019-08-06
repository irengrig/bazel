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
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Lists;
import com.google.devtools.build.lib.bazel.rules.ninja.NinjaRule.ParameterName;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.List;
import javax.annotation.Nullable;

public class NinjaTargetsFunction implements SkyFunction {
  @Nullable
  @Override
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws SkyFunctionException, InterruptedException {
    NinjaTargetsValue.Key actionsValueKey = (NinjaTargetsValue.Key) skyKey;
    RootedPath rootedPath = actionsValueKey.getPath();

    NinjaTargetsValue.Builder builder = NinjaTargetsValue.builder();
    parseFileFragmentForNinjaTargets(actionsValueKey, rootedPath, builder);
    return builder.build();
  }

  @VisibleForTesting
  public static void parseFileFragmentForNinjaTargets(
      NinjaTargetsValue.Key actionsValueKey,
      RootedPath rootedPath,
      NinjaTargetsValue.Builder builder) throws NinjaFileFormatSkyFunctionException {

    try (FileChannel fch = FileChannel.open(rootedPath.asPath().getPathFile().toPath(),
        StandardOpenOption.READ)) {
      FileChannelLinesReader reader = new FileChannelLinesReader(fch);
      long bufferStart = actionsValueKey.getBufferStart();
      boolean skippingStart = bufferStart > 0;
      int size = actionsValueKey.getSize();
      reader.position(bufferStart, 0);
      List<String> list = Lists.newArrayList();
      List<String> previousList = Lists.newArrayList();

      while (true) {
        long lineStart = reader.getCurrentEnd();
        String line = reader.readLine();
        if (line == null || size > 0 && lineStart >= (bufferStart + size)) {
          break;
        }
        if (line.startsWith("#") || line.isEmpty()) {
          continue;
        }
        if (!line.startsWith(" ") && !line.startsWith("\t")) {
          if (!list.isEmpty()) {
            if (!skippingStart) {
              parseTargetExpression(list, builder);
            }
            previousList.clear();
            previousList.addAll(list);
            list.clear();
            skippingStart = false;
          }
          list.add(line);
        } else {
          if (!skippingStart) {
            list.add(line);
          }
        }
      }
      if (!list.isEmpty()) {
        if (skippingStart) {
          throw new NinjaFileFormatSkyFunctionException("Buffer size is too small!");
        }
        parseTargetExpression(list, builder);
      }
    } catch (IOException e) {
      throw new NinjaFileFormatSkyFunctionException(e);
    }
  }

  @VisibleForTesting
  public static void parseTargetExpression(List<String> lines, NinjaTargetsValue.Builder builder)
      throws NinjaFileFormatSkyFunctionException {
    Preconditions.checkArgument(lines.size() > 0);
    String header = lines.get(0);
    if (header.startsWith("default ")) {
      parseDefault(lines, builder);
    } else if (header.startsWith("build ")) {
      parseBuild(lines, builder);
    } else if (header.startsWith("rule ")) {
      Pair<String, NinjaRule> pair = parseRule(lines);
      builder.addRule(pair.getFirst(), pair.getSecond());
    } else if (header.startsWith("pool ")) {
      // Skip that
    } else if (header.startsWith("include ") || header.startsWith("subninja ")) {
      builder.addInclude(header);
      if (lines.size() > 1) {
        throw new NinjaFileFormatSkyFunctionException(
            String.format("Include/subninja command followed by something unexpected: '%s'",
                String.join("\n", lines)));
      }
    } else {
      int idx = header.indexOf("=");
      if (idx >= 0) {
        if (lines.size() > 1) {
          throw new NinjaFileFormatSkyFunctionException(
              String.format("Variable definition followed by something unexpected: '%s'",
                  String.join("\n", lines)));
        }
        builder.addVariable(header.substring(0, idx).trim(), header.substring(idx + 1).trim());
      } else {
        throw new NinjaFileFormatSkyFunctionException(
            String.format("Unexpected line: '%s'", String.join("\n", lines)));
      }
    }
  }

  public static Pair<String, NinjaRule> parseRule(List<String> lines)
      throws NinjaFileFormatSkyFunctionException {
    ImmutableSortedMap.Builder<NinjaRule.ParameterName, String> parametersBuilder =
        ImmutableSortedMap.naturalOrder();
    boolean inPool = false;

    String name = readRuleName(lines.get(0), lines);
    for (int i = 1; i < lines.size(); i++) {
      String line = lines.get(i);
      if (line.startsWith("rule ")) {
          throw new NinjaFileFormatSkyFunctionException(
              String.format("Expected only one rule definition: '%s'", String.join("\n", lines)));
      } else if (line.trim().startsWith("#") || line.trim().isEmpty()) {
        // Skip the line.
      } else if (line.startsWith("pool ")) {
        // skip the pool statement; if there was some rule before, it will be processed when the
        // definition of the next rule comes, or after iteration
        inPool = true;
      } else if (line.startsWith(" ")) {
        if (inPool) {
          continue;
        }
        int idx = line.indexOf("=");
        if (idx >= 0) {
          String key = line.substring(0, idx).trim();
          String value = line.substring(idx + 1).trim();
          ParameterName parameterName = ParameterName.nullOrValue(key);
          if (parameterName == null) {
            throw new NinjaFileFormatSkyFunctionException(
                String.format("Unknown rule parameter: '%s' in rule '%s'", key, name));
          }
          if (parameterName.isDefinedByTarget()) {
            throw new NinjaFileFormatSkyFunctionException(
                String.format("Parameter '%s' should not be defined in rule '%s'", key, name));
          }
          parametersBuilder.put(parameterName, value);
        } else {
          throw new NinjaFileFormatSkyFunctionException(
              String.format("Can not parse rule parameter: '%s' in rule '%s'", line, name));
        }
      } else {
        throw new NinjaFileFormatSkyFunctionException(
            String.format("Unknown top-level keyword in rules section: '%s'", line));
      }
    }

    return Pair.of(name, new NinjaRule(checkAndBuildParameters(name, parametersBuilder)));
  }

  private static String readRuleName(String line, List<String> lines)
      throws NinjaFileFormatSkyFunctionException {
    if (!line.startsWith("rule ")) {
      throw new NinjaFileFormatSkyFunctionException(
          String.format("Expected to find rule definition: '%s'", String.join("\n", lines)));
    }
    String[] parts = line.split(" ");
    if (parts.length != 2) {
      throw new NinjaFileFormatSkyFunctionException(String.format("Wrong rule name: '%s'", line));
    }
    return parts[1];
  }

  private static ImmutableSortedMap<ParameterName, String> checkAndBuildParameters(String name,
      ImmutableSortedMap.Builder<ParameterName, String> parametersBuilder)
      throws NinjaFileFormatSkyFunctionException {
    ImmutableSortedMap<ParameterName, String> parameters = parametersBuilder.build();
    if (!parameters.containsKey(ParameterName.command)) {
      throw new NinjaFileFormatSkyFunctionException(
          String.format("Rule %s should have command, rule text: '%s'", name, parameters));
    }
    return parameters;
  }

  private static void parseBuild(List<String> lines, NinjaTargetsValue.Builder builder)
      throws NinjaFileFormatSkyFunctionException {
    NinjaTarget.Builder tBuilder = NinjaTarget.builder();

    String header = lines.get(0);
    parseHeader(header, tBuilder);
    parseVariables(lines.subList(1, lines.size())).forEach(tBuilder::addVariable);

    NinjaTarget target = tBuilder.build();
    String command = target.getCommand();
    if (command == null) {
      throw new NinjaFileFormatSkyFunctionException("Ninja target is missing command: " + header);
    }
    if ("phony".equals(command)) {
      if (!target.getImplicitOutputs().isEmpty()) {
        throw new NinjaFileFormatSkyFunctionException(
            "Phony target contains implicit outputs: " + header);
      }
      if (target.getInputs().isEmpty()
          && target.getImplicitInputs().isEmpty()
          && target.getOrderOnlyInputs().isEmpty()) {
        // Skip the target: it is a phony statement without inputs,
        // declaring that the named output files can be non existent at build time
        return;
      }
      if (target.getOutputs().size() > 1) {
        throw new NinjaFileFormatSkyFunctionException(
            "Phony target with several alias names: " + header);
      }
    }
    builder.addNinjaTarget(target);
  }

  private static ImmutableSortedMap<String, String> parseVariables(List<String> lines)
      throws NinjaFileFormatSkyFunctionException {
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
      throw new NinjaFileFormatSkyFunctionException(
          String.format("Some of the lines do not contain variable definitions: [%s]",
              String.join(",\n", problematic)));
    }
    return builder.build();
  }

  private static void parseHeader(String header, NinjaTarget.Builder tBuilder)
      throws NinjaFileFormatSkyFunctionException {
    String buildText = header.substring("build ".length());

    TokenProcessor addToInputs = string -> tBuilder.addInputs(splitBySpace(string));
    TokenProcessor addToImplicitInputs =
        string -> tBuilder.addImplicitInputs(splitBySpace(string));
    TokenProcessor addToOrderOnlyInputs =
        string -> tBuilder.addOrderOnlyInputs(splitBySpace(string));

    TokenProcessor addToOutputs = string -> tBuilder.addOutputs(splitBySpace(string));
    TokenProcessor addToImplicitOutputs =
        string -> tBuilder.addImplicitOutputs(splitBySpace(string));

    new Splitter(":", true)
        .head(new Splitter("|")
            .head(addToOutputs)
            .tail(addToImplicitOutputs))
        .tail(new Splitter("||")
            .head(new Splitter("|")
                .head(new Splitter(" ")
                    .head(tBuilder::setCommand)
                    .tail(addToInputs)
                )
                .tail(addToImplicitInputs)
            )
            .tail(addToOrderOnlyInputs)
        ).accept(buildText);
  }

  private static List<String> splitBySpace(String s) {
    List<String> result = Lists.newArrayList();
    int start = 0;
    while (start < s.length()) {
      int next = s.indexOf(" ", start);
      if (next < 0) {
        // last
        result.add(s.substring(start));
        break;
      } else {
        result.add(s.substring(start, next));
      }
      start = next + 1;
      while (start < s.length() && s.charAt(start) == ' ') {
        ++start;
      }
    }
    return result;
  }

  private static void parseDefault(List<String> lines, NinjaTargetsValue.Builder builder)
      throws NinjaFileFormatSkyFunctionException {
    String header = lines.get(0);
    if (lines.size() > 1) {
      throw new NinjaFileFormatSkyFunctionException(
          "'default' is supposed to be one-line expression, but found: " +
          String.join("\n", lines));
    }
    String[] words = header.split(" ");
    // Skip first "default" word.
    for (int i = 1; i < words.length; i++) {
      builder.addDefault(words[i]);
    }
  }

  @VisibleForTesting
  public interface TokenProcessor {
    void accept(String text) throws NinjaFileFormatSkyFunctionException;
  }

  @VisibleForTesting
  public static class Splitter implements TokenProcessor {
    private static final TokenProcessor SHOULD_NOT_BE_CALLED =
        t -> {throw new IllegalStateException(t);};
    private final String token;
    private final boolean mustSplit;
    private TokenProcessor headSplitter = SHOULD_NOT_BE_CALLED;
    private TokenProcessor tailSplitter = SHOULD_NOT_BE_CALLED;

    public Splitter(String token) {
      this(token, false);
    }

    public Splitter(String token, boolean mustSplit) {
      this.token = token;
      this.mustSplit = mustSplit;
    }

    public Splitter head(TokenProcessor headSplitter) {
      this.headSplitter = headSplitter;
      return this;
    }

    public Splitter tail(TokenProcessor tailSplitter) {
      this.tailSplitter = tailSplitter;
      return this;
    }

    @Override
    public void accept(String text) throws NinjaFileFormatSkyFunctionException {
      if (text.isEmpty()) {
        return;
      }
      int idx = text.indexOf(token);
      if (idx < 0) {
        if (mustSplit) {
          throw new NinjaFileFormatSkyFunctionException("Wrong target line format: " + text);
        }
        headSplitter.accept(text.trim());
      } else {
        String head = text.substring(0, idx).trim();
        if (!head.isEmpty()) {
          headSplitter.accept(head);
        }
        String tail = text.substring(idx + token.length()).trim();
        if (!tail.isEmpty()) {
          tailSplitter.accept(tail);
        }
      }
    }
  }

  @Nullable
  @Override
  public String extractTag(SkyKey skyKey) {
    return null;
  }
}
