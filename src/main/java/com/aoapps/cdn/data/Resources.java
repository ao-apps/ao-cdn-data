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

import com.aoapps.lang.io.function.IOPredicate;
import com.aoapps.security.SmallIdentifier;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages access through the resources hash directories.
 */
@SuppressFBWarnings("PI_DO_NOT_REUSE_PUBLIC_IDENTIFIERS_CLASS_NAMES")
public class Resources implements Iterable<Resource> {

  private static final Logger logger = Logger.getLogger(Resources.class.getName());

  static final String RESOURCES_DIR_NAME = "resources";

  static final int BITS_PER_HEX_CHAR = 4;

  /**
   * The number of hex characters in both levels of hash directory.
   * Each character is a four-bit lower-case hex value.
   */
  static final int HASH_CHARS = 4;

  /**
   * The number of hex characters in resource directory.
   * Each character is a four-bit lower-case hex value.
   */
  static final int RESOURCE_HASH_CHARS = Long.SIZE / BITS_PER_HEX_CHAR - HASH_CHARS * 2;

  /**
   * Parses a single hex character in lower-case only.
   *
   * @throws NumberFormatException when not a valid lower-case hex number
   */
  static int parseHexChar(char ch) throws NumberFormatException {
    if (ch >= '0' && ch <= '9') {
      return ch - '0';
    }
    if (ch >= 'a' && ch <= 'f') {
      return 10 + ch - 'a';
    }
    throw new NumberFormatException("Invalid hex character: " + ch);
  }

  /**
   * Parses a hash directory name, case-sensitive.
   *
   * @throws NumberFormatException when not a valid number of lower-case hex characters
   *
   * @see #parseHash1Name(java.lang.String)
   * @see #parseHash2Name(java.lang.String)
   * @see #parseResourceName(java.lang.String)
   */
  static long parseHashName(String hashName, int numChars) throws NumberFormatException {
    if (hashName.length() != numChars) {
      throw new NumberFormatException("Unexpected number of characters in hash directory name.  Expected " + numChars
          + ", got " + hashName.length() + ".  hashName = " + hashName);
    }
    long value = 0;
    for (int i = 0; i < numChars; i++) {
      value = (value << BITS_PER_HEX_CHAR) | parseHexChar(hashName.charAt(i));
    }
    return value;
  }

  /**
   * Parses a hash1 directory name, case-sensitive.
   * Sets the high-order bits, with the remaining bits all zero.
   * May be or'ed with the results of {@link #parseHash2Name(java.lang.String)}
   * and {@link #parseResourceName(java.lang.String)}.
   *
   * @throws NumberFormatException when not a valid four-character lower-case hex number
   *
   * @see #parseHashName(java.lang.String, int)
   * @see #parseHash2Name(java.lang.String)
   * @see #parseResourceName(java.lang.String)
   */
  static long parseHash1Name(String hash1Name) throws NumberFormatException {
    // First level of most-signifant bits
    return parseHashName(hash1Name, HASH_CHARS) << (Long.SIZE - HASH_CHARS * BITS_PER_HEX_CHAR);
  }

  /**
   * Parses a hash2 directory name, case-sensitive.
   * Sets the next-high-order bits, with the remaining bits all zero.
   * May be or'ed with the results of {@link #parseHash1Name(java.lang.String)}
   * and {@link #parseResourceName(java.lang.String)}.
   *
   * @throws NumberFormatException when not a valid four-character lower-case hex number
   *
   * @see #parseHashName(java.lang.String, int)
   * @see #parseHash1Name(java.lang.String)
   * @see #parseResourceName(java.lang.String)
   */
  static long parseHash2Name(String hash2Name) throws NumberFormatException {
    // Second level of most-signifant bits
    return parseHashName(hash2Name, HASH_CHARS) << (RESOURCE_HASH_CHARS * BITS_PER_HEX_CHAR);
  }

