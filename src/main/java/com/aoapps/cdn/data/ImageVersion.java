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

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * A version of a resource that contains an {@link Image}.
 */
public class ImageVersion extends DimensionVersion {

  /**
   * Checks that a type is supported.
   *
   * @return  the type when supported
   *
   * @throws  IllegalArgumentException when not supported
   */
  private static ContentType checkType(ContentType contentType) throws IllegalArgumentException {
    switch (contentType) {
      case JPEG:
      case PNG:
      case GIF:
        return contentType;
      default:
        throw new IllegalArgumentException("Unsupported content type: " + contentType);
    }
  }

  /**
   * Checks that Java image format name.
   *
   * @return  the Java image type when supported
   *
   * @throws  IllegalArgumentException when not supported
   *
   * @see ImageVersion#writeInto(java.awt.image.BufferedImage, java.nio.file.Path)
   * @see ImageIO#write(java.awt.image.RenderedImage, java.lang.String, java.io.OutputStream)
   * @see ImageIO#getWriterFormatNames()
   */
  private static String getJavaImageType(ContentType contentType) throws IllegalArgumentException {
    switch (contentType) {
      case JPEG:
        return "JPG";
      case PNG:
        return "PNG";
      case GIF:
        return "GIF";
      default:
        throw new IllegalArgumentException("Unsupported content type: " + contentType);
    }
  }

  /**
   * Reads an image from the given path.
   */
  private static BufferedImage readImage(ContentType contentType, Path versionFile) throws IOException {
    checkType(contentType);
    try (InputStream in = new BufferedInputStream(Files.newInputStream(versionFile, LinkOption.NOFOLLOW_LINKS))) {
      BufferedImage image = ImageIO.read(in);
      if (image == null) {
        throw new IOException("Unable to read image: " + versionFile);
      }
      return image;
    }
  }

  /**
   * Reads the dimensions of an image from the given path.
   */
  private static Dimension readDimension(ContentType contentType, Path versionFile) throws IOException {
    BufferedImage image = readImage(contentType, versionFile);
    return new Dimension(image.getWidth(), image.getHeight());
  }

  /**
   * Create an image version by reading the underlying file to determine the meta data.
   */
  static ImageVersion createImageVersionByReadFile(Resource resource, ContentType contentType, Path versionFile) throws IOException {
    checkType(contentType);
    return new ImageVersion(resource, contentType, versionFile, readDimension(contentType, versionFile));
  }

  /**
   * Create an image version by parsing the meta data from the filename.
   *
   * @throws IllegalArgumentException when unable to parse
   */
  static ImageVersion createImageVersionByParseFilename(Resource resource, ContentType contentType, Path versionFile) throws IllegalArgumentException {
    checkType(contentType);
    String filename = FileSystemUtils.getFileName(versionFile);
    // Filename must be (width)x(height).(ext)
    String expectedExtension = contentType.getExtension();
    if (!filename.endsWith(CdnData.EXTENSION_SEPARATOR + expectedExtension)) {
      throw new IllegalArgumentException("Version file has mismatched extension: expected " + expectedExtension
          + ", but path is " + versionFile);
    }
    String prefix = filename.substring(0, filename.length() - 1 - expectedExtension.length());
    return new ImageVersion(resource, contentType, versionFile, parseDimensionFromFilenamePrefix(versionFile, prefix));
  }

  ImageVersion(Resource resource, ContentType contentType, Path versionFile, Dimension dimension) {
    super(resource, checkType(contentType), versionFile, dimension);
  }

  @Override
  public ImageVersion scale(Integer width, Integer height) throws IOException {
    return (ImageVersion) super.scale(width, height);
  }

  /**
   * See <a href="https://www.baeldung.com/java-resize-image">https://www.baeldung.com/java-resize-image</a>.
   */
  @Override
  void scaleInto(int newWidth, int newHeight, Path tempFile) throws IOException {
    if (newWidth > width || newHeight > height) {
      throw new IllegalArgumentException("Refusing to scale up");
    }
    BufferedImage before = readImage(contentType, versionFile);
    BufferedImage scaledImage = new BufferedImage(newWidth, newHeight, before.getType());
    Graphics2D graphics2D = scaledImage.createGraphics();
    graphics2D.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    graphics2D.drawImage(before, 0, 0, newWidth, newHeight, null);
    graphics2D.dispose();
    writeInto(scaledImage, tempFile);
  }

  /**
   * See <a href="https://docs.oracle.com/javase/tutorial/2d/images/saveimage.html">https://docs.oracle.com/javase/tutorial/2d/images/saveimage.html</a>.
   *
   * @param  tempFile  Will be created if doesn't exist.  Will be overwritten if does exist.
   */
  void writeInto(BufferedImage scaledImage, Path tempFile) throws IOException {
    try (FileChannel channel = FileChannel.open(tempFile,
        Set.of(LinkOption.NOFOLLOW_LINKS, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING),
        FileSystemUtils.usePosixAttributesWhenSupported(tempFile.getFileSystem(), FileSystemUtils.NEW_FILE_PERMISSIONS)
    )) {
      try (OutputStream out = new BufferedOutputStream(Channels.newOutputStream(channel))) {
        String javaImageType = getJavaImageType(contentType);
        if (!ImageIO.write(scaledImage, javaImageType, out)) {
          throw new AssertionError("No image writer found, despite being a documented standard type: "
              + getJavaImageType(contentType));
        }
        out.flush();
        channel.force(true);
      }
    }
  }
}
