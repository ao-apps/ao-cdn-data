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

import com.aoapps.lang.io.function.IOPredicate;
import com.aoapps.security.SmallIdentifier;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * A resource has both the originally uploaded content as well as any number of derived versions.
 * Derived versions include things like scaling size, reducing quality, change frame rates, bit speeds, and such.
 * Derived versions may be of a different {@linkplain ContentType content type than the original}, such as when
 * a thumbnail image is extracted from the first frame of an mp4.
 * <p>
 * TODO: Versions may have a different content type than the original, such as the first-frame still image for a video
 * file.  This means that a resource may be access by different extensions, even for original unqualified:
 *           xyz.mp4 would redirect to original version video
 *           xyz.jpg would redirect to version of first frame at original video size
 *           xyz-(width)x(height).mp4 would be scaled video
 *           xyz-(width)x(height).jpg would be scaled first frame
 * </p>
 */
public class Resource {

  /**
   * The filename prefix used for the original resource symlink.
   */
  static final String ORIGINAL_PREFIX = "original";

  final CdnData cdnData;
  private final SmallIdentifier id;
  final Path resourceDir;

  /**
   * Creates a new resource.
   *
   * @param resourceDir The directory may not yet exist while adding a new resource in the *.new directory.
   */
  Resource(CdnData cdnData, SmallIdentifier id, Path resourceDir) {
    this.cdnData = cdnData;
    this.id = id;
    this.resourceDir = resourceDir;
  }

  @Override
  public String toString() {
    return id.toString() + "@" + resourceDir;
  }

  @Override
  public boolean equals(Object obj) {
    Resource other;
    return
        (obj instanceof Resource)
        && cdnData == (other = (Resource) obj).cdnData
        && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return id.hashCode();
  }

  /**
   * Gets the CDN this resource is part of.
   */
  public CdnData getCdnData() {
    return cdnData;
  }

  /**
   * Gets the unique identifier for this resource.
   */
  public SmallIdentifier getId() {
    return id;
  }

  /**
   * Locks the resource directory.
   *
   * @see  DirectoryLock#DirectoryLock(java.nio.file.Path, boolean)
   */
  DirectoryLock lock(boolean shared) throws IOException {
    return new DirectoryLock(resourceDir, shared);
  }

