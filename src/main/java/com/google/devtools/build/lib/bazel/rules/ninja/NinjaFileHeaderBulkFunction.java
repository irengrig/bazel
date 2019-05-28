package com.google.devtools.build.lib.bazel.rules.ninja;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.function.Function;
import javax.annotation.Nullable;

public class NinjaFileHeaderBulkFunction implements SkyFunction {
  @Nullable
  @Override
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws SkyFunctionException, InterruptedException {
    RootedPath ninjaFilePath = ((RootedPath) skyKey.argument());

    return compute(ninjaFilePath);
  }

  public static SkyValue compute(RootedPath ninjaFilePath)
      throws NinjaFileFormatSkyFunctionException {
    LinesConsumer variables = new LinesConsumer(line -> !line.startsWith("rule ")
      && !line.startsWith("build ") && !line.startsWith("default"));
    LinesConsumer rules = new LinesConsumer(line -> line.startsWith("rule ")
        || line.startsWith(" ") || line.isEmpty());
    Pair<Long, Integer> position;
    try {
      position = readHeaderParts(ninjaFilePath.asPath().getPathFile(), variables, rules);
    } catch (IOException e) {
      throw new NinjaFileFormatSkyFunctionException(e);
    }

    return new NinjaFileHeaderBulkValue(variables.getLines(), rules.getLines(), position);
  }

  private static Pair<Long, Integer> readHeaderParts(File file, LinesConsumer... linesConsumers)
      throws IOException {
    Preconditions.checkArgument(linesConsumers.length > 0);
    Iterator<LinesConsumer> iterator = Arrays.asList(linesConsumers).iterator();
    LinesConsumer current = iterator.next();
    try (FileChannel fch = FileChannel.open(file.toPath(), StandardOpenOption.READ)) {
      FileChannelLinesReader reader = new FileChannelLinesReader(fch);
      boolean consumed = true;
      while (!reader.isEOF() && consumed) {
        String line = reader.readLine();
        consumed = false;
        if (line == null) {
          continue;
        }
        consumed = current.consume(line);
        if (!consumed && iterator.hasNext()) {
          current = iterator.next();
          consumed = current.consume(line);
        }
      }
      return Pair.of(reader.getBufferStart(), reader.getLineStart());
    }
  }

  @Nullable
  @Override
  public String extractTag(SkyKey skyKey) {
    return null;
  }

  private static class LinesConsumer {
    final List<String> lines;
    private Function<String, Boolean> predicate;

    public LinesConsumer(Function<String, Boolean> predicate) {
      this.predicate = predicate;
      lines = Lists.newArrayList();
    }

    public boolean consume(String line) {
      if (Boolean.TRUE.equals(predicate.apply(line))) {
        lines.add(line);
        return true;
      }
      return false;
    }

    public List<String> getLines() {
      return lines;
    }
  }
}
