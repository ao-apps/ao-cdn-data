/*
 * ao-cdn-data - API for accessing underlying content delivery network (CDN) data.
 * Copyright (C) 2023  AO Industries, Inc.
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

import com.aoapps.security.SmallIdentifier;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Iterates all of the resources.
 */
class ResourcesIterator implements Iterator<Resource> {

  private static final Logger logger = Logger.getLogger(ResourcesIterator.class.getName());

  private final Resources resources;
  private final Iterator<Path> hash1Iter;

  private long hash1Value;
  private Iterator<Path> hash2Iter;
  private long hash2Value;
  private Iterator<Path> resourceIter;

  private Resource next;

  @SuppressFBWarnings("CT_CONSTRUCTOR_THROW")
  ResourcesIterator(Resources resources) throws IOException {
    this.resources = resources;
    hash1Iter = FileSystemUtils.list(resources.resourcesDir, Resources.resourcesDirFilter).iterator();
  }

  private Resource findNext() {
    try {
      NEXT:
      while (next == null) {
        while (resourceIter == null || !resourceIter.hasNext()) {
          while (hash2Iter == null || !hash2Iter.hasNext()) {
            if (!hash1Iter.hasNext()) {
              // Nothing left to find
              break NEXT;
            }
            Path hash1Dir = hash1Iter.next();
            try {
              long newHash1Value = Resources.parseHash1Name(FileSystemUtils.getFileName(hash1Dir));
              if (!Files.isDirectory(hash1Dir, LinkOption.NOFOLLOW_LINKS)) {
                logger.log(Level.WARNING, () -> "Skipping non-directory in hash directory level 1: " + hash1Dir);
              } else {
                hash1Value = newHash1Value;
                hash2Iter = FileSystemUtils.list(hash1Dir).iterator();
              }
            } catch (NumberFormatException e) {
              logger.log(Level.WARNING, () -> "Skipping unexpected name for hash directory level 1: " + hash1Dir);
            }
          }
          Path hash2Dir = hash2Iter.next();
          try {
            long newHash2Value = Resources.parseHash2Name(FileSystemUtils.getFileName(hash2Dir));
            if (!Files.isDirectory(hash2Dir, LinkOption.NOFOLLOW_LINKS)) {
              logger.log(Level.WARNING, () -> "Skipping non-directory in hash directory level 2: " + hash2Dir);
            } else {
              hash2Value = newHash2Value;
              resourceIter = FileSystemUtils.list(hash2Dir).iterator();
            }
          } catch (NumberFormatException e) {
            logger.log(Level.WARNING, () -> "Skipping unexpected name for hash directory level 2: " + hash2Dir);
          }
        }
        // Try to find next
        Path resourceDir = resourceIter.next();
        if (!Files.isDirectory(resourceDir, LinkOption.NOFOLLOW_LINKS)) {
          logger.log(Level.FINE, () -> "Skipping non-directory for resource directory: "
              + resourceDir);
        } else {
          String resourceDirName = FileSystemUtils.getFileName(resourceDir);
          if (Resources.isNewResource(resourceDirName)) {
            logger.log(Level.FINE, () -> "Skipping new resource directory: "
                + resourceDir);
          } else {
            try {
              SmallIdentifier resourceId = new SmallIdentifier(hash1Value | hash2Value
                  | Resources.parseResourceName(resourceDirName));
              logger.log(Level.FINER, () -> "Returning resource " + resourceId + " in " + resourceDir);
              next = new Resource(resources.cdnData, resourceId, resourceDir);
            } catch (NumberFormatException e) {
              logger.log(Level.WARNING, () -> "Skipping unexpected name for resource directory: "
                  + resourceDir);
            }
          }
        }
      }
      return next;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public boolean hasNext() {
    return findNext() != null;
  }

  @Override
  public Resource next() {
    Resource r = findNext();
    if (r == null) {
      throw new NoSuchElementException();
    } else {
      next = null;
      return r;
    }
  }
}
