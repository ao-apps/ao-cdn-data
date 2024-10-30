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

import com.aoapps.lang.math.SafeMath;
import java.awt.Dimension;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A version of a resource that has a dimension.
 */
public abstract class DimensionVersion extends Version {

  private static final Logger logger = Logger.getLogger(DimensionVersion.class.getName());

  /**
   * The character used to separate dimensions.
   */
  static final char DIMENSION_SEPARATOR = 'x';

  /**
   * Parses a dimension from the filename prefix.
   *
   * @throws  IllegalArgumentException when not able to parse
   */
  static Dimension parseDimensionFromFilenamePrefix(Path versionFile, String prefix) throws IllegalArgumentException {
    int sepPos = prefix.indexOf(DIMENSION_SEPARATOR);
    if (sepPos == -1) {
      throw new IllegalArgumentException("Unable to find dimension separator '" + DIMENSION_SEPARATOR
          + "' in filename prefix \"" + prefix + "\" for path " + versionFile);
    }
    String widthStr = prefix.substring(0, sepPos);
    int width = Integer.parseInt(widthStr);
    if (!Integer.toString(width).equals(widthStr)) {
      throw new IllegalArgumentException("Non-canonical width \"" + widthStr + "\" in filename prefix \"" + prefix
           + "\" for path " + versionFile);
    }
    if (width < 1) {
      throw new IllegalArgumentException("Invalid width \"" + width + "\" in filename prefix \"" + prefix
           + "\" for path " + versionFile);
    }

    String heightStr = prefix.substring(sepPos + 1);
    int height = Integer.parseInt(heightStr);
    if (!Integer.toString(height).equals(heightStr)) {
      throw new IllegalArgumentException("Non-canonical height \"" + heightStr + "\" in filename prefix \"" + prefix
           + "\" for path " + versionFile);
    }
    if (height < 1) {
      throw new IllegalArgumentException("Invalid height \"" + height + "\" in filename prefix \"" + prefix
           + "\" for path " + versionFile);
    }

    return new Dimension(width, height);
  }

  final int width;
  final int height;

  DimensionVersion(Resource resource, ContentType contentType, Path versionFile, Dimension dimension) {
    super(resource, contentType, versionFile);
    this.width = dimension.width;
    this.height = dimension.height;
  }

  /**
   * Gets the dimension in pixels.
   */
  public Dimension getDimension() {
    return new Dimension(getWidth(), getHeight());
  }

  /**
   * Gets the width in pixels.
   */
  public int getWidth() {
    return width;
  }

  /**
   * Gets the height in pixels.
   */
  public int getHeight() {
    return height;
  }

  /**
   * {@inheritDoc}.
   *
   * <p>Defaults to <code>(width)x(height).(extension)</code>.</p>
   */
  @Override
  String getFilename() {
    return getFilename(width, height);
  }

  String getFilename(int width, int height) {
    return Integer.toString(width) + DIMENSION_SEPARATOR + Integer.toString(height) + CdnData.EXTENSION_SEPARATOR
        + contentType.getExtension();
  }

