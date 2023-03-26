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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.logging.Logger;
import org.junit.Test;

/**
 * Tests the {@link CdnData} class.
 */
@SuppressWarnings("deprecation")
public class CdnDataTest {

  private static final Logger logger = Logger.getLogger(CdnDataTest.class.getName());

  private static Path createTempDirectory() throws IOException {
    return Files.createTempDirectory(CdnDataTest.class.getSimpleName());
  }

  private static Version upload(CdnData cdnData, ContentType contentType, String filename) throws IOException {
    UploadFile uploadFile = cdnData.getUploads().createUploadFile(contentType);
    Path tempFile = uploadFile.getTempFile();
    InputStream in = CdnDataTest.class.getResourceAsStream(filename);
    if (in == null) {
      throw new IOException("Resource not found: " + filename);
    }
    try {
      Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      in.close();
    }
    return cdnData.findOrAdd(uploadFile);
  }

  private static Version upload(CdnData cdnData, ContentType contentType, Path path) throws IOException {
    UploadFile uploadFile = cdnData.getUploads().createUploadFile(contentType);
    Path tempFile = uploadFile.getTempFile();
    Files.copy(path, tempFile, StandardCopyOption.REPLACE_EXISTING);
    return cdnData.findOrAdd(uploadFile);
  }

  @Test
  @SuppressWarnings("ThrowableResultIgnored")
  public void testUploadJpeg() throws IOException {
    Path cdnRoot = createTempDirectory();
    try {
      CdnData cdnData = new CdnData(cdnRoot, null, true);
      Version originalVersion = upload(cdnData, ContentType.JPEG, "33057.jpg");
      assertSame(ContentType.JPEG, originalVersion.getContentType());
      assertEquals("778x584.jpg", originalVersion.getFilename());
      assertTrue(originalVersion instanceof ImageVersion);
      ImageVersion original = (ImageVersion) originalVersion;
      // Compare dimensions
      assertEquals(778, original.getWidth());
      assertEquals(584, original.getHeight());

      // Add again, should get the same ID
      Version reUpload = upload(cdnData, ContentType.JPEG, "33057.jpg");
      assertEquals(originalVersion, reUpload);

      // Trigger file type mismatch detection
      assertThrows(IllegalArgumentException.class, () -> upload(cdnData, ContentType.GIF, "33057.jpg"));

      // Scaling tests
      assertSame("Both null returns same object", original, original.scale(null, null));
      assertSame("Exact match returns same object", original, original.scale(778, 584));
      // Scale down by width
      ImageVersion widthScaled = original.scale(389, null);
      assertEquals(389, widthScaled.getWidth());
      assertEquals(292, widthScaled.getHeight());
      assertEquals("389x292.jpg", widthScaled.getFilename());
      // Scale down by height return same object
      assertSame(widthScaled, widthScaled.scale(null, 292));
      // Scale down from original by height
      ImageVersion heightScaled = original.scale(null, 292);
      assertEquals(widthScaled, heightScaled);
      assertEquals(389, heightScaled.getWidth());
      assertEquals(292, heightScaled.getHeight());
      assertEquals("389x292.jpg", heightScaled.getFilename());
      // Scale with letterboxing horizontal
      ImageVersion horizontalLetterbox1 = heightScaled.scale(1000, 292);
      assertSame(heightScaled, horizontalLetterbox1);
      assertEquals(389, horizontalLetterbox1.getWidth());
      assertEquals(292, horizontalLetterbox1.getHeight());
      ImageVersion horizontalLetterbox2 = original.scale(1000, 584);
      assertSame(original, horizontalLetterbox2);
      assertEquals(778, horizontalLetterbox2.getWidth());
      assertEquals(584, horizontalLetterbox2.getHeight());
      ImageVersion horizontalLetterbox3 = heightScaled.scale(1000, 584);
      assertNotSame(original, horizontalLetterbox3);
      assertEquals(original, horizontalLetterbox3);
      assertEquals(778, horizontalLetterbox3.getWidth());
      assertEquals(584, horizontalLetterbox3.getHeight());
      // Scale with letterboxing vertical
      ImageVersion verticalLetterbox1 = heightScaled.scale(389, 1000);
      assertSame(heightScaled, verticalLetterbox1);
      assertEquals(389, verticalLetterbox1.getWidth());
      assertEquals(292, verticalLetterbox1.getHeight());
      ImageVersion verticalLetterbox2 = original.scale(778, 1000);
      assertSame(original, verticalLetterbox2);
      assertEquals(778, verticalLetterbox2.getWidth());
      assertEquals(584, verticalLetterbox2.getHeight());
      ImageVersion verticalLetterbox3 = heightScaled.scale(778, 1000);
      assertNotSame(original, verticalLetterbox3);
      assertEquals(original, verticalLetterbox3);
      assertEquals(778, verticalLetterbox3.getWidth());
      assertEquals(584, verticalLetterbox3.getHeight());
      // Scale down disproportionate smaller
      ImageVersion disproportionateVerticalLetterbox = heightScaled.scale(100, 1000);
      assertEquals(100, disproportionateVerticalLetterbox.getWidth());
      assertEquals(75, disproportionateVerticalLetterbox.getHeight());
      ImageVersion disproportionateHorizontalLetterbox = heightScaled.scale(1000, 100);
      assertEquals(133, disproportionateHorizontalLetterbox.getWidth());
      assertEquals(100, disproportionateHorizontalLetterbox.getHeight());

      // Re-upload scaled version file, should get existing version
      Version uploadedScaled = upload(cdnData, ContentType.JPEG, widthScaled.versionFile);
      assertEquals(widthScaled, uploadedScaled);
      assertEquals(widthScaled.getResource(), uploadedScaled.getResource());
      assertEquals("389x292.jpg", uploadedScaled.getFilename());
      assertTrue(uploadedScaled instanceof ImageVersion);

      // Request scale up, but get the original image
      assertSame("Scale bigger than original returns original", original, original.scale(1000, 1000));
      Version widthScaleTooBig = widthScaled.scale(1000, 1000);
      assertNotSame("Scale bigger than original returns original", original, widthScaleTooBig);
      assertEquals("Scale bigger than original returns original", original, widthScaleTooBig);
      Version heightScaleTooBig = heightScaled.scale(1000, 1000);
      assertNotSame("Scale bigger than original returns original", original, heightScaleTooBig);
      assertEquals("Scale bigger than original returns original", original, heightScaleTooBig);
    } finally {
      try {
        Files.walk(cdnRoot)
            .sorted(Comparator.reverseOrder())
            .forEach(path -> {
              try {
                logger.info("Deleting " + path);
                Files.delete(path);
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            });
      } catch (UncheckedIOException e) {
        throw e.getCause();
      }
    }
  }
}
