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
import java.nio.file.Path;
import java.util.Objects;

/**
 * Only a specific set of content types are supported by the CDN.
 */
public enum ContentType {
  JPEG {
    @Override
    public String getMimeType() {
      return com.aoapps.lang.io.ContentType.JPEG;
    }

    @Override
    public String getExtension() {
      return "jpg";
    }

    @Override
    Version createVersionByReadFile(Resource resource, Path versionFile) throws IOException {
      return ImageVersion.createImageVersionByReadFile(resource, this, versionFile);
    }

    @Override
    Version createVersionByParseFilename(Resource resource, Path versionFile) {
      return ImageVersion.createImageVersionByParseFilename(resource, this, versionFile);
    }
  },
  PNG {
    @Override
    public String getMimeType() {
      return com.aoapps.lang.io.ContentType.PNG;
    }

    @Override
    public String getExtension() {
      return "png";
    }

    @Override
    Version createVersionByReadFile(Resource resource, Path versionFile) throws IOException {
      return ImageVersion.createImageVersionByReadFile(resource, this, versionFile);
    }

    @Override
    Version createVersionByParseFilename(Resource resource, Path versionFile) {
      return ImageVersion.createImageVersionByParseFilename(resource, this, versionFile);
    }
  },
  GIF {
    @Override
    public String getMimeType() {
      return com.aoapps.lang.io.ContentType.GIF;
    }

    @Override
    public String getExtension() {
      return "gif";
    }

    @Override
    Version createVersionByReadFile(Resource resource, Path versionFile) throws IOException {
      return ImageVersion.createImageVersionByReadFile(resource, this, versionFile);
    }

    @Override
    Version createVersionByParseFilename(Resource resource, Path versionFile) {
      return ImageVersion.createImageVersionByParseFilename(resource, this, versionFile);
    }
  };
  // TODO: Video support

  /**
   * Gets the distinct MIME content type.
   * This is used provided in headers when the resource is requested.
   * <p>
   * No corresponding method exists for getting character encoding, since the CDN is only storing binary files.
   * </p>
   */
  public abstract String getMimeType();

  /**
   * Each content type is represented by a distinct file extension.
   * This is used internally in the filesystem and externally in the URL.
   */
  public abstract String getExtension();

  /**
   * Only get once.
   */
  static final ContentType[] values = values();

  /**
   * Gets the content type from its extension, case-sensitive.
   *
   * @throws IllegalArgumentException when no content type has the given extension
   * @throws NullPointerException when extension is null
   *
   * @see #getContentTypeForMimeType(java.lang.String)
   * @see #valueOf(java.lang.String)
   */
  public static ContentType getContentTypeForExtension(String extension) throws IllegalArgumentException, NullPointerException {
    Objects.requireNonNull(extension);
    for (ContentType value : values) {
      if (value.getExtension().equals(extension)) {
        return value;
      }
    }
    throw new IllegalArgumentException("No content type with extension \"" + extension + '"');
  }

  /**
   * Gets the content type from its content type, case-insensitive, matching up to first ';', and
   * trimmed.
   *
   * @throws IllegalArgumentException when no content type has the given content type
   * @throws NullPointerException when content type is null
   *
   * @see #getContentTypeForExtension(java.lang.String)
   * @see #valueOf(java.lang.String)
   */
  public static ContentType getContentTypeForMimeType(String mimeType) throws IllegalArgumentException, NullPointerException {
    String trimmed = Objects.requireNonNull(mimeType);
    int semiPos = trimmed.indexOf(';');
    if (semiPos != -1) {
      trimmed = trimmed.substring(0, semiPos);
    }
    trimmed = trimmed.trim();
    for (ContentType value : values) {
      String valueMimeType = value.getMimeType();
      assert valueMimeType.indexOf(';') == -1;
      if (valueMimeType.equalsIgnoreCase(trimmed)) {
        return value;
      }
    }
    throw new IllegalArgumentException("No content type with MIME type \"" + mimeType + '"');
  }

  /**
   * Creates the correct type of version, reading the underlying file to determine the meta data.
   */
  abstract Version createVersionByReadFile(Resource resource, Path versionFile) throws IOException;

  /**
   * Creates the correct type of version, parsing the meta data from the filename.
   *
   * @throws IllegalArgumentException when unable to parse
   */
  abstract Version createVersionByParseFilename(Resource resource, Path versionFile) throws IllegalArgumentException;
}
