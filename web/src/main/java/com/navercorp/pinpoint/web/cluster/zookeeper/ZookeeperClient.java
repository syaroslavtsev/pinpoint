/*
 * Copyright 2014 NAVER Corp.
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

package com.navercorp.pinpoint.web.cluster.zookeeper;


import com.navercorp.pinpoint.rpc.util.TimerFactory;
import com.navercorp.pinpoint.web.cluster.zookeeper.exception.*;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.jboss.netty.util.HashedWheelTimer;
import org.jboss.netty.util.Timeout;
import org.jboss.netty.util.TimerTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author koo.taejin
 */
public class ZookeeperClient {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private final AtomicBoolean clientState = new AtomicBoolean(true);

    private final HashedWheelTimer timer;

    private final String hostPort;
    private final int sessionTimeout;
    private final ZookeeperClusterDataManager zookeeperDataManager;
    private final long reconnectDelayWhenSessionExpired;

    // ZK client is thread-safe
    private volatile ZooKeeper zookeeper;

    // hmm this structure should contain all necessary information
    public ZookeeperClient(String hostPort, int sessionTimeout, ZookeeperClusterDataManager manager) throws KeeperException, IOException, InterruptedException {
        this(hostPort, sessionTimeout, manager, ZookeeperClusterDataManager.DEFAULT_RECONNECT_DELAY_WHEN_SESSION_EXPIRED);
    }
    
    public ZookeeperClient(String hostPort, int sessionTimeout, ZookeeperClusterDataManager zookeeperDataManager, long reconnectDelayWhenSessionExpired) throws KeeperException, IOException, InterruptedException {
        this.hostPort = hostPort;
        this.sessionTimeout = sessionTimeout;
        this.zookeeperDataManager = zookeeperDataManager;
        this.reconnectDelayWhenSessionExpired = reconnectDelayWhenSessionExpired;
        
        this.zookeeper = new ZooKeeper(hostPort, sessionTimeout, zookeeperDataManager); // server
        
        this.timer = TimerFactory.createHashedWheelTimer(this.getClass().getSimpleName(), 100, TimeUnit.MILLISECONDS, 512);
    }


