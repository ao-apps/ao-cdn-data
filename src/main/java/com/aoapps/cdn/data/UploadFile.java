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

import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A file that is being uploaded, and may be partial/incomplete.
 *
 * <p>Once {@linkplain CdnData#findOrAdd(com.aoapps.cdn.data.UploadFile) added}, the uploaded
 * file must not be modified.</p>
 *
 * @see  Uploads#createUploadFile(com.aoapps.cdn.data.ContentType)
 * @see  CdnData#findOrAdd(com.aoapps.cdn.data.UploadFile)
 */
public class UploadFile {

  private final CdnData cdnData;
  private final ContentType contentType;
  private final AtomicReference<Path> tempFile;

  UploadFile(CdnData cdnData, ContentType contentType, Path tempFile) {
    this.cdnData = cdnData;
    this.contentType = contentType;
    this.tempFile = new AtomicReference<>(tempFile);
  }

  /**
   * Gets the CDN this upload file is for.
   */
  public CdnData getCdnData() {
    return cdnData;
  }

  /**
   * Gets the content type for this upload file.
   */
  public ContentType getContentType() {
    return contentType;
  }

  /**
   * Gets the temp file, which will be {@code null} (and previous underlying file will not exist) once
   * {@linkplain CdnData#findOrAdd(com.aoapps.cdn.data.UploadFile) added}.
   *
   * <p>When writing the file, it is suggested to {@link OutputStream#flush()} then {@link FileChannel#force(boolean)}
   * or open channel with {@link StandardOpenOption#SYNC}.</p>
   */
  public Path getTempFile() {
    return tempFile.get();
  }

  /**
   * Atomically gets the value of temp file while setting it to {@code null}.
   *
   * @return The previous value, which will be null if this has already been done before
   *         ({@linkplain CdnData#findOrAdd(com.aoapps.cdn.data.UploadFile) findOrAdd} called twice).
   */
  Path getAndRemoveTempFile() {
    return tempFile.getAndSet(null);
  }
}
