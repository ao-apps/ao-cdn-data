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
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Level;

/**
 * Manages access to the uploads directory.
 * Note: the uploads directory is excluded from replication inside csync2.cfg
 */
public class Uploads {

  /**
   * Name of the uploads directory.
   * This directory is excluded from Csync2 synchronization.
   */
  static final String UPLOADS_DIR_NAME = "uploads";

  private final CdnData cdnData;
  final Path uploadsDir;

  Uploads(CdnData cdnData, Path uploadsDir) {
    this.cdnData = cdnData;
    this.uploadsDir = uploadsDir;
  }

  @Override
  public String toString() {
    return Uploads.class.getName() + "(\"" + uploadsDir + "\")";
  }

  /**
   * Clear-out everything from the uploads directory on start-up.
   */
  void fsckUploadsDirectory(boolean isStartup, Map<Path, FsckIssue> issues) {
    try {
      for (Path uploadFile : FileSystemUtils.list(uploadsDir)) {
        if (!Files.isRegularFile(uploadFile, LinkOption.NOFOLLOW_LINKS)) {
          issues.put(uploadFile, new FsckIssue(Level.WARNING, "Non-file in uploads directory"));
        } else if (isStartup) {
          //  Only delete if is an expected extension, warn otherwise
          String fileName = FileSystemUtils.getFileName(uploadFile);
          boolean expected = false;
          for (ContentType contentType : ContentType.values) {
            if (fileName.endsWith(CdnData.EXTENSION_SEPARATOR + contentType.getExtension())) {
              expected = true;
              break;
            }
          }
          if (expected) {
            try {
              Files.delete(uploadFile);
            } catch (IOException e) {
              issues.put(uploadFile, new FsckIssue(Level.SEVERE, e, "Unable to delete file in uploads directory"));
            }
          } else {
            issues.put(uploadFile, new FsckIssue(Level.WARNING, "Unexpected file in uploads directory"));
          }
        }
      }
    } catch (IOException e) {
      issues.put(uploadsDir, new FsckIssue(Level.SEVERE, e, "Unable to list uploads directory"));
    }
  }

  /**
   * Creates a new upload file, which will be empty.  This upload file may be used in subsequent calls to add a new
   * resource.  By using these upload files, it ensures that data starts within the same filesystem and has the option
   * to be efficiently moved into place instead of copying data.
   * <p>
   * When supported, the new file has permissions set to expected values.
   * </p>
   *
   * @param  contentType  The new file will have {@linkplain ContentType#getExtension() an extension} for the given content type.
   *
   * @see  CdnData#findOrAdd(com.aoapps.cdn.data.UploadFile)
   * @see  #createUploadFile(com.aoapps.cdn.data.ContentType)
   */
  public UploadFile createUploadFile(ContentType contentType) throws IOException {
    return new UploadFile(
        cdnData,
        contentType,
        FileSystemUtils.createTempFile(uploadsDir, null, contentType)
    );
  }
}
