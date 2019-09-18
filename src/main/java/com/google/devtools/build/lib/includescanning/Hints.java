package com.google.devtools.build.lib.includescanning;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.includescanning.IncludeParser.Inclusion;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import java.util.Collection;

public interface Hints {

  void clearCachedLegacyHints();

  Collection<Artifact> getFileLevelHintedInclusionsLegacy(Artifact path);

  Collection<Artifact> getPathLevelHintedInclusions(
      ImmutableList<PathFragment> paths, Environment env) throws InterruptedException;

  Collection<Inclusion> getHintedInclusions(Artifact path);
}
