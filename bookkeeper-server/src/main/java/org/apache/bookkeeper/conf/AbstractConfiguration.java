/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.bookkeeper.conf;

import java.net.URL;

import org.apache.commons.configuration.CompositeConfiguration;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.commons.configuration.SystemConfiguration;

import org.apache.bookkeeper.meta.LedgerManagerFactory;
import org.apache.bookkeeper.util.ReflectionUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract configuration
 */
public abstract class AbstractConfiguration extends CompositeConfiguration {

    static final Logger LOG = LoggerFactory.getLogger(AbstractConfiguration.class);

    private static ClassLoader defaultLoader;
    static {
        defaultLoader = Thread.currentThread().getContextClassLoader();
        if (null == defaultLoader) {
            defaultLoader = AbstractConfiguration.class.getClassLoader();
        }
    }

    // Ledger Manager
    protected final static String LEDGER_MANAGER_TYPE = "ledgerManagerType";
    protected final static String LEDGER_MANAGER_FACTORY_CLASS = "ledgerManagerFactoryClass";
    protected final static String ZK_LEDGERS_ROOT_PATH = "zkLedgersRootPath";
    protected final static String AVAILABLE_NODE = "available";
    protected AbstractConfiguration() {
        super();
        // add configuration for system properties
        addConfiguration(new SystemConfiguration());
    }

    /**
     * You can load configurations in precedence order. The first one takes
     * precedence over any loaded later.
     *
     * @param confURL
     *          Configuration URL
     */
    public void loadConf(URL confURL) throws ConfigurationException {
        Configuration loadedConf = new PropertiesConfiguration(confURL);
        addConfiguration(loadedConf);
    }

    /**
     * You can load configuration from other configuration
     *
     * @param baseConf
     *          Other Configuration
     */
    public void loadConf(AbstractConfiguration baseConf) {
        addConfiguration(baseConf); 
    }

    /**
     * Load configuration from other configuration object
     *
     * @param otherConf
     *          Other configuration object
     */
    public void loadConf(Configuration otherConf) {
        addConfiguration(otherConf);
    }

    /**
     * Set Ledger Manager Type.
     *
     * @param lmType
     *          Ledger Manager Type
     * @return void
     * @deprecated replaced by {@link #setLedgerManagerFactoryClass()}
     */
    @Deprecated
    public void setLedgerManagerType(String lmType) {
        setProperty(LEDGER_MANAGER_TYPE, lmType); 
    }

    /**
     * Get Ledger Manager Type.
     *
     * @return ledger manager type
     * @throws ConfigurationException
     * @deprecated replaced by {@link #getLedgerManagerFactoryClass()}
     */
    @Deprecated
    public String getLedgerManagerType() {
        return getString(LEDGER_MANAGER_TYPE);
    }

    /**
     * Set Ledger Manager Factory Class Name.
     *
     * @param factoryClassName
     *          Ledger Manager Factory Class Name
     * @return void
     */
    public void setLedgerManagerFactoryClassName(String factoryClassName) {
        setProperty(LEDGER_MANAGER_FACTORY_CLASS, factoryClassName);
    }

    /**
     * Set Ledger Manager Factory Class.
     *
     * @param factoryClass
     *          Ledger Manager Factory Class
     * @return void
     */
    public void setLedgerManagerFactoryClass(Class<? extends LedgerManagerFactory> factoryClass) {
        setProperty(LEDGER_MANAGER_FACTORY_CLASS, factoryClass.getName());
    }

    /**
     * Get ledger manager factory class.
     *
     * @return ledger manager factory class
     */
    public Class<? extends LedgerManagerFactory> getLedgerManagerFactoryClass()
        throws ConfigurationException {
        return ReflectionUtils.getClass(this, LEDGER_MANAGER_FACTORY_CLASS,
                                        null, LedgerManagerFactory.class,
                                        defaultLoader);
    }

    /**
     * Set Zk Ledgers Root Path.
     *
     * @param zkLedgersPath zk ledgers root path
     */
    public void setZkLedgersRootPath(String zkLedgersPath) {
        setProperty(ZK_LEDGERS_ROOT_PATH, zkLedgersPath);
    }

    /**
     * Get Zk Ledgers Root Path.
     *
     * @return zk ledgers root path
     */
    public String getZkLedgersRootPath() {
        return getString(ZK_LEDGERS_ROOT_PATH, "/ledgers");
    }

    /**
     * Get the node under which available bookies are stored
     *
     * @return Node under which available bookies are stored.
     */
    public String getZkAvailableBookiesPath() {
        return getZkLedgersRootPath() + "/" + AVAILABLE_NODE;
    }
}