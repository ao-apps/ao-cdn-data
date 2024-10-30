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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Currently implemented directly in the filesystem, leveraging Csync2 in multi-master mode for replication.
 *
 * <p>TODO: Have a central warnings/errors structure.
 * Warnings by path for things that are anamolous (spelling), such as resource hash directories with unexpected names.
 * Warnings for things failed to auto-cleanup.
 * Errors by path for csync2 failures, could initialize state from a csync2 -t test.
 * Could background re-test either all csync2 or paths with known synchronization failures.
 * Could integrated into status page (or status.json).
 * Could integrated into AO monitoring.</p>
 */
public class CdnData {

  private static final Logger logger = Logger.getLogger(CdnData.class.getName());

  /**
   * The separator character to go between the resource or filename and
   * {@linkplain ContentType#getExtension() the extension}.
   * This is used internally in the filesystem and externally in the URL.
   */
  public static final char EXTENSION_SEPARATOR = '.';

  /**
   * File extension used for new (and possibly partial) directories and files.
   * Does not include the {@link #EXTENSION_SEPARATOR}.
   * These files are excluded from Csync2 synchronization.
   */
  static final String NEW_EXTENSION = "new";

  final Path cdnRoot;
  final Csync2 csync2;

  final Resources resources;
  final Uploads uploads;

  /**
   * Creates a new CDN data accessor.
   * An {@linkplain #fsck()} is performed immediately in start-up mode.  Any {@linkplain FsckIssue issue} that is
   * {@linkplain Level#SEVERE severe} or higher will cause the CDN to fail by throwing {@link IOException}.
   *
   * @param cdnRoot  The directory that contains the underlying CDN data.  This should not be in the web root directly,
   *                 but instead should be mapped into Tomcat via the <code>&lt;PreResource&gt;</code> mechanism.
   *                 The front-end component will dispatch to the resource after URL rewriting and access control.
   *                 Back-end components interact with this CDN data directly.
   *
   * @param csync2Group  The Csync2 group to be synchronized after each change.  When {@code null} empty
   *                     (after trimming), no synchronization is performed.
   *
   * @param isUploader  Is this the uploader instance of cdn-data, which is responsible for introducing entirely new
   *                    resources?
   *
   * @throws IOException When CDN start-up {@linkplain #fsck()} fails.
   *
   * @see #fsck()
   * @see #fsck(boolean)
   */
  @SuppressFBWarnings("CT_CONSTRUCTOR_THROW")
  public CdnData(Path cdnRoot, String csync2Group, boolean isUploader) throws IOException {
    this.cdnRoot = FileSystemUtils.makeDirectoryIfNeeded(
        null, // Never synchronize the root
        cdnRoot,
        true // Make any missing parents, too
    );
    this.csync2 = new Csync2(csync2Group);

    resources = new Resources(this, FileSystemUtils.makeDirectoryIfNeeded(
        csync2,
        this.cdnRoot.resolve(Resources.RESOURCES_DIR_NAME)
    ));
    if (isUploader) {
      uploads = new Uploads(this, FileSystemUtils.makeDirectoryIfNeeded(
          null, // The uploads directory is not synchronized
          this.cdnRoot.resolve(Uploads.UPLOADS_DIR_NAME)
      ));
    } else {
      uploads = null;
    }

    Map<Path, FsckIssue> issues = fsck(true);
    int numSevere = 0;
    for (Map.Entry<Path, FsckIssue> entry : issues.entrySet()) {
      Path path = entry.getKey();
      FsckIssue issue = entry.getValue();
      Level level = issue.getLevel();
      logger.log(level, issue.getThrown(), () -> path + ": " + issue.getMessage());
      if (level.intValue() >= Level.SEVERE.intValue()) {
        numSevere++;
      }
    }
    if (numSevere > 0) {
      throw new IOException("Start-up fsck failed with " + numSevere + " severe "
          + (numSevere == 1 ? "issue" : "issues") + ", see log file for details");
    }
  }

  @Override
  public String toString() {
    return cdnRoot.toString();
  }

  /**
   * Performs an fsck of this CDN data.
   *
   * @return  Unmodifiable map of issues
   */
  private Map<Path, FsckIssue> fsck(boolean isStartup) {
    return Fsck.fsck(this, isStartup);
  }

  /**
   * Performs a non-startup fsck of this CDN data.
   *
   * @return  Unmodifiable map of issues
   */
  public Map<Path, FsckIssue> fsck() {
    return fsck(false);
  }

