/*
 * ao-cdn-data - API for accessing underlying content delivery network (CDN) data.
 * Copyright (C) 2023, 2024  AO Industries, Inc.
 *     support@aoindustries.com
 *     7262 Bull Pen Cir
 *     Mobile, AL 36695
 *
 * This file is part of ao-cdn-data.
 *
 * ao-cdn-data is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ao-cdn-data is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with ao-cdn-data.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.aoapps.cdn.data;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Filesystem integrity checker.
 *
 * <p>TODO: This could be called on-occasion by monitoring, but on auction.* (for isUploader) and cdn.* (for !isUploader)</p>
 */
final class Fsck {

  /** Make no instances. */
  private Fsck() {
    throw new AssertionError();
  }

  /**
   * Performs a filesystem integrity check.
   */
  static Map<Path, FsckIssue> fsck(CdnData cdnData, boolean isStartup) {
    Map<Path, FsckIssue> issues = new LinkedHashMap<>() {
      /**
       * Throws an exception if value overwritten.  Each path should only be in the map once.
       */
      @Override
      public FsckIssue put(Path path, FsckIssue issue) {
        FsckIssue oldValue = super.put(path, issue);
        if (oldValue != null) {
          throw new AssertionError("More than one issue added for a path.\n"
              + "  path = \"" + path + "\"\n"
              + "  issue #1 = \"" + oldValue + "\"\n"
              + "  issue #2 = \"" + issue + '"');
        }
        return oldValue;
      }

    };
    Set<Path> synchronizePaths = isStartup ? new LinkedHashSet<>() : null;
    cdnData.resources.fsckResourcesDirectories(issues, synchronizePaths);
    if (synchronizePaths != null && !synchronizePaths.isEmpty()) {
      cdnData.csync2.synchronize(synchronizePaths.toArray(Path[]::new));
    }

    if (cdnData.uploads != null) {
      cdnData.uploads.fsckUploadsDirectory(isStartup, issues);
    }

    // Find anything in cdnRoot not expected
    try {
      for (Path path : FileSystemUtils.list(cdnData.cdnRoot)) {
        String name = FileSystemUtils.getFileName(path);
        if (
            !Resources.RESOURCES_DIR_NAME.equals(name)
            && !Uploads.UPLOADS_DIR_NAME.equals(name)) {
          issues.put(path, new FsckIssue(Level.WARNING, "Unexpected path in cdnRoot"));
        }
      }
    } catch (IOException e) {
      issues.put(cdnData.cdnRoot, new FsckIssue(Level.SEVERE, e, "Unable to list CDN root directory"));
    }
    return Collections.unmodifiableMap(issues);
  }
}
