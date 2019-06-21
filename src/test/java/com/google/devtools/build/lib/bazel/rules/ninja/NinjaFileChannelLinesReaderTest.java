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

import static com.google.common.truth.Truth.assertThat;

import com.google.devtools.build.lib.blackbox.framework.PathUtils;
import java.io.File;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class NinjaFileChannelLinesReaderTest {
  @Test
  public void testReadOneLine() throws Exception {
    check("There is a one-line test.");
  }

  private void check(String... input) throws Exception {
    try(FileChannel fch = testFile(input)) {
      FileChannelLinesReader reader = new FileChannelLinesReader(fch);
      for (String line : input) {
        assertThat(reader.readLine()).isEqualTo(line);
      }
      assertThat(reader.readLine()).isNull();
      assertThat(reader.isEOF()).isTrue();
    }
  }

  private FileChannel testFile(String... lines) throws Exception {
    File file = File.createTempFile("NinjaFileChannelLinesReaderTest", ".ninja");
    PathUtils.writeFile(file.toPath(), lines);
    return FileChannel.open(file.toPath(), StandardOpenOption.READ);
  }
}
