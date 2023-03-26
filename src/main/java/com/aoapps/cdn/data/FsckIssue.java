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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.logging.Level;

/**
 * An issue associated with one path from {@link CdnData#fsck()}.
 */
public class FsckIssue {

  private final Level level;
  private final Throwable thrown;
  private final String msg;

  FsckIssue(Level level, Throwable thrown, String msg) {
    this.level = level;
    this.thrown = thrown;
    this.msg = msg;
  }

  FsckIssue(Level level, String msg) {
    this(level, null, msg);
  }

  @Override
  public String toString() {
    return msg;
  }

  public Level getLevel() {
    return level;
  }

  @SuppressFBWarnings("EI_EXPOSE_REP")
  public Throwable getThrown() {
    return thrown;
  }

  public String getMessage() {
    return msg;
  }
}
