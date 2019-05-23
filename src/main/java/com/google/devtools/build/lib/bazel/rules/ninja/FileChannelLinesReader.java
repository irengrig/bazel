package com.google.devtools.build.lib.bazel.rules.ninja;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import javax.annotation.Nullable;

class FileChannelLinesReader {
  private static final int BLOCK = 10 * 1024;

  private FileChannel fch;
  private CharBuffer buffer;
  private final ByteBuffer byteBuffer;
  private boolean atEOF;
  private int lineStart = 0;
  private long bufferStart = 0;

  FileChannelLinesReader(FileChannel fch) {
    this.fch = fch;
    byteBuffer = ByteBuffer.allocate(BLOCK * Character.BYTES);
  }

  @Nullable
  public String readLine() throws IOException {
    System.out.println("%%%%");
    Line line = new Line();
    while (!line.isEol() && !atEOF) {
      readNextChunkIfNeeded();
      readLineFromBuffer(line);
    }
    String text = line.getLine();
    if (atEOF && text.isEmpty()) {
      return null;
    }
    return text;
  }

  private void readNextChunkIfNeeded() throws IOException {
    if (atEOF) {
      return;
    }
    if (buffer == null || buffer.position() >= buffer.limit()) {
      bufferStart = fch.position();
      int bytesRead = fch.read(byteBuffer);
      byteBuffer.position(0);
      byteBuffer.limit(bytesRead > 0 ? bytesRead : 0);
      atEOF = bytesRead <= 0;
      buffer = StandardCharsets.ISO_8859_1.decode(byteBuffer);
    }
  }

  private void readLineFromBuffer(Line line) {
    lineStart = buffer.position();
    while (buffer.hasRemaining()) {
      char ch = buffer.get();
      if (ch == '\n') {
        break;
      }
    }
    int end = buffer.position();
    char[] chars = new char[end - lineStart];
    buffer.position(lineStart);
    buffer.get(chars);
    buffer.position(end);
    line.append(chars);
  }

  public boolean isEOF() {
    return atEOF;
  }

  public int getLineStart() {
    return lineStart;
  }

  public long getBufferStart() {
    return bufferStart;
  }

  // todo separate test
  private static class Line {
    private ImmutableSet<Character> ESCAPED = ImmutableSet.of(' ', '$', ':');
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

    private void append(char[] chars) {
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

    private String getLine() {
      return sb.toString();
    }

    private boolean isEol() {
      return eol;
    }
  }
}
