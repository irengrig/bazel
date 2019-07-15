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
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.lib.vfs.util.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class NinjaFileHeaderBulkFunctionTest {

  @Test
  public void testFileHeader() throws Exception {
    final String[] fileLines = new String[]{
        "# build.ninja",
        "cc     = clang",
        "cflags = -Weverything",
        "",
        "rule compile",
        "  command = $cc $cflags -c $in -o$", // escaping the newline
        " $out",
        "",
        "pool link_pool",
        "  depth = 4",
        "",
        "rule link",
        "  command = $cc $in -o $out",
        "  pool = link_pool",
        "",
        "build hello.o: compile hello.c",
        "build hello: link hello.o",
        "",
        "default hello"};
    Path buildNinja = Files.createTempFile("test", ".ninja");
    PathUtils.writeFile(buildNinja, fileLines);

    Root root = Root.absoluteRoot(FileSystems.getNativeFileSystem());
    RootedPath rootedPath = RootedPath.toRootedPath(root,
        PathFragment.create(buildNinja.toString()));
    NinjaFileHeaderBulkValue value =
        (NinjaFileHeaderBulkValue) NinjaFileHeaderBulkFunction.compute(rootedPath);
    assertThat(value).isNotNull();
    assertThat(value.getVariables()).containsExactly(
        "# build.ninja",
        "cc     = clang",
        "cflags = -Weverything",
        ""
    );
    assertThat(value.getRules()).containsExactly(
        "rule compile",
        "  command = $cc $cflags -c $in -o $out",
        "",
        "pool link_pool",
        "  depth = 4",
        "",
        "rule link",
        "  command = $cc $in -o $out",
        "  pool = link_pool",
        "");
  }

  @Test
  public void testEmptyHeader() throws Exception {
    Path buildNinja = Files.createTempFile("test", ".ninja");
    PathUtils.writeFile(buildNinja);

    Root root = Root.absoluteRoot(FileSystems.getNativeFileSystem());
    RootedPath rootedPath = RootedPath.toRootedPath(root,
        PathFragment.create(buildNinja.toString()));
    NinjaFileHeaderBulkValue value =
        (NinjaFileHeaderBulkValue) NinjaFileHeaderBulkFunction.compute(rootedPath);
    assertThat(value).isNotNull();
    assertThat(value.getVariables()).isEmpty();
    assertThat(value.getRules()).isEmpty();
  }

  @Test
  public void testEmptyVars() throws Exception {
    final String[] fileLines = new String[]{
        "rule compile",
        "  command = $cc $cflags -c $in -o $out",
        "",
        "rule link",
        "  command = $cc $in -o $out",
        "",
        "build hello.o: compile hello.c",
        "build hello: link hello.o",
        "",
        "default hello"};
    Path buildNinja = Files.createTempFile("test", ".ninja");
    PathUtils.writeFile(buildNinja, fileLines);

    Root root = Root.absoluteRoot(FileSystems.getNativeFileSystem());
    RootedPath rootedPath = RootedPath.toRootedPath(root,
        PathFragment.create(buildNinja.toString()));
    NinjaFileHeaderBulkValue value =
        (NinjaFileHeaderBulkValue) NinjaFileHeaderBulkFunction.compute(rootedPath);
    assertThat(value).isNotNull();
    assertThat(value.getVariables()).isEmpty();
    assertThat(value.getRules()).containsExactly(
        "rule compile",
        "  command = $cc $cflags -c $in -o $out",
        "",
        "rule link",
        "  command = $cc $in -o $out",
        "");
  }

  @Test
  public void testEmptyRules() throws Exception {
    final String[] fileLines = new String[]{
        "# build.ninja",
        "cc     = clang",
        "cflags = -Weverything",
        "",
        "build hello.o: compile hello.c",
        "build hello: link hello.o",
        "",
        "default hello"};
    Path buildNinja = Files.createTempFile("test", ".ninja");
    PathUtils.writeFile(buildNinja, fileLines);

    Root root = Root.absoluteRoot(FileSystems.getNativeFileSystem());
    RootedPath rootedPath = RootedPath.toRootedPath(root,
        PathFragment.create(buildNinja.toString()));
    NinjaFileHeaderBulkValue value =
        (NinjaFileHeaderBulkValue) NinjaFileHeaderBulkFunction.compute(rootedPath);
    assertThat(value).isNotNull();
    assertThat(value.getVariables()).containsExactly(
        "# build.ninja",
        "cc     = clang",
        "cflags = -Weverything",
        ""
    );
    assertThat(value.getRules()).isEmpty();
  }
}
