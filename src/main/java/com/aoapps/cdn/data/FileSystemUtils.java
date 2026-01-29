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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Helper methods for working with {@link FileSystem}, {@link Files}, and {@link Path}.
 */
final class FileSystemUtils {

  /** Make no instances. */
  private FileSystemUtils() {
    throw new AssertionError();
  }

  private static final FileAttribute<Set<PosixFilePermission>> NEW_DIRECTORY_PERMISSIONS = PosixFilePermissions.asFileAttribute(
      EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
          PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE));

  static final FileAttribute<Set<PosixFilePermission>> NEW_FILE_PERMISSIONS = PosixFilePermissions.asFileAttribute(
      EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
          PosixFilePermission.GROUP_READ));

  /**
   * Checks if POSIX attributes are supported to decide whether to use the given attributes.
   *
   * @return  The given attributes when supported or an empty array when not supported.
   */
  static FileAttribute<?>[] usePosixAttributesWhenSupported(FileSystem fileSystem, FileAttribute<Set<PosixFilePermission>> posixAttributes) {
    if (fileSystem.supportedFileAttributeViews().contains("posix")) {
      return new FileAttribute<?>[] {posixAttributes};
    } else {
      // Unsupported, use no attributes
      return new FileAttribute<?>[0];
    }
  }

  /**
   * Creates a directory with the correct permissions.
   */
  static Path makeDirectory(Path dir) throws IOException {
    return Files.createDirectory(dir, usePosixAttributesWhenSupported(dir.getFileSystem(), NEW_DIRECTORY_PERMISSIONS));
  }

  /**
   * Creates a new directory, if needed, optionally including any parents.  Verifies any existing is a directory.
   *
   * <p>When supported, any new directory has permissions set to expected values.</p>
   *
   * @param csync2  When not {@code null}, will immediately synchronize the cluster when making a new directory
   * @param dir     The directory to create or verify
   * @param mkdirs  Also create parent directories if missing
   *
   * @see  FileSystemUtils#makeDirectoryIfNeeded(com.aoapps.cdn.data.Csync2, java.nio.file.Path)
   */
  static Path makeDirectoryIfNeeded(Csync2 csync2, Path dir, boolean mkdirs) throws IOException {
    if (Files.notExists(dir, LinkOption.NOFOLLOW_LINKS)) {
      if (mkdirs) {
        Files.createDirectories(dir, usePosixAttributesWhenSupported(dir.getFileSystem(), NEW_DIRECTORY_PERMISSIONS));
      } else {
        makeDirectory(dir);
      }
      if (csync2 != null) {
        csync2.synchronize(dir);
      }
    } else if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Existing is not a directory: " + dir);
    }
    return dir;
  }

  /**
   * Creates a new directory, if needed, not including any parents.  Verifies any existing is a directory.
   *
   * <p>When supported, any new directory has permissions set to expected values.</p>
   *
   * @param csync2  When not {@code null}, will immediately synchronize the cluster when making a new directory
   * @param dir     The directory to create or verify
   *
   * @see  FileSystemUtils#makeDirectoryIfNeeded(com.aoapps.cdn.data.Csync2, java.nio.file.Path, boolean)
   */
  static Path makeDirectoryIfNeeded(Csync2 csync2, Path dir) throws IOException {
    return makeDirectoryIfNeeded(csync2, dir, false);
  }

  /**
   * Creates a new temp file with {@link FileSystemUtils#NEW_FILE_PERMISSIONS}.
   */
  static Path createTempFile(Path dir, String prefix, ContentType contentType) throws IOException {
    return Files.createTempFile(
        dir,
        prefix,
        CdnData.EXTENSION_SEPARATOR + contentType.getExtension(),
        usePosixAttributesWhenSupported(dir.getFileSystem(), NEW_FILE_PERMISSIONS)
    );
  }

  /**
   * Lists a directory into a {@link List}.
   */
  static List<Path> list(Path dir) throws IOException {
    try (Stream<Path> stream = Files.list(dir)) {
      return stream.collect(Collectors.toList());
    }
  }

  /**
   * Lists a directory into a {@link List} with a given filter.
   */
  static List<Path> list(Path dir, IOPredicate<Path> filter) throws IOException {
    try (Stream<Path> stream = Files.list(dir)) {
      return stream
          .filter(path -> {
            try {
              return filter.test(path);
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
          })
          .collect(Collectors.toList());
    } catch (UncheckedIOException e) {
      throw e.getCause();
    }
  }

  /**
   * Gets the file name or empty string when none.
   */
  static String getFileName(Path path) {
    return Objects.toString(path.getFileName(), "");
  }
}
