/*
 * Copyright 2011-2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.vertx.java.deploy.impl;

import org.vertx.java.core.Handler;
import org.vertx.java.core.SimpleHandler;
import org.vertx.java.core.Vertx;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.core.logging.Logger;
import org.vertx.java.core.logging.impl.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
public class Redeployer2 {

  private static final Logger log = LoggerFactory.getLogger(Redeployer2.class);

  private final VerticleManager verticleManager;
  private final Map<Path, List<Deployment>> watchedDeployments = new HashMap<>();
  private final Map<WatchKey, Path> watchKeys = new HashMap<>();
  private final Map<Path, Path> moduleDirs = new HashMap<>();
  private final WatchService watchService;
  private final Vertx vertx;

  public Redeployer2(Vertx vertx, VerticleManager verticleManager) {
    this.verticleManager = verticleManager;
    try {
      watchService = FileSystems.getDefault().newWatchService();
    } catch (IOException e) {
      log.error("Failed to create redeployer", e);
      throw new IllegalArgumentException(e.getMessage());
    }

    this.vertx = vertx;
    vertx.setPeriodic(200, new Handler<Long>() {
      public void handle(Long id) {
        checkEvents();
      }
    });
  }

  public synchronized void moduleDeployed(File fmodDir, Deployment deployment) {
    log.info("Module deployed " + fmodDir);
    Path modDir = fmodDir.toPath();
    List<Deployment> deps = watchedDeployments.get(modDir);
    if (deps == null) {
      deps = new ArrayList<>();
      watchedDeployments.put(modDir, deps);
      try {
        registerAll(modDir);
      } catch (IOException e) {
        log.error("Failed to register", e);
        throw new IllegalStateException(e.getMessage());
      }
    }
    deps.add(deployment);
  }

  public synchronized void moduleUndeployed(Path modDir, Deployment deployment) {

  }

  void checkEvents() {
    Set<Path> toReload = new HashSet<>();
    while (true) {
      WatchKey key = watchService.poll();
      if (key == null) {
        break;
      }
      handleEvent(key, toReload);
    }
    for (Path modulePath: toReload) {
      reload(modulePath);
    }
  }

  private void reload(Path modulePath) {
    List<Deployment> deployments = watchedDeployments.get(modulePath);
    if (deployments == null) {
      throw new IllegalStateException("Cannot find any deployments for path: " + modulePath);
    }
    for (final Deployment deployment: deployments) {
      verticleManager.undeploy(deployment.name, new SimpleHandler() {
        public void handle() {
          verticleManager.deployMod(deployment.modName, deployment.config, deployment.instances,
                                    null, null);
        }
      });
    }
  }

  private void handleEvent(WatchKey key, Set<Path> toReload) {
    Path dir = watchKeys.get(key);
    if (dir == null) {
      throw new IllegalStateException("Unrecognised watch key " + dir);
    }

    for (WatchEvent<?> event : key.pollEvents()) {
      WatchEvent.Kind<?> kind = event.kind();

      if (kind == StandardWatchEventKinds.OVERFLOW) {
        log.warn("Overflow event on watched directory");
        continue;
      }

      log.info("got event in directory " + dir);

      Path moduleDir = moduleDirs.get(dir);
      log.info("module dir is " + moduleDir);

      @SuppressWarnings("unchecked")
      WatchEvent<Path> ev = (WatchEvent<Path>) event;
      Path name = ev.context();

      Path child = dir.resolve(name);

//      Path parent = moduleDirs.get(child);
//      if (parent == null) {
//        throw new IllegalStateException("Cannot find parent for " + child);
//      }

      if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
        log.info("entry modified: " + child);
      } else if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
        log.info("entry created: " + child);
        if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
          try {
            registerAll(child);
          } catch (IOException e) {
            log.error("Failed to register child", e);
            throw new IllegalStateException(e.getMessage());
          }
        }
      } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
        log.info("entry deleted: " + child);
        moduleDirs.remove(child);
      }
      //toReload.add(parent);
    }

    boolean valid = key.reset();
    if (!valid) {
      watchKeys.remove(key);
    }
  }

  private void register(Path modDir, Path dir) throws IOException {
    log.info("registering " + dir);
    WatchKey key = dir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE,
        StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
    watchKeys.put(key, dir);
    moduleDirs.put(dir, modDir);
  }

  private void registerAll(final Path modDir) throws IOException {
    log.info("registering all " + modDir);
    Files.walkFileTree(modDir, new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
        register(modDir, dir);
        return FileVisitResult.CONTINUE;
      }
    });
  }
}
