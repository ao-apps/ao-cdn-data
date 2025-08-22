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

import com.aoapps.lang.ProcessResult;
import com.aoapps.lang.Strings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Synchronization tasks are delegated here.
 *
 * <p>Please note that the following are excluded from synchronization:</p>
 *
 * <ul>
 * <li>{@link Uploads#UPLOADS_DIR_NAME}</li>
 * <li>{@link CdnData#NEW_EXTENSION}</li>
 * <li>{@link DirectoryLock#LOCK_FILE}</li>
 * </ul>
 */
class Csync2 {

  private static final Logger logger = Logger.getLogger(Csync2.class.getName());

  /**
   * The lock objects to be used for each unique Csync2 group.
   */
  private static final Map<String, Object> groupLocks = new HashMap<>();

  /**
   * Gets the lock object to be used for the given Csync2 group.
   */
  private static Object getGroupLock(String group) {
    synchronized (groupLocks) {
      return groupLocks.computeIfAbsent(
          Objects.requireNonNull(group),
          key -> new Object() {
            @Override
            public String toString() {
              return "Csync2 lock for " + group;
            }
          }
      );
    }
  }

  private final String group;

  /**
   * Creates a new synchronizer.
   *
   * @param group  The Csync2 group to be synchronized after each change.  When {@code null} empty
   *               (after trimming), no synchronization is performed.
   */
  Csync2(String group) {
    this.group = Strings.trimNullIfEmpty(group);
  }

  /**
   * Immediately synchronizes the cluster for the given paths.
   *
   * <p>Accesses are serialized (only on instance of Csync2 launched at a time) per distinct Csync2 group.</p>
   *
   * <p>TODO: Coalesce any concurrent requests instead of calling csync2 multiple times?
   *       Note: In the case of symbolic link removal and re-add, the intermediate synchronization is important, since
   *       csync2 does not currently detect symlink target changes.  This should probably be OK since the removal and
   *       re-add would be done by the same thread, and thus not coalesced under concurrency.</p>
   *
   * <p>Any errors are logged.  It is expected that cluster state monitoring will catch clustering issues.</p>
   *
   * <p>TODO: Time-out on csync2 process call, using ao-concurrent executors.  Have a clean shutdown that also shuts down executors.</p>
   *
   * @param paths  The set of files or directories to synchronize.
   *               Each does not need to exist when synchronizing a deletion.
   *               When {@code null} or empty, nothing is done.
   */
  void synchronize(Path... paths) {
    if (paths != null && paths.length != 0) {
      logger.log(Level.FINE, () ->
          ((group == null) ? "No cluster to synchronize for " : "Synchronizing cluster for ") + Arrays.asList(paths).toString());
      if (group != null) {
        synchronized (getGroupLock(group)) {
          // Note: A simple "csync2 -x -r [path]" seems to be marking as dirty but not actually performing the "update"
          //       step.  Also, "csync2 -u -r [path]" seems to be not sending the paths, which is likely the same issue.
          //       We're splitting into three steps to try to work around this issue:
          //       1) "-h -r paths": Add hints to specific paths (to avoid scanning all directories for every update)
          //       2) "-c": Check all hinted paths to mark as dirty
          //       3) "-u": Perform the update of paths marked as dirty

          // Hint
          int hintCommandLen = 5 + paths.length;
          String[] hintCommand = new String[hintCommandLen];
          int i = 0;
          hintCommand[i++] = "csync2";
          hintCommand[i++] = "-G";
          hintCommand[i++] = group;
          hintCommand[i++] = "-h";
          hintCommand[i++] = "-r";
          for (Path path : paths) {
            hintCommand[i++] = path.toString();
          }
          assert i == hintCommandLen;
          boolean hintSuccess;
          String joinedHintCommand = Strings.join(hintCommand, " "); // Only used for logging
          try {
            ProcessResult result = ProcessResult.exec(hintCommand, StandardCharsets.UTF_8);
            String stdout = result.getStdout();
            if (!stdout.isEmpty()) {
              logger.log(Level.INFO, () -> "\"" + joinedHintCommand + "\": standard output: " + stdout);
            }
            String stderr = result.getStderr();
            int exitVal = result.getExitVal();
            if (exitVal == 0) {
              hintSuccess = true;
              if (!stderr.isEmpty()) {
                logger.log(Level.WARNING, () -> "\"" + joinedHintCommand + "\": standard error: " + stderr);
              }
            } else {
              hintSuccess = false;
              if (stderr.isEmpty()) {
                logger.log(Level.SEVERE, () -> "\"" + joinedHintCommand + "\": hint failed: exitVal = " + result.getExitVal());
              } else {
                logger.log(Level.SEVERE, () -> "\"" + joinedHintCommand + "\": hint failed: exitVal = " + result.getExitVal() + ", standard error: " + stderr);
              }
            }
          } catch (IOException e) {
            logger.log(Level.SEVERE, e, () -> "\"" + joinedHintCommand + "\": hint failed");
            hintSuccess = false;
          }

          if (hintSuccess) {
            // Check
            String[] checkCommand = {"csync2", "-G", group, "-c"};
            boolean checkSuccess;
            String joinedCheckCommand = Strings.join(checkCommand, " "); // Only used for logging
            try {
              ProcessResult result = ProcessResult.exec(checkCommand, StandardCharsets.UTF_8);
              String stdout = result.getStdout();
              if (!stdout.isEmpty()) {
                logger.log(Level.INFO, () -> "\"" + joinedCheckCommand + "\": standard output: " + stdout);
              }
              String stderr = result.getStderr();
              int exitVal = result.getExitVal();
              if (exitVal == 0) {
                checkSuccess = true;
                if (!stderr.isEmpty()) {
                  logger.log(Level.WARNING, () -> "\"" + joinedCheckCommand + "\": standard error: " + stderr);
                }
              } else {
                checkSuccess = false;
                if (stderr.isEmpty()) {
                  logger.log(Level.SEVERE, () -> "\"" + joinedCheckCommand + "\": check failed: exitVal = " + result.getExitVal());
                } else {
                  logger.log(Level.SEVERE, () -> "\"" + joinedCheckCommand + "\": check failed: exitVal = " + result.getExitVal() + ", standard error: " + stderr);
                }
              }
            } catch (IOException e) {
              logger.log(Level.SEVERE, e, () -> "\"" + joinedCheckCommand + "\": check failed");
              checkSuccess = false;
            }

            if (checkSuccess) {
              // Update
              String[] updateCommand = {"csync2", "-G", group, "-u"};
              String joinedUpdatedCommand = Strings.join(updateCommand, " "); // Only used for logging
              try {
                ProcessResult result = ProcessResult.exec(updateCommand, StandardCharsets.UTF_8);
                String stdout = result.getStdout();
                if (!stdout.isEmpty()) {
                  logger.log(Level.INFO, () -> "\"" + joinedUpdatedCommand + "\": standard output: " + stdout);
                }
                String stderr = result.getStderr();
                int exitVal = result.getExitVal();
                if (exitVal == 0) {
                  if (!stderr.isEmpty()) {
                    logger.log(Level.WARNING, () -> "\"" + joinedUpdatedCommand + "\": standard error: " + stderr);
                  }
                } else {
                  if (stderr.isEmpty()) {
                    logger.log(Level.SEVERE, () -> "\"" + joinedUpdatedCommand + "\": update failed: exitVal = " + result.getExitVal());
                  } else {
                    logger.log(Level.SEVERE, () -> "\"" + joinedUpdatedCommand + "\": update failed: exitVal = " + result.getExitVal() + ", standard error: " + stderr);
                  }
                }
              } catch (IOException e) {
                logger.log(Level.SEVERE, e, () -> "\"" + joinedUpdatedCommand + "\": update failed");
              }
            }
          }
        }
      }
    }
  }
}
