/*
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
package org.apache.bookkeeper.client;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.bookkeeper.client.BKException.BKNotEnoughBookiesException;
import org.apache.bookkeeper.conf.Configurable;
import org.apache.bookkeeper.net.CachedDNSToSwitchMapping;
import org.apache.bookkeeper.net.DNSToSwitchMapping;
import org.apache.bookkeeper.net.NetworkTopology;
import org.apache.bookkeeper.net.Node;
import org.apache.bookkeeper.net.NodeBase;
import org.apache.bookkeeper.net.ScriptBasedMapping;
import org.apache.bookkeeper.util.ReflectionUtils;
import org.apache.commons.configuration.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

/**
 * Simple rackware ensemble placement policy.
 *
 * Make most of the class and methods as protected, so it could be extended to implement other algorithms.
 */
public class RackawareEnsemblePlacementPolicy extends TopologyAwareEnsemblePlacementPolicy {

    static final Logger LOG = LoggerFactory.getLogger(RackawareEnsemblePlacementPolicy.class);

    public static final String REPP_DNS_RESOLVER_CLASS = "reppDnsResolverClass";
    static final int RACKNAME_DISTANCE_FROM_LEAVES = 1;

    static class DefaultResolver implements DNSToSwitchMapping {

        @Override
        public List<String> resolve(List<String> names) {
            List<String> rNames = new ArrayList<String>(names.size());
            for (@SuppressWarnings("unused") String name : names) {
                rNames.add(NetworkTopology.DEFAULT_RACK);
            }
            return rNames;
        }

        @Override
        public void reloadCachedMappings() {
            // nop
        }

    };

    // for now, we just maintain the writable bookies' topology
    protected final NetworkTopology topology;
    protected DNSToSwitchMapping dnsResolver;
    protected final Map<InetSocketAddress, BookieNode> knownBookies;
    protected BookieNode localNode;
    protected final ReentrantReadWriteLock rwLock;
    protected ImmutableSet<InetSocketAddress> readOnlyBookies = null;


    public RackawareEnsemblePlacementPolicy() {
        topology = new NetworkTopology();
        knownBookies = new HashMap<InetSocketAddress, BookieNode>();

        rwLock = new ReentrantReadWriteLock();
    }

    protected BookieNode createBookieNode(InetSocketAddress addr) {
        return new BookieNode(addr, resolveNetworkLocation(addr));
    }

    /**
     * Initialize the policy.
     *
     * @param dnsResolver the object used to resolve addresses to their network address
     * @return initialized ensemble placement policy
     */
    @Override
    public RackawareEnsemblePlacementPolicy initialize(DNSToSwitchMapping dnsResolver) {
        this.dnsResolver = dnsResolver;
        BookieNode bn;
        try {
            bn = createBookieNode(new InetSocketAddress(InetAddress.getLocalHost().getHostAddress(), 0));
        } catch (UnknownHostException e) {
            LOG.error("Failed to get local host address : ", e);
            bn = null;
        }
        localNode = bn;
        LOG.info("Initialize rackaware ensemble placement policy @ {} @ {} : {}.",
            new Object[] { localNode, null == localNode ? "Unknown" : localNode.getNetworkLocation(),
                dnsResolver.getClass().getName() });
        return this;
    }

    @Override
    public RackawareEnsemblePlacementPolicy initialize(Configuration conf) {
        String dnsResolverName = conf.getString(REPP_DNS_RESOLVER_CLASS, ScriptBasedMapping.class.getName());
        DNSToSwitchMapping dnsResolver;
        try {
            dnsResolver = ReflectionUtils.newInstance(dnsResolverName, DNSToSwitchMapping.class);
            if (dnsResolver instanceof Configurable) {
                ((Configurable) dnsResolver).setConf(conf);
            }
        } catch (RuntimeException re) {
            LOG.info("Failed to initialize DNS Resolver {}, used default subnet resolver.", dnsResolverName, re);
            dnsResolver = new DefaultResolver();
        }

        return initialize(dnsResolver);
    }

    @Override
    public void uninitalize() {
        // do nothing
    }

    protected String resolveNetworkLocation(InetSocketAddress addr) {
        List<String> names = new ArrayList<String>(1);
        if (dnsResolver instanceof CachedDNSToSwitchMapping) {
            names.add(addr.getAddress().getHostAddress());
        } else {
            names.add(addr.getHostName());
        }
        // resolve network addresses
        List<String> rNames = dnsResolver.resolve(names);
        String netLoc;
        if (null == rNames) {
            LOG.warn("Failed to resolve network location for {}, using default rack for them : {}.", names,
                    NetworkTopology.DEFAULT_RACK);
            netLoc = NetworkTopology.DEFAULT_RACK;
        } else {
            netLoc = rNames.get(0);
        }
        return netLoc;
    }

