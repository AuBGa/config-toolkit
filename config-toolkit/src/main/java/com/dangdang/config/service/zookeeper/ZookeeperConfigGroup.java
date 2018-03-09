/**
 * Copyright 1999-2014 dangdang.com.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dangdang.config.service.zookeeper;

import com.dangdang.config.service.ConfigGroup;
import com.dangdang.config.service.GeneralConfigGroup;
import com.dangdang.config.service.util.Tuple;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.api.CuratorListener;
import org.apache.curator.framework.api.GetChildrenBuilder;
import org.apache.curator.framework.api.GetDataBuilder;
import org.apache.curator.utils.ZKPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PreDestroy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 配置组节点
 *
 * @author <a href="mailto:wangyuxuan@dangdang.com">Yuxuan Wang</a>
 *
 */
public class ZookeeperConfigGroup extends GeneralConfigGroup {

    private static final long serialVersionUID = 1L;

    private ZookeeperConfigProfile configProfile;

    /**
     * 节点名字
     */
    private String node;

    private CuratorFramework client;

    private ConfigLocalCache configLocalCache;

    static final Logger LOGGER = LoggerFactory.getLogger(ZookeeperConfigGroup.class);

    public ZookeeperConfigGroup(ZookeeperConfigProfile configProfile, String node, boolean enumerable) {
        this(configProfile, node);
        super.enumerable = enumerable;
    }

    public ZookeeperConfigGroup(ZookeeperConfigProfile configProfile, String node) {
        this(null, configProfile, node);
    }

    public ZookeeperConfigGroup(ConfigGroup internalConfigGroup, ZookeeperConfigProfile configProfile, String node) {
        super(internalConfigGroup);
        this.configProfile = configProfile;
        this.node = node;

        if (configProfile.isOpenLocalCache()) {
            configLocalCache = new ConfigLocalCache(System.getProperty("user.home") + "/.config-toolkit", configProfile.getRootNode());
        }

        initConfigs();
    }

    private CuratorListener listener = new ConfigNodeEventListener(this);

    /**
     * 初始化节点
     */
    private void initConfigs() {
        client = CuratorFrameworkFactory.newClient(configProfile.getConnectStr(), configProfile.getRetryPolicy());
        client.start();
        client.getCuratorListenable().addListener(listener);

        LOGGER.debug("Loading properties for node: {}", node);
        loadNode();

        // Update local cache
        if (configLocalCache != null) {
            configLocalCache.saveLocalCache(this, node);
        }
    }

    /**
     * 加载节点并监听节点变化
     */
    void loadNode() {
        final String nodePath = ZKPaths.makePath(configProfile.getVersionedRootNode(), node);

        final GetChildrenBuilder childrenBuilder = client.getChildren();

        try {
            final List<String> children = childrenBuilder.watched().forPath(nodePath);
            if (children != null) {
                final Map<String, String> configs = new HashMap<>();
                for (String child : children) {
                    final Tuple<String, String> keyValue = loadKey(ZKPaths.makePath(nodePath, child));
                    if (keyValue != null) {
                        configs.put(keyValue.getFirst(), keyValue.getSecond());
                    }
                }
                cleanAndPutAll(configs);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    void reloadKey(final String nodePath) {
        try {
            final Tuple<String, String> keyValue = loadKey(nodePath);
            if (keyValue != null) {
                super.put(keyValue.getFirst(), keyValue.getSecond());
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Tuple<String, String> loadKey(final String nodePath) throws Exception {
        final String nodeName = ZKPaths.getNodeFromPath(nodePath);
        final Set<String> keysSpecified = configProfile.getKeysSpecified();
        switch (configProfile.getKeyLoadingMode()) {
            case INCLUDE:
                if (keysSpecified == null || !keysSpecified.contains(nodeName)) {
                    return null;
                }
                break;
            case EXCLUDE:
                if (keysSpecified.contains(nodeName)) {
                    return null;
                }
                break;
            case ALL:
                break;
            default:
                break;
        }

        final GetDataBuilder data = client.getData();
        final String value = new String(data.watched().forPath(nodePath), "UTF-8");
        return new Tuple<>(nodeName, value);
    }

    public String getNode() {
        return node;
    }

    public ConfigLocalCache getConfigLocalCache() {
        return configLocalCache;
    }

    /**
     * 导出属性列表
     *
     * @return
     */
    public Map<String, String> exportProperties() {
        return new HashMap(this);
    }

    @PreDestroy
    @Override
    public void close() {
        if (client != null) {
            client.getCuratorListenable().removeListener(listener);
            client.close();
        }

    }

}
