package com.google.devtools.build.lib.bazel.rules.ninja;

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
  private long currentEnd = 0;

  FileChannelLinesReader(FileChannel fch) {
    this.fch = fch;
    byteBuffer = ByteBuffer.allocate(BLOCK * Character.BYTES);
  }

  @Nullable
  public String readLine() throws IOException {
    NinjaFileLine line = new NinjaFileLine();
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
      currentEnd = fch.position() + byteBuffer.limit();
      byteBuffer.position(0);
      byteBuffer.limit(bytesRead > 0 ? bytesRead : 0);
      atEOF = bytesRead <= 0;
      buffer = StandardCharsets.ISO_8859_1.decode(byteBuffer);
    }
  }

  private void readLineFromBuffer(NinjaFileLine line) {
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

  public void position(long bufferStart, int lineStart) throws IOException {
    fch.position(bufferStart);
    readNextChunkIfNeeded();
    buffer.position(lineStart);
  }

  public long getCurrentEnd() {
    return currentEnd;
  }
}
