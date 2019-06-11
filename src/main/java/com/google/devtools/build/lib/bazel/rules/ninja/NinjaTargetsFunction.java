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
  private final static int CHUNK_SIZE = 100 * 1024;

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

    boolean skippingStart = true;
    try (FileChannel fch = FileChannel.open(rootedPath.asPath().getPathFile().toPath(),
        StandardOpenOption.READ)) {
      FileChannelLinesReader reader = new FileChannelLinesReader(fch);
      long bufferStart = actionsValueKey.getBufferStart();
      reader.position(bufferStart, actionsValueKey.getLineStart());
      List<String> list = Lists.newArrayList();

      while (true) {
        long lineStart = reader.getCurrentEnd();
        String line = reader.readLine();
        if (line != null && line.startsWith("#")) {
          continue;
        }
        if (line == null
            || line.isEmpty()
            || line.startsWith("build ")
            || line.startsWith("default ")) {
          if (!list.isEmpty()) {
            parseTargetExpression(list, builder);
            list.clear();
          }
          if (line == null || lineStart >= (bufferStart + CHUNK_SIZE)) {
            break;
          }
        }
        if (line != null && !line.isEmpty()) {
          if (list.isEmpty() && !(line.startsWith("build ") || line.startsWith("default "))) {
            if (skippingStart) {
              continue;
            }
            throw new NinjaFileFormatSkyFunctionException("Non proper file fragment: " + line);
          }
          skippingStart = false;
          list.add(line);
        }
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
    } else {
      throw new NinjaFileFormatSkyFunctionException(
          String.format("Unexpected line start for build targets section: '%s'", header));
    }
  }

  private static void parseBuild(List<String> lines, NinjaTargetsValue.Builder builder)
      throws NinjaFileFormatSkyFunctionException {
    NinjaTarget.Builder tBuilder = NinjaTarget.builder();

    String header = lines.get(0);
    parseHeader(header, tBuilder);
    NinjaVariablesFunction.parseVariables(lines.subList(1, lines.size()))
        .forEach(tBuilder::addVariable);

    NinjaTarget target = tBuilder.build();
    String command = target.getCommand();
    if (command == null) {
      throw new NinjaFileFormatSkyFunctionException("Ninja target is missing command: " + header);
    }
    if ("phony".equals(command)) {
      if (!target.getImplicitOutputs().isEmpty() || target.getOutputs().size() != 1) {
        throw new NinjaFileFormatSkyFunctionException("Wrong phony target format: " + header);
      }
      builder.addAlias(target.getOutputs().get(0), target);
    } else {
      builder.addNinjaTarget(target);
    }
  }

  private static void parseHeader(String header, NinjaTarget.Builder tBuilder)
      throws NinjaFileFormatSkyFunctionException {
    String buildText = header.substring("build ".length());

    TokenProcessor addToInputs = string -> tBuilder.addInputs(string.split(" "));
    TokenProcessor addToImplicitInputs =
        string -> tBuilder.addImplicitInputs(string.split(" "));
    TokenProcessor addToOrderOnlyInputs =
        string -> tBuilder.addOrderOnlyInputs(string.split(" "));

    TokenProcessor addToOutputs = string -> tBuilder.addOutputs(string.split(" "));
    TokenProcessor addToImplicitOutputs =
        string -> tBuilder.addImplicitOutputs(string.split(" "));

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