    @Override
    public Set<InetSocketAddress> onClusterChanged(Set<InetSocketAddress> writableBookies,
            Set<InetSocketAddress> readOnlyBookies) {
        rwLock.writeLock().lock();
        try {
            ImmutableSet<InetSocketAddress> joinedBookies, leftBookies, deadBookies;
            Set<InetSocketAddress> oldBookieSet = knownBookies.keySet();
            // left bookies : bookies in known bookies, but not in new writable bookie cluster.
            leftBookies = Sets.difference(oldBookieSet, writableBookies).immutableCopy();
            // joined bookies : bookies in new writable bookie cluster, but not in known bookies
            joinedBookies = Sets.difference(writableBookies, oldBookieSet).immutableCopy();
            // dead bookies.
            deadBookies = Sets.difference(leftBookies, readOnlyBookies).immutableCopy();
            if (LOG.isDebugEnabled()) {
                LOG.debug(
                        "Cluster changed : left bookies are {}, joined bookies are {}, while dead bookies are {}.",
                        new Object[] { leftBookies, joinedBookies, deadBookies });
            }
            handleBookiesThatLeft(leftBookies);
            handleBookiesThatJoined(joinedBookies);

            if (!readOnlyBookies.isEmpty()) {
                this.readOnlyBookies = ImmutableSet.copyOf(readOnlyBookies);
            }

            return deadBookies;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    protected void handleBookiesThatLeft(Set<InetSocketAddress> leftBookies) {
        for (InetSocketAddress addr : leftBookies) {
            BookieNode node = knownBookies.remove(addr);
            if(null != node) {
                topology.remove(node);
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Cluster changed : bookie {} left from cluster.", addr);
                }
            }
        }
    }

    protected void handleBookiesThatJoined(Set<InetSocketAddress> joinedBookies) {
        // node joined
        for (InetSocketAddress addr : joinedBookies) {
            BookieNode node = createBookieNode(addr);
            topology.add(node);
            knownBookies.put(addr, node);
            if (LOG.isDebugEnabled()) {
                LOG.debug("Cluster changed : bookie {} joined the cluster.", addr);
            }
        }
    }

    protected Set<Node> convertBookiesToNodes(Set<InetSocketAddress> excludeBookies) {
        Set<Node> nodes = new HashSet<Node>();
        for (InetSocketAddress addr : excludeBookies) {
            BookieNode bn = knownBookies.get(addr);
            if (null == bn) {
                bn = createBookieNode(addr);
            }
            nodes.add(bn);
        }
        return nodes;
    }

    @Override
    public ArrayList<InetSocketAddress> newEnsemble(int ensembleSize, int writeQuorumSize,
                                                    Set<InetSocketAddress> excludeBookies) throws BKNotEnoughBookiesException {
        return newEnsembleInternal(ensembleSize, writeQuorumSize, excludeBookies, null);
    }

    public ArrayList<InetSocketAddress> newEnsembleInternal(int ensembleSize, int writeQuorumSize,
            Set<InetSocketAddress> excludeBookies, RRTopologyAwareCoverageEnsemble parentEnsemble) throws BKNotEnoughBookiesException {
        rwLock.readLock().lock();
        try {
            Set<Node> excludeNodes = convertBookiesToNodes(excludeBookies);
            RRTopologyAwareCoverageEnsemble ensemble = new RRTopologyAwareCoverageEnsemble(ensembleSize, writeQuorumSize, RACKNAME_DISTANCE_FROM_LEAVES, parentEnsemble);
            BookieNode prevNode = null;
            int numRacks = topology.getNumOfRacks();
            // only one rack, use the random algorithm.
            if (numRacks < 2) {
                List<BookieNode> bns = selectRandom(ensembleSize, excludeNodes,
                        ensemble);
                ArrayList<InetSocketAddress> addrs = new ArrayList<InetSocketAddress>(ensembleSize);
                for (BookieNode bn : bns) {
                    addrs.add(bn.getAddr());
                }
                return addrs;
            }
            // pick nodes by racks, to ensure there is at least two racks per write quorum.
            for (int i = 0; i < ensembleSize; i++) {
                String curRack;
                if (null == prevNode) {
                    if (null == localNode) {
                        curRack = NodeBase.ROOT;
                    } else {
                        curRack = localNode.getNetworkLocation();
                    }
                } else {
                    curRack = "~" + prevNode.getNetworkLocation();
                }
                prevNode = selectFromRack(curRack, excludeNodes, ensemble, ensemble);
            }
            ArrayList<InetSocketAddress> bookieList = ensemble.toList();
            if (ensembleSize != bookieList.size()) {
                LOG.error("Not enough {} bookies are available to form an ensemble : {}.",
                          ensembleSize, bookieList);
                throw new BKNotEnoughBookiesException();
            }
            return bookieList;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public InetSocketAddress replaceBookie(InetSocketAddress bookieToReplace,
            Set<InetSocketAddress> excludeBookies) throws BKNotEnoughBookiesException {
        rwLock.readLock().lock();
        try {
            BookieNode bn = knownBookies.get(bookieToReplace);
            if (null == bn) {
                bn = createBookieNode(bookieToReplace);
            }

            Set<Node> excludeNodes = convertBookiesToNodes(excludeBookies);
            // add the bookie to replace in exclude set
            excludeNodes.add(bn);
            if (LOG.isDebugEnabled()) {
                LOG.debug("Try to choose a new bookie to replace {}, excluding {}.", bookieToReplace,
                        excludeNodes);
            }
            // pick a candidate from same rack to replace
            BookieNode candidate = selectFromRack(bn.getNetworkLocation(), excludeNodes,
                    TruePredicate.instance, EnsembleForReplacement.instance);
            if (LOG.isDebugEnabled()) {
                LOG.debug("Bookie {} is chosen to replace bookie {}.", candidate, bn);
            }
            return candidate.getAddr();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    protected BookieNode selectFromRack(String networkLoc, Set<Node> excludeBookies, Predicate predicate,
            Ensemble ensemble) throws BKNotEnoughBookiesException {
        // select one from local rack
        try {
            return selectRandomFromRack(networkLoc, excludeBookies, predicate, ensemble);
        } catch (BKNotEnoughBookiesException e) {
            LOG.warn("Failed to choose a bookie from {} : "
                     + "excluded {}, fallback to choose bookie randomly from the cluster.",
                     networkLoc, excludeBookies);
            // randomly choose one from whole cluster, ignore the provided predicate.
            return selectRandom(1, excludeBookies, ensemble).get(0);
        }
    }

    protected String getRemoteRack(BookieNode node) {
        return "~" + node.getNetworkLocation();
    }

    /**
     * Choose random node under a given network path.
     *
     * @param netPath
     *          network path
     * @param excludeBookies
     *          exclude bookies
     * @param predicate
     *          predicate to check whether the target is a good target.
     * @param ensemble
     *          ensemble structure
     * @return chosen bookie.
     */
    protected BookieNode selectRandomFromRack(String netPath, Set<Node> excludeBookies, Predicate predicate,
            Ensemble ensemble) throws BKNotEnoughBookiesException {
        List<Node> leaves = new ArrayList<Node>(topology.getLeaves(netPath));
        Collections.shuffle(leaves);
        for (Node n : leaves) {
            if (excludeBookies.contains(n)) {
                continue;
            }
            if (!(n instanceof BookieNode) || !predicate.apply((BookieNode) n, ensemble)) {
                continue;
            }
            BookieNode bn = (BookieNode) n;
            // got a good candidate
            ensemble.addBookie(bn);
            // add the candidate to exclude set
            excludeBookies.add(bn);
            return bn;
        }
        throw new BKNotEnoughBookiesException();
    }

    /**
     * Choose a random node from whole cluster.
     *
     * @param numBookies
     *          number bookies to choose
     * @param excludeBookies
     *          bookies set to exclude.
     * @param ensemble
     *          ensemble to hold the bookie chosen.
     * @return the bookie node chosen.
     * @throws BKNotEnoughBookiesException
     */
    protected List<BookieNode> selectRandom(int numBookies, Set<Node> excludeBookies, Ensemble ensemble)
            throws BKNotEnoughBookiesException {
        List<BookieNode> allBookies = new ArrayList<BookieNode>(knownBookies.values());
        Collections.shuffle(allBookies);
        List<BookieNode> newBookies = new ArrayList<BookieNode>(numBookies);
        for (BookieNode bookie : allBookies) {
            if (excludeBookies.contains(bookie)) {
                continue;
            }
            ensemble.addBookie(bookie);
            excludeBookies.add(bookie);
            newBookies.add(bookie);
            --numBookies;
            if (numBookies == 0) {
                return newBookies;
            }
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("Failed to find {} bookies : excludeBookies {}, allBookies {}.", new Object[] {
                    numBookies, excludeBookies, allBookies });
        }
        throw new BKNotEnoughBookiesException();
    }

    @Override
    public List<Integer> reorderReadSequence(ArrayList<InetSocketAddress> ensemble, List<Integer> writeSet) {
        List<Integer> finalList = new ArrayList<Integer>(writeSet.size());
        List<Integer> readOnlyList = new ArrayList<Integer>(writeSet.size());
        List<Integer> unAvailableList = new ArrayList<Integer>(writeSet.size());
        for (Integer idx : writeSet) {
            InetSocketAddress address = ensemble.get(idx);
            if (null == knownBookies.get(address)) {
                // there isn't too much differences between readonly bookies from unavailable bookies. since there
                // is no write requests to them, so we shouldn't try reading from readonly bookie in prior to writable
                // bookies.
                if ((null == readOnlyBookies) || !readOnlyBookies.contains(address)) {
                    unAvailableList.add(idx);
                } else {
                    readOnlyList.add(idx);
                }
            } else {
                finalList.add(idx);
            }
        }
        finalList.addAll(readOnlyList);
        finalList.addAll(unAvailableList);
        return finalList;
    }
}