    public void reconnectWhenSessionExpired() {
        if (!clientState.get()) {
            logger.warn("ZookeeperClient.reconnectWhenSessionExpired() failed. Error: Already closed.");
            return;
        }
        
        ZooKeeper zookeeper = this.zookeeper;
        if (zookeeper.getState().isConnected()) {
            logger.warn("ZookeeperClient.reconnectWhenSessionExpired() failed. Error: session(0x{}) is connected.", Long.toHexString(zookeeper.getSessionId()));
            return;
        }

        logger.warn("Execute ZookeeperClient.reconnectWhenSessionExpired()(Expired session:0x{}).", Long.toHexString(zookeeper.getSessionId()));

        try {
            zookeeper.close();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        ZooKeeper newZookeeper = createNewZookeeper();
        if (newZookeeper == null) {
            logger.warn("Failed to create new Zookeeper instance. It will be retry  AFTER {}ms.", reconnectDelayWhenSessionExpired);

            timer.newTimeout(new TimerTask() {
                @Override
                public void run(Timeout timeout) throws Exception {
                    if (timeout.isCancelled()) {
                        return;
                    }
                    
                    reconnectWhenSessionExpired();
                }
            }, reconnectDelayWhenSessionExpired, TimeUnit.MILLISECONDS);
        } else {
            this.zookeeper = newZookeeper;
        }

    }

    private ZooKeeper createNewZookeeper() {
        try {
            return new ZooKeeper(hostPort, sessionTimeout, zookeeperDataManager);
        } catch (IOException ignore) {
            // ignore
        }
        return null;
    }
    
    /**
     * do not create node in path suffix
     *
     * @throws PinpointZookeeperException
     * @throws InterruptedException
     */
    public void createPath(String path) throws PinpointZookeeperException, InterruptedException {
        checkState();

        ZooKeeper zookeeper = this.zookeeper;
        int pos = 1;
        do {
            pos = path.indexOf('/', pos + 1);

            if (pos != -1) {
                try {
                    String subPath = path.substring(0, pos);
                    if (zookeeper.exists(subPath, false) != null) {
                        continue;
                    }

                    zookeeper.create(subPath, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                } catch (KeeperException exception) {
                    if (exception.code() != Code.NODEEXISTS) {
                        handleException(exception);
                    }
                }
            } else {
                pos = path.length();
            }
        } while (pos < path.length());
    }

    // we need deep node inspection for verification purpose (node content)
    public String createNode(String znodePath, byte[] data, CreateMode createMode) throws PinpointZookeeperException, InterruptedException {
        checkState();
        
        ZooKeeper zookeeper = this.zookeeper;
        try {
            if (zookeeper.exists(znodePath, false) != null) {
                return znodePath;
            }

            String pathName = zookeeper.create(znodePath, data, Ids.OPEN_ACL_UNSAFE, createMode);
            return pathName;
        } catch (KeeperException exception) {
            if (exception.code() != Code.NODEEXISTS) {
                handleException(exception);
            }
        }
        return znodePath;
    }

    public List<String> getChildren(String path, boolean watch) throws PinpointZookeeperException, InterruptedException {
        checkState();

        ZooKeeper zookeeper = this.zookeeper;
        try {
            return zookeeper.getChildren(path, watch);
        } catch (KeeperException exception) {
            if (exception.code() != Code.NONODE) {
                handleException(exception);
            }
        }

        return Collections.emptyList();
    }

    public byte[] getData(String path) throws PinpointZookeeperException, InterruptedException {
        return getData(path, false);
    }

    public byte[] getData(String path, boolean watch) throws PinpointZookeeperException, InterruptedException {
        checkState();

        ZooKeeper zookeeper = this.zookeeper;
        try {
            return zookeeper.getData(path, watch, null);
        } catch (KeeperException exception) {
            handleException(exception);
        }

        throw new UnknownException("UnknownException.");
    }


    public void delete(String path) throws PinpointZookeeperException, InterruptedException {
        checkState();

        ZooKeeper zookeeper = this.zookeeper;
        try {
            zookeeper.delete(path, -1);
        } catch (KeeperException exception) {
            if (exception.code() != Code.NONODE) {
                handleException(exception);
            }
        }
    }

    public boolean exists(String path) throws PinpointZookeeperException, InterruptedException {
        checkState();

        ZooKeeper zookeeper = this.zookeeper;
        try {
            Stat stat = zookeeper.exists(path, false);
            if (stat == null) {
                return false;
            }
        } catch (KeeperException exception) {
            if (exception.code() != Code.NODEEXISTS) {
                handleException(exception);
            }
        }
        return true;
    }

    private void checkState() throws PinpointZookeeperException {
        if (!this.zookeeperDataManager.isConnected() || !clientState.get()) {
            throw new ConnectionException("instance must be connected.");
        }
    }

    private void handleException(KeeperException keeperException) throws PinpointZookeeperException {
        switch (keeperException.code()) {
            case CONNECTIONLOSS:
            case SESSIONEXPIRED:
                throw new ConnectionException(keeperException.getMessage(), keeperException);
            case AUTHFAILED:
            case INVALIDACL:
            case NOAUTH:
                throw new AuthException(keeperException.getMessage(), keeperException);
            case BADARGUMENTS:
            case BADVERSION:
            case NOCHILDRENFOREPHEMERALS:
            case NOTEMPTY:
            case NODEEXISTS:
                throw new BadOperationException(keeperException.getMessage(), keeperException);
            case NONODE:
                throw new NoNodeException(keeperException.getMessage(), keeperException);
            case OPERATIONTIMEOUT:
                throw new TimeoutException(keeperException.getMessage(), keeperException);
            default:
                throw new UnknownException(keeperException.getMessage(), keeperException);
            }
    }

    public void close() {
        if (clientState.compareAndSet(true, false)) {
            if (timer != null) {
                timer.stop();
            }
            
            if (zookeeper != null) {
                try {
                    zookeeper.close();
                } catch (InterruptedException ignore) {
                    logger.debug(ignore.getMessage(), ignore);
                }
            }
        }
    }

}
