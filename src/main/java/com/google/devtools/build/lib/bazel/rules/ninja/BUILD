java_library(
    name = "ninja_build",
    srcs = glob(["*.java"]),
    deps = [
        "//third_party:guava",
        "//third_party:jsr305",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/skyframe",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//src/main/java/com/google/devtools/build/lib:util",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec:autocodec-annotation",
    ],
    visibility = ["//src/test/java/com/google/devtools/build/lib/bazel/rules/ninja:__pkg__"]
)