  /**
   * Performs an integrity check on a resource.
   * <p>
   * {@link #lock(boolean) Locks the resource directory} with shared mode for runtime checks and exclusive mode
   * when starting-up.
   * </p>
   *
   * @param synchronizePaths Only non-null when in start-up mode and able to make filesystem modifications
   */
  void fsckResource(Map<Path, FsckIssue> issues, Set<Path> synchronizePaths) {
    try (DirectoryLock lock = lock(synchronizePaths == null)) {
      assert lock.isValid();
      // List contents
      List<Path> paths;
      try {
        paths = FileSystemUtils.list(resourceDir);
      } catch (IOException e) {
        issues.put(resourceDir, new FsckIssue(Level.SEVERE, e, "Unable to list resource directory"));
        return;
      }
      // Original verification
      // Make sure one and only one original symlink found
      final String originalSep = ORIGINAL_PREFIX + CdnData.EXTENSION_SEPARATOR;
      Path originalPath = null;
      for (Path path : paths) {
        String fileName = FileSystemUtils.getFileName(path);
        if (fileName.startsWith(originalSep)) {
          if (originalPath != null) {
            issues.put(resourceDir, new FsckIssue(Level.SEVERE,
                "More than one " + originalSep + "* path found in resource directory:\n"
                + "  path 1: \"" + originalPath + "\"\n"
                + "  path 2: \"" + path + '"'));
            originalPath = null;
            break;
          }
          originalPath = path;
        }
      }
      if (originalPath != null) {
        // Must be a symbolic link
        if (!Files.isSymbolicLink(originalPath)) {
          issues.put(originalPath, new FsckIssue(Level.SEVERE, "Original is not a symlink"));
        } else {
          // Make sure original is a supported content type
          final int originalSepLen = originalSep.length();
          final String originalFileName = FileSystemUtils.getFileName(originalPath);
          final int originalFileNameLen = originalFileName.length();
          ContentType originalContentType = null;
          for (ContentType contentType : ContentType.values) {
            String typeExt = contentType.getExtension();
            if (originalFileNameLen == (originalSepLen + typeExt.length()) && originalFileName.endsWith(typeExt)) {
              originalContentType = contentType;
              break;
            }
          }
          if (originalContentType == null) {
            issues.put(originalPath, new FsckIssue(Level.WARNING, "Original is not a supported content type"));
          } else {
            // Make sure symlink target of original exists
            Path originalVersionFile = originalPath.toRealPath();
            if (Files.notExists(originalVersionFile, LinkOption.NOFOLLOW_LINKS)) {
              issues.put(originalVersionFile, new FsckIssue(Level.SEVERE, "Original version does not exist"));
            } else {
              // Make sure symlink target matches type
              String expectedExtension = originalContentType.getExtension();
              String originalVersionFileName = FileSystemUtils.getFileName(originalVersionFile);
              if (!originalVersionFileName.endsWith(CdnData.EXTENSION_SEPARATOR + expectedExtension)) {
                issues.put(originalVersionFile, new FsckIssue(Level.SEVERE,
                    "Original version has mismatched extension: expected " + expectedExtension));
              }
            }
          }
        }
      }

      // Versions
      for (Path path : paths) {
        String fileName = FileSystemUtils.getFileName(path);
        if (
            // Lock file already verified during locking
            !DirectoryLock.LOCK_FILE.equals(fileName)
            // Originals already verified
            && !fileName.startsWith(ORIGINAL_PREFIX + CdnData.EXTENSION_SEPARATOR)
        ) {
          // Handle *.new files
          if (fileName.endsWith(CdnData.EXTENSION_SEPARATOR + CdnData.NEW_EXTENSION)) {
            // Verify is a regular file
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
              issues.put(path, new FsckIssue(Level.SEVERE, "New version is not a regular file"));
            } else if (synchronizePaths != null) {
              // In start-up mode, remove *.new found
              try {
                Files.delete(path);
                issues.put(path, new FsckIssue(Level.INFO, "Deleted new version file"));
              } catch (IOException e) {
                issues.put(path, new FsckIssue(Level.SEVERE, e, "Unable to delete new version file"));
              }
            } else {
              // TODO: Upgrade to warning for *.new older than a certain time frame
              issues.put(path, new FsckIssue(Level.INFO, "Skipping new version file"));
            }
          } else {
            // All other files should be versions
            int sepPos = fileName.lastIndexOf(CdnData.EXTENSION_SEPARATOR);
            if (sepPos == -1) {
              issues.put(path, new FsckIssue(Level.WARNING, "Unable to find extension to determine content type"));
            } else {
              ContentType versionContentType;
              try {
                versionContentType = ContentType.getContentTypeForExtension(fileName.substring(sepPos + 1));
                try {
                  Version version = versionContentType.createVersionByParseFilename(this, path);
                  version.fsckVersion(issues);
                  // TODO: Make sure is valid derivation from original (if not the original itself)
                  //       This could mean checking dimensions <= original, or content types allowed (jpg can come from mov, but mov not from jpg)
                } catch (IllegalArgumentException e) {
                  issues.put(path, new FsckIssue(Level.WARNING, e, "Unable to parse version filename"));
                }
              } catch (IllegalArgumentException e) {
                issues.put(path, new FsckIssue(Level.WARNING, e, "Unsupported content type"));
              }
            }
          }
        }
      }
    } catch (IOException e) {
      issues.put(resourceDir, new FsckIssue(Level.SEVERE, e, "Unable to lock resource directory"));
    }
  }

  /**
   * Filters files that are not versions.
   * <p>
   * Skips {@link DirectoryLock#LOCK_FILE}, {@link #ORIGINAL_PREFIX}, and {@link CdnData#NEW_EXTENSION}.
   * </p>
   */
  private static class VersionFilter implements IOPredicate<Path> {

    private final String dotExtension;

    /**
     * Creates a new filter.
     *
     * @param  contentType  The optional content type to filter for or {@code null} for any content type
     */
    private VersionFilter(ContentType contentType) {
      dotExtension = (contentType == null) ? null : (CdnData.EXTENSION_SEPARATOR + contentType.getExtension());
    }

    @Override
    public boolean test(Path path) {
      String fileName = FileSystemUtils.getFileName(path);
      return
          !DirectoryLock.LOCK_FILE.equals(fileName)
          && !fileName.startsWith(ORIGINAL_PREFIX + CdnData.EXTENSION_SEPARATOR)
          && !fileName.endsWith(CdnData.EXTENSION_SEPARATOR + CdnData.NEW_EXTENSION)
          && (dotExtension == null || fileName.endsWith(dotExtension));
    }
  }

  /**
   * Gets all versions of this resource, optionally filtered by content type.
   *
   * @param  contentType  The optional content type to filter for or {@code null} for any content type
   */
  public List<Version> getVersions(ContentType contentType) throws IOException {
    List<Path> paths = FileSystemUtils.list(resourceDir, new VersionFilter(contentType));
    List<Version> versions = new ArrayList<>(paths.size());
    for (Path path : paths) {
      versions.add(contentType.createVersionByParseFilename(this, path));
    }
    return versions;
  }

  /**
   * Gets all versions of this resource.
   */
  public List<Version> getVersions() throws IOException {
    return getVersions(null);
  }

  /**
   * Finds a version matching the binary data and the given type.
   * <p>
   * {@linkplain #lock(boolean) Resource locking} not performed since all variants of a resource
   * are effectively immutable (never modified).
   * </p>
   *
   * @param  contentType  The optional content type to filter for or {@code null} for any content type
   */
  Version findVersion(Path tempFile, long tempFileSize, ContentType contentType) throws IOException {
    for (Version version : getVersions(contentType)) {
      if (tempFileSize == version.size()) {
        // Read file and compare byte-by-byte
        boolean match;
        try (
            InputStream in1 = new BufferedInputStream(Files.newInputStream(tempFile, LinkOption.NOFOLLOW_LINKS));
            InputStream in2 = new BufferedInputStream(version.newInputStream())
        ) {
          do {
            int b1 = in1.read();
            int b2 = in2.read();
            match = (b1 == b2);
            if (b1 == -1) {
              break;
            } else if (b2 == -1) {
              match = false;
            }
          } while (match);
        }
        if (match) {
          return version;
        }
      }
    }
    return null;
  }

  /**
   * Determines the original content type by looking for the "original.(extension)" entry.
   * This is expected to be a symlink to the scaled name pattern, but this is not specifically checked;
   * only filenames are scanned.
   * <p>
   * {@linkplain #lock(boolean) Resource locking} not performed since the original version is created for a new resource
   * then effectively immutable (never modified).
   * </p>
   *
   * @throws IllegalArgumentException When unable to determine the content type
   */
  public ContentType getOriginalContentType() throws IOException, IllegalArgumentException {
    final String originalSep = ORIGINAL_PREFIX + CdnData.EXTENSION_SEPARATOR;
    final int originalSepLen = originalSep.length();
    for (Path path : FileSystemUtils.list(resourceDir)) {
      String fileName = FileSystemUtils.getFileName(path);
      if (fileName.startsWith(originalSep)) {
        int fileNameLen = fileName.length();
        for (ContentType contentType : ContentType.values) {
          String typeExt = contentType.getExtension();
          if (fileNameLen == (originalSepLen + typeExt.length()) && fileName.endsWith(typeExt)) {
            return contentType;
          }
        }
      }
    }
    throw new IllegalArgumentException("Unable to match " + ORIGINAL_PREFIX + CdnData.EXTENSION_SEPARATOR
        + "* to any content type extension: " + resourceDir);
  }

  /**
   * Gets the original version of this resource by following the original symlink and parsing the filename.
   * <p>
   * No {@linkplain #lock(boolean) resource locking} required since the original version is created for a new resource
   * then effectively immutable (never modified).
   * </p>
   */
  public Version getOriginal() throws IOException {
    ContentType originalType = getOriginalContentType();
    Path originalSymlink = resourceDir.resolve(ORIGINAL_PREFIX + CdnData.EXTENSION_SEPARATOR + originalType.getExtension());
    Path originalVersionFile = originalSymlink.toRealPath();
    return originalType.createVersionByParseFilename(this, originalVersionFile);
  }
}
