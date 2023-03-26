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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Level;

/**
 * One version of a resource.  A resource will always have the original version, and any number of derived versions.
 */
public abstract class Version {

  final Resource resource;
  final ContentType contentType;
  final Path versionFile;

  Version(Resource resource, ContentType contentType, Path versionFile) {
    this.resource = resource;
    this.contentType = contentType;
    this.versionFile = versionFile;
  }

  @Override
  public String toString() {
    return versionFile.toString();
  }

  @Override
  public int hashCode() {
    return versionFile.hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    return
        (obj instanceof Version)
        && versionFile.equals(((Version) obj).versionFile);
  }

  public Resource getResource() {
    return resource;
  }

  public ContentType getContentType() {
    return contentType;
  }

  /**
   * Performs an integrity check on a version.
   */
  void fsckVersion(Map<Path, FsckIssue> issues) {
    if (Files.notExists(versionFile, LinkOption.NOFOLLOW_LINKS)) {
      issues.put(versionFile, new FsckIssue(Level.SEVERE, "Version file missing"));
    }
    if (!Files.isRegularFile(versionFile, LinkOption.NOFOLLOW_LINKS)) {
      issues.put(versionFile, new FsckIssue(Level.SEVERE, "Version is not a regular file"));
    }
  }

  private void checkIsRegularFile() throws IOException {
    if (!Files.isRegularFile(versionFile, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Version is not a regular file: " + versionFile);
    }
  }

  /**
   * Gets the file size for this version.
   *
   * @see  Files#isRegularFile(java.nio.file.Path, java.nio.file.LinkOption...)
   * @see  Files#size(java.nio.file.Path)
   */
  long size() throws IOException {
    checkIsRegularFile();
    return Files.size(versionFile);
  }

  /**
   * Gets an unbuffered input stream this version.
   *
   * @see  Files#isRegularFile(java.nio.file.Path, java.nio.file.LinkOption...)
   * @see  Files#newInputStream(java.nio.file.Path, java.nio.file.OpenOption...)
   */
  InputStream newInputStream() throws IOException {
    checkIsRegularFile();
    return Files.newInputStream(versionFile, LinkOption.NOFOLLOW_LINKS);
  }

  /**
   * Gets the filename to use within the resource directory.
   */
  abstract String getFilename();
}