  /**
   * Gets the resources handler for this CDN.
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "This is the intended public API")
  public Resources getResources() {
    return resources;
  }

  /**
   * Gets the uploads handler for this CDN.
   *
   * @throws IllegalStateException when this CDN data upload is disabled.
   */
  public Uploads getUploads() throws IllegalStateException {
    if (uploads == null) {
      throw new IllegalStateException("Uploads disabled");
    }
    return uploads;
  }

  /**
   * Stores a new file in CDN data.
   *
   * <p>First searches all existing resources for a match, including matching derived resources.  A full byte-by-byte check
   * is performed.</p>
   *
   * <p>When a match is found, the existing resource is returned.  This means that a higher quality resource may be
   * returned than the one requested.</p>
   *
   * @param uploadFile  This underlying file should have been returned from {@link Uploads#createUploadFile(com.aoapps.cdn.data.CdnData.ContentType)}.
   *                    If a different file is used, it may not have correct permissions and also may not be able to be moved into place efficiently.
   *
   *                    <p>The underlying file must have an extension matching {@linkplain ContentType#getExtension() the expected extension for the type}.</p>
   *
   *                    <p>This underlying file must not be changed after being stored.  Doing so may corrupted the underlying CDN data.</p>
   *
   * @return  The resource, whether found existing or stored new.  This resource will be
   *          equivalent to, or higher quality, than the requested resource.  Furthermore, the requested resource is
   *          guaranteed to be immediately available at its specific resolution without any on-demand scaling.
   *
   * @throws IllegalArgumentException if the upload file is from a different CDN data instance,
   *                                  if the upload file has already been stored,
   *                                  or if the upload file has an incorrect extension
   *
   * @see  Uploads#createUploadFile(com.aoapps.cdn.data.ContentType)
   */
  public Version findOrAdd(UploadFile uploadFile) throws IOException, IllegalArgumentException {
    try {
      if (uploadFile.getCdnData() != this) {
        throw new IllegalArgumentException("Uploaded file is for a different CDN: this = " + this + ", uploadFile.cdnData = " + uploadFile.getCdnData());
      }
      assert uploads != null : "When have upload file for this CDN data, is must be an uploader";
      Path tempFile = uploadFile.getAndRemoveTempFile();
      if (tempFile == null) {
        throw new IllegalArgumentException("UploadFile already stored (or possibly attempted to be stored and failed)");
      }
      // Verify extension
      String expectedExtension = uploadFile.getContentType().getExtension();
      String tempFileName = FileSystemUtils.getFileName(tempFile);
      if (!tempFileName.endsWith(EXTENSION_SEPARATOR + expectedExtension)) {
        throw new IllegalArgumentException("UploadFile has mismatched extension: expected " + expectedExtension
            + ", but filename is " + tempFileName);
      }
      // Verify is regular file
      if (!Files.isRegularFile(tempFile, LinkOption.NOFOLLOW_LINKS)) {
        throw new IllegalArgumentException("UploadFile is not a regular file: " + tempFile);
      }
      // Verify content type
      String guessedContent;
      try (InputStream in = new BufferedInputStream(Files.newInputStream(tempFile, LinkOption.NOFOLLOW_LINKS))) {
        guessedContent = URLConnection.guessContentTypeFromStream(in);
      }
      if (guessedContent != null) {
        String mimeType = uploadFile.getContentType().getMimeType();
        if (!guessedContent.equalsIgnoreCase(mimeType)) {
          throw new IllegalArgumentException("Guessed content type does not match declared content type: guessedContent = "
              + guessedContent + ", uploadFile.contentType.mimeType = " + mimeType);
        }
      } else {
        String probedType = Files.probeContentType(tempFile);
        if (probedType != null) {
          String mimeType = uploadFile.getContentType().getMimeType();
          if (!probedType.equalsIgnoreCase(mimeType)) {
            throw new IllegalArgumentException("Probed content type does not match declared content type: guessedContent = "
                + probedType + ", uploadFile.contentType.mimeType = " + mimeType);
          }
        }
      }
      // Iterate through all existing resources looking for a match
      long tempFileSize = Files.size(tempFile);
      ContentType uploadContentType = uploadFile.getContentType();
      for (Resource resource : resources) {
        ContentType originalContentType = resource.getOriginalContentType();
        // The version must match the original link type.
        // For example, do not match jpg of thumbnail converted from a mov
        if (originalContentType == uploadContentType) {
          Version version = resource.findVersion(tempFile, tempFileSize, uploadContentType);
          if (version != null) {
            Files.delete(tempFile);
            return version;
          }
        }
      }
      Resource newResource = resources.addNewResource(tempFile, tempFileSize, uploadFile.getContentType());
      assert Files.notExists(tempFile, LinkOption.NOFOLLOW_LINKS);
      return newResource.getOriginal();
    } catch (UncheckedIOException e) {
      throw e.getCause();
    }
  }
}
