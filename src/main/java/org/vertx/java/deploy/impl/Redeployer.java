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
import org.vertx.java.core.impl.VertxInternal;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.deploy.impl.VerticleManager;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
public class Redeployer implements Handler<Long> {

  private final VertxInternal vertx;
  private final VerticleManager mgr;
  private final long redeployCheckPeriod;

  public Redeployer(VertxInternal vertx, VerticleManager mgr,
                    long redeployCheckPeriod) {
    this.vertx = vertx;
    this.mgr = mgr;
    this.redeployCheckPeriod = redeployCheckPeriod;
  }

  private void check() {
    Map<String, Deployment> deployments = mgr.listDeployments();
    Map<File, List<String>> modDirs = new HashMap<>();
    for (Deployment dep: deployments.values()) {
      if (dep.modDir != null) {
        List<String> deployIDs = modDirs.get(dep.modDir);
        if (deployIDs == null) {
          deployIDs = new ArrayList<>();
          modDirs.put(dep.modDir, deployIDs);
        }
        deployIDs.add(dep.name);
      }
    }
    if (!modDirs.isEmpty()) {
      CountingCompletionHandler compHandler = new CountingCompletionHandler(vertx);
      for (Map.Entry<File, List<String>> entry: modDirs.entrySet()) {
        File deployMarker = new File(entry.getKey(), ".redeployme");
        if (deployMarker.exists()) {
          deployMarker.delete();
          redeploy(entry.getValue(), deployments, compHandler);
        }
      }
      compHandler.setHandler(new SimpleHandler() {
        public void handle() {
          resetTimer();
        }
      });
    } else {
      resetTimer();
    }
  }

  private void redeploy(List<String> deploymentIDs, Map<String, Deployment> deployments,
                        final CountingCompletionHandler compHandler) {
    for (String deploymentID: deploymentIDs) {
      final Deployment deployment = deployments.get(deploymentID);
      compHandler.incRequired();
      mgr.undeploy(deploymentID, new SimpleHandler() {
        public void handle() {
          // We only redeploy top level deployments since child ones will get
          // automatically redeployed when the parent is.
          if (deployment.parentDeploymentName == null) {
            // It's a module
            if (deployment.modDir != null) {
              mgr.deployMod(deployment.modName, deployment.config,
                            deployment.instances, null, new Handler<String>() {
                public void handle(String res) {
                  compHandler.complete();
                }
              });
            }
          }
        }
      });
    }
  }

  @Override
  public void handle(Long event) {
    check();
  }

  private void resetTimer() {
    vertx.setTimer(redeployCheckPeriod, this);
  }

}