  /**
   * Parses a resource directory name, case-sensitive.
   * Sets the lowest order bits, with the remaining bits all zero.
   * May be or'ed with the results of {@link #parseHash1Name(java.lang.String)}
   * and {@link #parseHash2Name(java.lang.String)}.
   *
   * @throws NumberFormatException when not a valid four-character lower-case hex number
   *
   * @see #parseHashName(java.lang.String, int)
   * @see #parseHash1Name(java.lang.String)
   * @see #parseHash2Name(java.lang.String)
   */
  static long parseResourceName(String resourceName) throws NumberFormatException {
    // Lowest bits
    return parseHashName(resourceName, RESOURCE_HASH_CHARS);
  }

  /**
   * Determines if a directory matches the pattern for a new resource directory.
   */
  static boolean isNewResource(String resourceName) {
    int expectedLen = RESOURCE_HASH_CHARS + 1 + CdnData.NEW_EXTENSION.length();
    if (resourceName.length() != expectedLen || !resourceName.endsWith(CdnData.EXTENSION_SEPARATOR + CdnData.NEW_EXTENSION)) {
      return false;
    }
    // Verify all characters are individually parseable
    try {
      parseResourceName(resourceName.substring(0, RESOURCE_HASH_CHARS));
      return true;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  /**
   * Gets a hex value for the lower-order 4 bits of the given value.
   */
  private static char getHexChar(long value) {
    int v = ((int) value) & 0xf;
    if (v < 10) {
      return (char) ('0' + v);
    } else {
      return (char) ('a' + v - 10);
    }
  }

  /**
   * Gets the hash directory name for the lower-order bits of the given ID.
   */
  private static String getHashName(long value, int numChars) {
    char[] chars = new char[numChars];
    long remaining = value;
    for (int i = numChars - 1; i >= 0; i--) {
      chars[i] = getHexChar(remaining);
      remaining >>>= BITS_PER_HEX_CHAR;
    }
    String result = new String(chars);
    assert result.length() == numChars;
    assert parseHashName(result, numChars) == (value & (-1L >>> (Long.SIZE - numChars * BITS_PER_HEX_CHAR)));
    return result;
  }

  /**
   * Gets the hash1 directory name for the given ID.
   */
  private static String getHash1Name(SmallIdentifier id) {
    long value = id.getValue();
    String result = getHashName(value >>> (BITS_PER_HEX_CHAR * (HASH_CHARS + RESOURCE_HASH_CHARS)), HASH_CHARS);
    assert result.length() == HASH_CHARS;
    assert (parseHash1Name(result) >>> (BITS_PER_HEX_CHAR * (HASH_CHARS + RESOURCE_HASH_CHARS))) == (value >>> (BITS_PER_HEX_CHAR * (HASH_CHARS + RESOURCE_HASH_CHARS)));
    return result;
  }

  /**
   * Gets the hash2 directory name for the given ID.
   */
  private static String getHash2Name(SmallIdentifier id) {
    long value = id.getValue();
    String result = getHashName(value >>> (BITS_PER_HEX_CHAR * RESOURCE_HASH_CHARS), HASH_CHARS);
    assert result.length() == HASH_CHARS;
    assert parseHash2Name(result)
        ==
        // Shift left then right to zero-out hash1 bits
        value << (BITS_PER_HEX_CHAR * HASH_CHARS)
        // Shift all the way right to zero-out resource bits
        >>> (BITS_PER_HEX_CHAR * (HASH_CHARS + RESOURCE_HASH_CHARS))
        // Shift back into original location
        << (BITS_PER_HEX_CHAR * RESOURCE_HASH_CHARS);
    return result;
  }

  /**
   * Gets the resource directory name for the given ID.
   */
  private static String getResourceName(SmallIdentifier id) {
    long value = id.getValue();
    String result = getHashName(value, RESOURCE_HASH_CHARS);
    assert result.length() == RESOURCE_HASH_CHARS;
    assert parseResourceName(result) == (value & (-1L >>> (Long.SIZE - BITS_PER_HEX_CHAR * RESOURCE_HASH_CHARS)));
    return result;
  }

  final CdnData cdnData;
  final Path resourcesDir;

  Resources(CdnData cdnData, Path resourcesDir) {
    this.cdnData = cdnData;
    this.resourcesDir = resourcesDir;
  }

  @Override
  public String toString() {
    return Resources.class.getName() + "(\"" + resourcesDir + "\")";
  }

  /**
   * Locks the resources directory.  This locks the entire set of resources, which can be used to perform an atomic
   * check for an existing resource before adding a new one.  Without this locking, a resource could be duplicated
   * on concurrent addition.
   *
   * @see  DirectoryLock#DirectoryLock(java.nio.file.Path, boolean)
   */
  DirectoryLock lock(boolean shared) throws IOException {
    return new DirectoryLock(resourcesDir, shared);
  }

  /**
   * Performs an integrity check on a resource directory.
   *
   * @param synchronizePaths Only non-null when in start-up mode and able to make filesystem modifications
   */
  private void fsckResourceDir(Map<Path, FsckIssue> issues, Set<Path> synchronizePaths, long hash1Value, long hash2Value, Path resourceDir) {
    if (!Files.isDirectory(resourceDir, LinkOption.NOFOLLOW_LINKS)) {
      issues.put(resourceDir, new FsckIssue(Level.WARNING, "Non-directory for resource directory"));
    } else {
      String resourceDirName = FileSystemUtils.getFileName(resourceDir);
      if (Resources.isNewResource(resourceDirName)) {
        if (synchronizePaths != null && cdnData.uploads != null) {
          issues.put(resourceDir, new FsckIssue(Level.INFO, "Deleting new resource directory"));
          // Final directory should not contain sub-directories, just delete one level deep
          boolean deleteResourceDir = true;
          try {
            for (Path resourceFile : FileSystemUtils.list(resourceDir)) {
              try {
                Files.delete(resourceFile);
                issues.put(resourceFile, new FsckIssue(Level.INFO, "Deleted new resource file"));
              } catch (IOException e) {
                issues.put(resourceFile, new FsckIssue(Level.SEVERE, e, "Unable to delete new resource file"));
                deleteResourceDir = false;
              }
            }
          } catch (IOException e) {
            issues.put(resourceDir, new FsckIssue(Level.SEVERE, e, "Unable to list new resource directory"));
            deleteResourceDir = false;
          }
          if (deleteResourceDir) {
            try {
              Files.delete(resourceDir);
            } catch (IOException e) {
              issues.put(resourceDir, new FsckIssue(Level.SEVERE, e, "Unable to delete new resource directory"));
            }
          }
        } else {
          // TODO: Upgrade to warning for *.new older than a certain time frame
          issues.put(resourceDir, new FsckIssue(Level.INFO, "Skipping new resource directory"));
        }
      } else {
        try {
          SmallIdentifier resourceId = new SmallIdentifier(hash1Value | hash2Value
              | Resources.parseResourceName(resourceDirName));
          Resource resource = new Resource(cdnData, resourceId, resourceDir);
          resource.fsckResource(issues, synchronizePaths);
        } catch (NumberFormatException e) {
          issues.put(resourceDir, new FsckIssue(Level.WARNING, "Unexpected name for resource directory"));
        }
      }
    }
  }

  /**
   * Performs an integrity check on hash directory level 2.
   *
   * @param synchronizePaths Only non-null when in start-up mode and able to make filesystem modifications
   */
  private void fsckHash2Dir(Map<Path, FsckIssue> issues, Set<Path> synchronizePaths, long hash1Value, Path hash2Dir) {
    try {
      long hash2Value = Resources.parseHash2Name(FileSystemUtils.getFileName(hash2Dir));
      if (!Files.isDirectory(hash2Dir, LinkOption.NOFOLLOW_LINKS)) {
        issues.put(hash2Dir, new FsckIssue(Level.WARNING, "Non-directory in hash directory level 2"));
      } else {
        boolean checkIfEmpty;
        try {
          for (Path resourceDir : FileSystemUtils.list(hash2Dir)) {
            fsckResourceDir(issues, synchronizePaths, hash1Value, hash2Value, resourceDir);
          }
          checkIfEmpty = true;
        } catch (IOException e) {
          issues.put(hash2Dir, new FsckIssue(Level.SEVERE, e, "Unable to list hash directory level 2"));
          checkIfEmpty = false;
        }
        if (checkIfEmpty) {
          // Delete hash2Dir if now empty
          boolean isEmpty;
          try {
            isEmpty = FileSystemUtils.list(hash2Dir).isEmpty();
          } catch (IOException e) {
            issues.put(hash2Dir, new FsckIssue(Level.SEVERE, e, "Unable to list hash directory level 2"));
            isEmpty = false;
          }
          if (isEmpty) {
            if (cdnData.uploads != null && synchronizePaths != null) {
              try {
                Files.delete(hash2Dir);
                issues.put(hash2Dir, new FsckIssue(Level.INFO, "Deleted empty hash directory level 2"));
                synchronizePaths.add(hash2Dir);
              } catch (IOException e) {
                issues.put(hash2Dir, new FsckIssue(Level.SEVERE, e, "Unable to delete empty hash directory level 2"));
              }
            } else {
              issues.put(hash2Dir, new FsckIssue(Level.INFO, "Found empty hash directory level 2"));
            }
          }
        }
      }
    } catch (NumberFormatException e) {
      issues.put(hash2Dir, new FsckIssue(Level.WARNING, "Unexpected name for hash directory level 2"));
    }
  }

  /**
   * Performs an integrity check on hash directory level 1.
   *
   * @param synchronizePaths Only non-null when in start-up mode and able to make filesystem modifications
   */
  private void fsckHash1Dir(Map<Path, FsckIssue> issues, Set<Path> synchronizePaths, Path hash1Dir) {
    try {
      long hash1Value = Resources.parseHash1Name(FileSystemUtils.getFileName(hash1Dir));
      if (!Files.isDirectory(hash1Dir, LinkOption.NOFOLLOW_LINKS)) {
        issues.put(hash1Dir, new FsckIssue(Level.WARNING, "Non-directory in hash directory level 1"));
      } else {
        boolean checkIfEmpty;
        try {
          for (Path hash2Dir : FileSystemUtils.list(hash1Dir)) {
            fsckHash2Dir(issues, synchronizePaths, hash1Value, hash2Dir);
          }
          checkIfEmpty = true;
        } catch (IOException e) {
          issues.put(hash1Dir, new FsckIssue(Level.SEVERE, e, "Unable to list hash directory level 1"));
          checkIfEmpty = false;
        }
        if (checkIfEmpty) {
          // Delete hash1Dir if now empty
          boolean isEmpty;
          try {
            isEmpty = FileSystemUtils.list(hash1Dir).isEmpty();
          } catch (IOException e) {
            issues.put(hash1Dir, new FsckIssue(Level.SEVERE, e, "Unable to list hash directory level 1"));
            isEmpty = false;
          }
          if (isEmpty) {
            if (cdnData.uploads != null && synchronizePaths != null) {
              try {
                Files.delete(hash1Dir);
                issues.put(hash1Dir, new FsckIssue(Level.INFO, "Deleted empty hash directory level 1"));
                // Remove all that start with hash1dir
                Iterator<Path> existingIter = synchronizePaths.iterator();
                while (existingIter.hasNext()) {
                  if (existingIter.next().startsWith(hash1Dir)) {
                    existingIter.remove();
                  }
                }
                synchronizePaths.add(hash1Dir);
              } catch (IOException e) {
                issues.put(hash1Dir, new FsckIssue(Level.SEVERE, e, "Unable to delete empty hash directory level 1"));
              }
            } else {
              issues.put(hash1Dir, new FsckIssue(Level.INFO, "Found empty hash directory level 1"));
            }
          }
        }
      }
    } catch (NumberFormatException e) {
      issues.put(hash1Dir, new FsckIssue(Level.WARNING, "Unexpected name for hash directory level 1"));
    }
  }

  /**
   * Filters past expected non-hash1 directories inside resources directory.
   * Currently skips {@link DirectoryLock#LOCK_FILE}.
   */
  static final IOPredicate<Path> resourcesDirFilter = path ->
      !DirectoryLock.LOCK_FILE.equals(FileSystemUtils.getFileName(path));

  /**
   * Performs an integrity check on the resources directories on start-up.
   * <p>
   * {@link #lock(boolean) Locks the resources directory} with shared mode for runtime checks and exclusive mode
   * when starting-up.
   * </p>
   *
   * @param synchronizePaths Only non-null when in start-up mode and able to make filesystem modifications
   */
  void fsckResourcesDirectories(Map<Path, FsckIssue> issues, Set<Path> synchronizePaths) {
    try (DirectoryLock lock = lock(synchronizePaths == null)) {
      assert lock.isValid();
      try {
        for (Path hash1Dir : FileSystemUtils.list(resourcesDir, resourcesDirFilter)) {
          fsckHash1Dir(issues, synchronizePaths, hash1Dir);
        }
      } catch (IOException e) {
        issues.put(resourcesDir, new FsckIssue(Level.SEVERE, e, "Unable to list resources directory"));
      }
    } catch (IOException e) {
      issues.put(resourcesDir, new FsckIssue(Level.SEVERE, e, "Unable to lock resources directory"));
    }
  }

  /**
   * Iterates all of the resources.
   */
  @Override
  public Iterator<Resource> iterator() {
    try {
      return new ResourcesIterator(this);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Only one new identifier is created at a time since its availability is determined by present of "directory"
   * or "directory.new".
   */
  private static final Object newIdLock = new Object();

  /**
   * Adds a new resource, allocating a new *.tmp directory, using the given upload file as the original resource.
   * <p>
   * Holds {@linkplain #lock(boolean) an exclusive lock} to avoid possibly duplications of new resources.
   * New resources are only added by the backoffice administrators, so concurrency is not important.
   * The lock is released, however, before {@linkplain Csync2 synchronizing the cluster}.
   * </p>
   */
  Resource addNewResource(Path tempFile, long tempFileSize, ContentType contentType) throws IOException {
    Path syncPath;
    Resource resource;
    try (DirectoryLock lock = lock(false)) {
      assert lock.isValid();
      SmallIdentifier id;
      Path resourceDir;
      Path resourceNewDir;
      synchronized (newIdLock) {
        while (true) {
          syncPath = null;
          id = new SmallIdentifier();
          String hash1Name = getHash1Name(id);
          Path hash1Dir = resourcesDir.resolve(hash1Name);
          if (Files.notExists(hash1Dir, LinkOption.NOFOLLOW_LINKS)) {
            FileSystemUtils.makeDirectory(hash1Dir);
            syncPath = hash1Dir;
          } else if (!Files.isDirectory(hash1Dir, LinkOption.NOFOLLOW_LINKS)) {
            logger.log(Level.WARNING, () -> "Discarding possible id due to non-directory in hash directory level 1: " + hash1Dir);
            continue;
          }
          String hash2Name = getHash2Name(id);
          Path hash2Dir = hash1Dir.resolve(hash2Name);
          if (Files.notExists(hash2Dir, LinkOption.NOFOLLOW_LINKS)) {
            FileSystemUtils.makeDirectory(hash2Dir);
            if (syncPath == null) {
              syncPath = hash2Dir;
            }
          } else if (!Files.isDirectory(hash2Dir, LinkOption.NOFOLLOW_LINKS)) {
            logger.log(Level.WARNING, () -> "Discarding possible id due to non-directory in hash directory level 2: " + hash2Dir);
            continue;
          }
          String resourceName = getResourceName(id);
          resourceDir = hash2Dir.resolve(resourceName);
          if (Files.notExists(resourceDir, LinkOption.NOFOLLOW_LINKS)) {
            String resourceNameNew = resourceName + CdnData.EXTENSION_SEPARATOR + CdnData.NEW_EXTENSION;
            resourceNewDir = hash2Dir.resolve(resourceNameNew);
            if (Files.notExists(resourceNewDir, LinkOption.NOFOLLOW_LINKS)) {
              FileSystemUtils.makeDirectory(resourceNewDir);
              if (syncPath == null) {
                syncPath = resourceDir;
              }
              break;
            } else {
              Path finalResourceNewDir = resourceNewDir;
              logger.log(Level.INFO, () -> "Discarding id since already used by new resource directory: " + finalResourceNewDir);
            }
          } else {
            Path finalResourceDir = resourceDir;
            logger.log(Level.INFO, () -> "Discarding id since already used by resource directory: " + finalResourceDir);
          }
        }
      }
      assert syncPath != null;
      assert id != null;
      assert Files.exists(resourceNewDir, LinkOption.NOFOLLOW_LINKS);
      assert Files.notExists(resourceDir, LinkOption.NOFOLLOW_LINKS);
      resource = new Resource(cdnData, id, resourceDir);
      // Determine versioned content type and filename
      Version originalVersion = contentType.createVersionByReadFile(resource, tempFile);
      String versionFilename = originalVersion.getFilename();
      Path versionFile = resourceNewDir.resolve(versionFilename);
      // Move/copy into versioned filename
      try {
        Files.move(tempFile, versionFile, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        logger.log(Level.FINE, e, () -> "Reverting to copy/delete from " + tempFile + " to " + versionFile);
        Files.copy(tempFile, versionFile, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
        Files.delete(tempFile);
      }
      // Symlink to original.(extension)
      Path originalExt = resourceNewDir.resolve(Resource.ORIGINAL_PREFIX + CdnData.EXTENSION_SEPARATOR
          + contentType.getExtension());
      Files.createSymbolicLink(originalExt, Paths.get("", versionFilename));
      // Check that tempFileSize still matches before moving *.new into place.  Can indicate concurrent modification
      long versionFileSize = Files.size(versionFile);
      if (tempFileSize != versionFileSize) {
        throw new ConcurrentModificationException();
      }
      // Move resource into place
      Files.move(resourceNewDir, resourceDir, StandardCopyOption.ATOMIC_MOVE);
    }
    // Synchronize cluster
    cdnData.csync2.synchronize(syncPath);
    return resource;
  }

  /**
   * Gets a resource given its unique identifier or {@link Optional#empty()} when resource not found.
   */
  public Optional<Resource> getResource(SmallIdentifier id) {
    String hash1Name = getHash1Name(id);
    Path hash1Dir = resourcesDir.resolve(hash1Name);
    if (Files.notExists(hash1Dir, LinkOption.NOFOLLOW_LINKS)) {
      return Optional.empty();
    }
    if (!Files.isDirectory(hash1Dir, LinkOption.NOFOLLOW_LINKS)) {
      logger.log(Level.WARNING, () -> "Skipping non-directory in hash directory level 1: " + hash1Dir);
      return Optional.empty();
    }
    String hash2Name = getHash2Name(id);
    Path hash2Dir = hash1Dir.resolve(hash2Name);
    if (Files.notExists(hash2Dir, LinkOption.NOFOLLOW_LINKS)) {
      return Optional.empty();
    }
    if (!Files.isDirectory(hash2Dir, LinkOption.NOFOLLOW_LINKS)) {
      logger.log(Level.WARNING, () -> "Skipping non-directory in hash directory level 2: " + hash2Dir);
      return Optional.empty();
    }
    String resourceName = getResourceName(id);
    Path resourceDir = hash2Dir.resolve(resourceName);
    if (Files.notExists(resourceDir, LinkOption.NOFOLLOW_LINKS)) {
      return Optional.empty();
    }
    if (!Files.isDirectory(resourceDir, LinkOption.NOFOLLOW_LINKS)) {
      logger.log(Level.WARNING, () -> "Skipping non-directory for resource directory: " + resourceDir);
      return Optional.empty();
    }
    return Optional.of(new Resource(cdnData, id, resourceDir));
  }
}