  /**
   * Scales this version to a different resolution, using cached version if previously scaled.
   *
   * <p>This will typically be done on {@linkplain Resource#getOriginal() the original version}, but can be performed on
   * any version.</p>
   *
   * <p>First searches all versions for any of this type and matching dimension.  If found, returns it.
   * Tracks which version had the highest resolution while performing this search.</p>
   *
   * <p>Will only scale down from the biggest existing resource.  A request to scale up will return the highest resolution
   * available.</p>
   *
   * <p>Will only perform proportional scaling.  If both {@code width} and {@code height} are specified, returns the
   * largest, possibly scaled, image that fits both the width and the height.  The resulting width or height may be less
   * than the requested scaling, but will not be more.  The user interface could perform letterboxing if needing to fit
   * into a fix proportion area.</p>
   *
   * <p>{@linkplain Resource#lock(boolean) Locks the resource} while performing the search.  This search should be very quick,
   * and this locking will prevent duplicate work when performing a new scaling.</p>
   *
   * <p>{@linkplain Csync2#synchronize(java.nio.file.Path...) Synchronizes the cluster}, but only after releasing the lock.
   * If different nodes create the same version at the same time, the cluster confict will be resolved through
   * standard monitoring and administration.</p>
   *
   * @param  width  The desired width or {@code null} for proportional scaling
   * @param  height  The desired height or {@code null} for proportional scaling
   *
   * @return  When both {@code width} and {@code height} are {@code null}, return {@code this}.
   *          When resulting dimensions match this version, return {@code this}.
   *          Otherwise return a closest-match scaled version.
   */
  // TODO: Only allow scaling to a predefined hard-coded set of resolutions matching edit-PictureReview.php
  //       or at least enforce/filter this in Cdn and Webapp interaction with the resources.
  public DimensionVersion scale(Integer width, Integer height) throws IOException {
    final Integer requestedWidth = width;
    final Integer requestedHeight = height;
    boolean autoWidth = false;
    boolean autoHeight = false;
    if (width == null) {
      if (height == null) {
        return this;
      }
      // Proportional width
      width = SafeMath.castInt(Math.round((double) this.width * (double) height / (double) this.height));
      autoWidth = true;
    } else if (height == null) {
      // Proportional height
      height = SafeMath.castInt(Math.round((double) this.height * (double) width / (double) this.width));
      autoHeight = true;
    }
    assert width != null;
    assert height != null;
    if (width == this.width && height == this.height) {
      return this;
    }
    Path syncPath;
    DimensionVersion newVersion;
    try (DirectoryLock lock = resource.lock(false)) {
      DimensionVersion biggest = null;
      for (Version v : resource.getVersions(contentType)) {
        assert v instanceof DimensionVersion : "Filtered by matching content type";
        DimensionVersion version = (DimensionVersion) v;
        // If found exact match, return
        if (version.width == width && version.height == height) {
          assert !version.equals(this);
          return version;
        }
        if (version.equals(this)) {
          version = this;
        }
        // Keep track of the biggest
        if (biggest == null || (version.width > biggest.width || version.height > biggest.height)) {
          biggest = version;
        }
        // Check for match in one dimension (but not over in the other) for disproportionate matching
        if (
            // Letterbox vertical
            (version.width == width && version.height <= height)
            // Letterbox horizontal
            || (version.height == height && version.width <= width)
        ) {
          return version;
        }
      }
      assert biggest != null : "Must have at least matched self since is same type";
      // Constrain bounds by the biggest available
      if (width > biggest.width) {
        width = biggest.width;
        if (autoHeight) {
          height = biggest.height;
        }
      }
      if (height > biggest.height) {
        height = biggest.height;
        if (autoWidth) {
          width = biggest.width;
        }
      }
      if (width == biggest.width && height == biggest.height) {
        return biggest;
      }

      // Compute and compare porportional scalings, keeping the smaller
      int verticalLetterboxWidth = SafeMath.castInt(Math.round((double) biggest.width * (double) height / (double) biggest.height));
      int horizontalLetterboxHeight = SafeMath.castInt(Math.round((double) biggest.height * (double) width / (double) biggest.width));
      if (verticalLetterboxWidth < width && height < horizontalLetterboxHeight) {
        width = verticalLetterboxWidth;
      } else {
        height = horizontalLetterboxHeight;
      }
      assert width < biggest.width || height < biggest.height;
      assert requestedWidth == null || width <= requestedWidth;
      assert requestedHeight == null || height <= requestedHeight;

      // Create new version file
      String newVersionFilename = getFilename(width, height);
      Path newVersionFile = resource.resourceDir.resolve(newVersionFilename);
      String tempFilename = newVersionFilename + CdnData.EXTENSION_SEPARATOR + CdnData.NEW_EXTENSION;
      Path tempFile = resource.resourceDir.resolve(tempFilename);
      try {
        // Call subclass to scale to version file at the requested width and height
        biggest.scaleInto(width, height, tempFile);
        // Move/copy the new file into place
        try {
          Files.move(tempFile, newVersionFile, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
          logger.log(Level.FINE, e, () -> "Reverting to copy/delete from " + tempFile + " to " + newVersionFile);
          Files.copy(tempFile, newVersionFile, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
          Files.delete(tempFile);
        }
      } finally {
        Files.deleteIfExists(tempFile);
      }
      syncPath = newVersionFile;
      newVersion = (DimensionVersion) contentType.createVersionByParseFilename(resource, newVersionFile);
    }
    // Synchronize cluster
    resource.cdnData.csync2.synchronize(syncPath);
    return newVersion;
  }

  /**
   * Performs scaling of this resource to the new size into the given path.
   *
   * <p>When writing the file, it is suggested to {@link OutputStream#flush()} then {@link FileChannel#force(boolean)}.</p>
   */
  abstract void scaleInto(int newWidth, int newHeight, Path tempFile) throws IOException;
}
