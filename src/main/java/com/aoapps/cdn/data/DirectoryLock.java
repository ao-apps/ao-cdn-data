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

import com.aoapps.collections.AoCollections;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A directory is locked by locking on a hidden {@literal Directory#LOCK_FILE} file within the directory.
 * <p>
 * Once created, the lock file is left in-place.  They are empty files and will thus not take any actual extents.
 * </p>
 * <p>
 * The lock files are excluded from {@link Csync2} synchronization.
 * </p>
 */
class DirectoryLock implements AutoCloseable {

  private static final Logger logger = Logger.getLogger(DirectoryLock.class.getName());

  /**
   * The filename used for temporary lock files within a directory.
   * These files are excluded from Csync2 replication.
   */
  static final String LOCK_FILE = ".lock";

  private final FileChannel channel;
  private final FileLock lock;

  /**
   * Locks a directory, atomically creating the lock file when first needed.
   *
   * @see  Resources#lock(boolean)
   * @see  Resource#lock(boolean)
   */
  @SuppressFBWarnings("CT_CONSTRUCTOR_THROW")
  DirectoryLock(Path dir, boolean shared) throws IOException {
    Path lockFile = dir.resolve(LOCK_FILE);
    boolean notExists = Files.notExists(lockFile, LinkOption.NOFOLLOW_LINKS);
    if (notExists) {
      logger.log(Level.FINE, () -> "Creating new lock file: " + lockFile);
    } else {
      logger.log(Level.FINER, () -> "Found existing lock file: " + lockFile);
      if (!Files.isRegularFile(lockFile, LinkOption.NOFOLLOW_LINKS)) {
        throw new IOException("Lock file is not regular file: " + lockFile);
      }
    }
    Set<OpenOption> options = AoCollections.newHashSet(4);
    options.add(LinkOption.NOFOLLOW_LINKS);
    if (notExists) {
      options.add(StandardOpenOption.CREATE);
    }
    if (shared) {
      options.add(StandardOpenOption.READ);
    } else {
      options.add(StandardOpenOption.WRITE);
      options.add(StandardOpenOption.APPEND);
    }
    channel = FileChannel.open(lockFile, options,
        FileSystemUtils.usePosixAttributesWhenSupported(lockFile.getFileSystem(), FileSystemUtils.NEW_FILE_PERMISSIONS)
    );
    // TODO: Create some fusion of ReadWriteLock (weak-map cached by path) that also performs channel locking
    lock = channel.lock(0, Long.MAX_VALUE, shared);
  }

  boolean isShared() {
    return lock.isShared();
  }

  boolean isValid() {
    return lock.isValid();
  }

  @Override
  public void close() throws IOException {
    try {
      channel.close();
    } finally {
      assert !lock.isValid();
    }
  }
}
