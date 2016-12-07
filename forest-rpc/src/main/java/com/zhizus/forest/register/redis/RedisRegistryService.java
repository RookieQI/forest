/*
 * Copyright 2002-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.zhizus.forest.register.redis;


import com.zhizus.forest.client.cluster.NamingService;
import com.zhizus.forest.common.Constants;
import com.zhizus.forest.register.AsyncRegistryService;
import com.zhizus.forest.register.NotifyListener;
import com.zhizus.forest.register.RegisterInfo;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import redis.clients.jedis.Jedis;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Register service implements by Redis server.
 *
 * @author xiemalin
 * @since 1.0
 */
public class RedisRegistryService extends AsyncRegistryService implements NamingService, DisposableBean,
        InitializingBean {

    private static final Logger logger = Logger.getLogger(RedisRegistryService.class.getName());

    private RedisClient redisClient;
    private boolean dummyRegisterFailed = true;

    private final CountDownLatch initialSignal = new CountDownLatch(1);
    private final AtomicBoolean initilized = new AtomicBoolean(false);

    private final Set<RegisterInfo> registeredInfoSet = new CopyOnWriteArraySet<RegisterInfo>();

    private static final String REGISTER = "REGISTER";

    private static final String UNREGISTER = "UNREGISTER";

    private String group;

    private boolean administrator = false;

    private final ScheduledExecutorService expireExecutor = Executors.newScheduledThreadPool(1, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, "RegistryExpireTimer");
        }
    });

    private ScheduledFuture<?> expireFuture;

    // if register time compare to current time is older than it, should remove it
    private static final int DEFAULT_EXPIRE_MS = 3000;

    private static final String ANY_VALUE = "*";

    private int expirePeriod = DEFAULT_EXPIRE_MS;

    /**
     * set expirePeriod value to expirePeriod
     *
     * @param expirePeriod the expirePeriod to set
     */
    public void setExpirePeriod(int expirePeriod) {
        this.expirePeriod = expirePeriod;
    }

    /**
     * set dummyRegisterFailed value to dummyRegisterFailed
     *
     * @param dummyRegisterFailed the dummyRegisterFailed to set
     */
    public void setDummyRegisterFailed(boolean dummyRegisterFailed) {
        this.dummyRegisterFailed = dummyRegisterFailed;
    }

    /**
     * get the redisClient
     *
     * @return the redisClient
     */
    public JedisPool getJedisPool() {
        return redisClient.getJedisPool();
    }

    /**
     * set administrator value to administrator
     *
     * @param administrator the administrator to set
     */
    public void setAdministrator(boolean administrator) {
        this.administrator = administrator;
    }

    /**
     * Initialize with redis client
     *
     * @param redisClient
     */
    public RedisRegistryService(RedisClient redisClient) {
        super();
        this.redisClient = redisClient;
    }

    private void extendUpdateTime() {
        Set<RegisterInfo> cachedList = new HashSet<RegisterInfo>(registeredInfoSet);

        Jedis resource = getJedisPool().getResource();
        try {
            for (RegisterInfo registerInfo : cachedList) {
                String hostInfo = getHostInfo(registerInfo);
                String serviceKey = Constants.SERVICE_KEY_PREIFX + group + registerInfo.getService();
                String registerTime = System.currentTimeMillis() + "";
                resource.hset(serviceKey, hostInfo, registerTime);
                logger.log(Level.FINE, "Extend time for service provider [" + registerInfo.getService() + "] of value "
                        + hostInfo);
            }
        } finally {
            getJedisPool().returnResource(resource);
        }

    }

    private void cleanExpired() {

        Jedis resource = getJedisPool().getResource();
        try {
            Set<String> keys = resource.keys(Constants.SERVICE_KEY_PREIFX + group + ANY_VALUE);

            if (keys != null && keys.size() > 0) {
                for (String key : keys) {
                    Map<String, String> values = resource.hgetAll(key);
                    if (values != null && values.size() > 0) {
                        boolean delete = false;
                        long now = System.currentTimeMillis();
                        for (Entry<String, String> entry : values.entrySet()) {
                            long expire = Long.parseLong(entry.getValue());
                            if (expire + expirePeriod < now) {
                                resource.hdel(key, entry.getKey());
                                delete = true;
                                logger.log(Level.INFO, "Delete expired key: " + key + " -> value: " + entry.getKey()
                                        + ", expire: " + new Date(expire + expirePeriod) + ", now: " + new Date(now));
                            }
                        }
                        if (delete) {
                            resource.publish(key, UNREGISTER);
                        }
                    }
                }
            }
        } finally {
            getJedisPool().returnResource(resource);
        }

    }

    /**
     * @param registerInfo
     */
    private String getHostInfo(RegisterInfo registerInfo) {
        String host = registerInfo.getHost();
        int port = registerInfo.getPort();
        try {
            URI uri = new URI(registerInfo.getProtocol(), null, host, port, null, null, null);
            return uri.toString();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.baidu.pbrpc.register.RegistryService#unregister(com.baidu.pbrpc.register.RigisterInfo)
     */
    @Override
    public void unregister(RegisterInfo registerInfo) {
        if (registerInfo == null) {
            throw new NullPointerException("param 'reRigisterInfo' is null");
        }

        String hostInfo = getHostInfo(registerInfo);
        String serviceKey = Constants.SERVICE_KEY_PREIFX + group + registerInfo.getService();

        Jedis resource = null;
        try {
            resource = getJedisPool().getResource();
            registeredInfoSet.remove(hostInfo);
            resource.hdel(serviceKey, hostInfo);
            logger.log(Level.INFO, "UnRegister service provider [" + registerInfo.getService() + "] for " + hostInfo);
            resource.publish(serviceKey, UNREGISTER);
        } catch (Exception e) {
            if (!dummyRegisterFailed) {
                throw new RuntimeException(e.getMessage(), e);
            }
            logger.log(Level.SEVERE, e.getMessage(), e);
        } finally {
            if (resource != null) {
                getJedisPool().returnResource(resource);
            }
        }

    }

    /*
     * (non-Javadoc)
     * 
     * @see com.baidu.pbrpc.register.RegistryService#subscribe(com.baidu.pbrpc.register.RigisterInfo,
     * com.baidu.pbrpc.register.NotifyListener)
     */
    @Override
    public void subscribe(RegisterInfo registerInfo, NotifyListener listener) {
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.baidu.pbrpc.register.RegistryService#unsubscribe(com.baidu.pbrpc.register.RigisterInfo,
     * com.baidu.pbrpc.register.NotifyListener)
     */
    @Override
    public void unsubscribe(RegisterInfo url, NotifyListener listener) {

    }

    /*
     * (non-Javadoc)
     * 
     * @see com.baidu.pbrpc.register.RegistryService#lookup(com.baidu.pbrpc.register.RigisterInfo)
     */
    @Override
    public List<RegisterInfo> lookup(RegisterInfo registerInfo) {

        String serviceKey = Constants.SERVICE_KEY_PREIFX + group + registerInfo.getService();

        Jedis resource = null;
        try {
            resource = getJedisPool().getResource();
            Map<String, String> map = resource.hgetAll(serviceKey);

            Iterator<Entry<String, String>> iter = map.entrySet().iterator();
            List<RegisterInfo> list = new ArrayList<RegisterInfo>();
            while (iter.hasNext()) {
                Entry<String, String> entry = iter.next();
                RegisterInfo host = parseHost(serviceKey, entry.getKey());
                if (host == null) {
                    continue;
                }
                host.setService(registerInfo.getService());
                list.add(host);
            }
            return list;
        } catch (Exception e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
        } finally {
            if (resource != null) {
                getJedisPool().returnResource(resource);
            }
        }

        return null;
    }

    /**
     * @return
     */
    private RegisterInfo parseHost(String serviceKey, String host) {
        RegisterInfo info = new RegisterInfo();

        try {
            URI uri = new URI(host);
            info.setHost(uri.getHost());
            info.setPort(uri.getPort());
            info.setProtocol(uri.getScheme());
            return info;
        } catch (URISyntaxException e) {
            // invalid should ignore
            logger.log(Level.SEVERE, "look from service:" + serviceKey + " value is invalid:" + host);
            return null;
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.baidu.jprotobuf.pbrpc.client.ha.NamingService#list(java.util.Set)
     */
    @Override
    public Map<String, List<RegisterInfo>> list(Set<String> serviceSignatures) throws Exception {
        Map<String, List<RegisterInfo>> ret = new HashMap<String, List<RegisterInfo>>();
        if (serviceSignatures == null) {
            return ret;
        }

        RegisterInfo registerInfo;
        for (String service : serviceSignatures) {
            registerInfo = new RegisterInfo();
            registerInfo.setService(service);

            List<RegisterInfo> result = lookup(registerInfo);
            ret.put(service, result);
        }

        return ret;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.springframework.beans.factory.DisposableBean#destroy()
     */
    @Override
    public void destroy() throws Exception {
        if (expireFuture != null) {
            expireFuture.cancel(true);
        }
        stop();
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.springframework.beans.factory.InitializingBean#afterPropertiesSet()
     */
    @Override
    public void afterPropertiesSet() throws Exception {
        try {
            redisClient.init();
        } catch (Exception e) {
            if (!dummyRegisterFailed) {
                throw new RuntimeException(e.getMessage(), e);
            }
            logger.log(Level.SEVERE, e.getMessage(), e);
        }

        this.expireFuture = expireExecutor.scheduleWithFixedDelay(new Runnable() {
            public void run() {
                try {
                    extendUpdateTime(); // 延长过期时间
                } catch (Throwable t) { // 防御性容错
                    logger.log(Level.SEVERE,
                            "Unexpected exception occur at defer expire time, cause: " + t.getMessage(), t);
                }

                try {
                    if (administrator) {
                        cleanExpired();
                    }
                } catch (Throwable t) {
                    logger.log(Level.SEVERE,
                            "Unexpected exception occur at defer expire time, cause: " + t.getMessage(), t);
                }
            }

        }, expirePeriod / 5, expirePeriod / 3, TimeUnit.MILLISECONDS);

    }

    /*
     * (non-Javadoc)
     * 
     * @see com.baidu.pbrpc.register.AsnycRegistryService#doRegister(com.baidu.jprotobuf.pbrpc.registry.RegisterInfo)
     */
    @Override
    protected void doRegister(RegisterInfo registerInfo) {
        if (registerInfo == null) {
            throw new NullPointerException("param 'reRigisterInfo' is null");
        }

        String hostInfo = getHostInfo(registerInfo);
        String serviceKey = Constants.SERVICE_KEY_PREIFX + group + registerInfo.getService();
        String registerTime = System.currentTimeMillis() + "";

        Jedis resource = null;
        try {
            resource = getJedisPool().getResource();
            registeredInfoSet.add(registerInfo);
            resource.hset(serviceKey, hostInfo, registerTime);
            logger.log(Level.INFO, "Register service provider [" + registerInfo.getService() + "] for " + hostInfo);
            resource.publish(serviceKey, REGISTER);
        } catch (Exception e) {
            if (!dummyRegisterFailed) {
                throw new RuntimeException(e.getMessage(), e);
            }
            logger.log(Level.SEVERE, e.getMessage(), e);
        } finally {
            if (resource != null) {
                getJedisPool().returnResource(resource);
            }
        }

    }

    /**
     * get the group
     *
     * @return the group
     */
    public String getGroup() {
        return group;
    }

    /**
     * set group value to group
     *
     * @param group the group to set
     */
    public void setGroup(String group) {
        this.group = group;
    }

}