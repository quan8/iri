package com.iota.iri.network;

import com.iota.iri.MilestoneTracker;
import com.iota.iri.TransactionValidator;
import com.iota.iri.conf.NodeConfig;
import com.iota.iri.controllers.TipsViewModel;
import com.iota.iri.controllers.TransactionViewModel;
import com.iota.iri.hash.SpongeFactory;
import com.iota.iri.model.Hash;
import com.iota.iri.model.HashFactory;
import com.iota.iri.model.TransactionHash;
import com.iota.iri.storage.Tangle;
import com.iota.iri.zmq.MessageQ;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.*;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The class node is responsible for managing Thread's connection.
 */
public class Node {

    private static final Logger log = LoggerFactory.getLogger(Node.class);
    private final int reqHashSize;


    private int BROADCAST_QUEUE_SIZE;
    private int RECV_QUEUE_SIZE;
    private int REPLY_QUEUE_SIZE;
    private static final int PAUSE_BETWEEN_TRANSACTIONS = 1;

    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    private final List<Neighbor> neighbors = new CopyOnWriteArrayList<>();
    private final ConcurrentSkipListSet<TransactionViewModel> broadcastQueue = weightQueue();
    private final ConcurrentSkipListSet<Pair<TransactionViewModel, Neighbor>> receiveQueue = weightQueueTxPair();
    private final ConcurrentSkipListSet<Pair<Hash, Neighbor>> replyQueue = weightQueueHashPair();


    private final DatagramPacket sendingPacket;
    private final DatagramPacket tipRequestingPacket;

    private final ExecutorService executor = Executors.newFixedThreadPool(5);
    private final NodeConfig configuration;
    private final Tangle tangle;
    private final TipsViewModel tipsViewModel;
    private final TransactionValidator transactionValidator;
    private final MilestoneTracker milestoneTracker;
    private final TransactionRequester transactionRequester;
    private final MessageQ messageQ;

    private static final SecureRandom rnd = new SecureRandom();


    private FIFOCache<ByteBuffer, Hash> recentSeenBytes;

    private static AtomicLong recentSeenBytesMissCount = new AtomicLong(0L);
    private static AtomicLong recentSeenBytesHitCount = new AtomicLong(0L);

    private static long sendLimit = -1;
    private static AtomicLong sendPacketsCounter = new AtomicLong(0L);
    private static AtomicLong sendPacketsTimer = new AtomicLong(0L);

    public static final ConcurrentSkipListSet<String> rejectedAddresses = new ConcurrentSkipListSet<String>();
    private DatagramSocket udpSocket;

    public Node(final Tangle tangle, final TransactionValidator transactionValidator, final TransactionRequester transactionRequester, final TipsViewModel tipsViewModel, final MilestoneTracker milestoneTracker, final MessageQ messageQ, final NodeConfig configuration
    ) {
        this.configuration = configuration;
        this.tangle = tangle;
        this.transactionValidator = transactionValidator;
        this.transactionRequester = transactionRequester;
        this.tipsViewModel = tipsViewModel;
        this.milestoneTracker = milestoneTracker;
        this.messageQ = messageQ;
        this.reqHashSize = configuration.getRequestHashSize();
        int packetSize = configuration.getTransactionPacketSize();
        this.sendingPacket = new DatagramPacket(new byte[packetSize], packetSize);
        this.tipRequestingPacket = new DatagramPacket(new byte[packetSize], packetSize);

    }

    public void init() throws Exception {

        //TODO ask Alon
        sendLimit = (long) ((configuration.getSendLimit() * 1000000) / (configuration.getTransactionPacketSize() * 8));

        BROADCAST_QUEUE_SIZE = RECV_QUEUE_SIZE = REPLY_QUEUE_SIZE = configuration.getqSizeNode();
        recentSeenBytes = new FIFOCache<>(configuration.getCacheSizeBytes(), configuration.getpDropCacheEntry());

        parseNeighborsConfig();

        executor.submit(spawnBroadcasterThread());
        executor.submit(spawnTipRequesterThread());
        executor.submit(spawnNeighborDNSRefresherThread());
        executor.submit(spawnProcessReceivedThread());
        executor.submit(spawnReplyToRequestThread());

        executor.shutdown();
    }

    public void setUDPSocket(final DatagramSocket socket) {
        this.udpSocket = socket;
    }

    public DatagramSocket getUdpSocket() {
        return udpSocket;
    }

    private final Map<String, String> neighborIpCache = new HashMap<>();

    private Runnable spawnNeighborDNSRefresherThread() {
        return () -> {
            if (configuration.isDnsResolutionEnabled()) {
                log.info("Spawning Neighbor DNS Refresher Thread");

                while (!shuttingDown.get()) {
                    int dnsCounter = 0;
                    log.info("Checking Neighbors' Ip...");

                    try {
                        neighbors.forEach(n -> {
                            final String hostname = n.getAddress().getHostName();
                            checkIp(hostname).ifPresent(ip -> {
                                log.info("DNS Checker: Validating DNS Address '{}' with '{}'", hostname, ip);
                                messageQ.publish("dnscv %s %s", hostname, ip);
                                final String neighborAddress = neighborIpCache.get(hostname);

                                if (neighborAddress == null) {
                                    neighborIpCache.put(hostname, ip);
                                } else {
                                    if (neighborAddress.equals(ip)) {
                                        log.info("{} seems fine.", hostname);
                                        messageQ.publish("dnscc %s", hostname);
                                    } else {
                                        if (configuration.isDnsRefresherEnabled()) {
                                            log.info("IP CHANGED for {}! Updating...", hostname);
                                            messageQ.publish("dnscu %s", hostname);
                                            String protocol = (n instanceof TCPNeighbor) ? "tcp://" : "udp://";
                                            String port = ":" + n.getAddress().getPort();

                                            uri(protocol + hostname + port).ifPresent(uri -> {
                                                removeNeighbor(uri, n.isFlagged());

                                                uri(protocol + ip + port).ifPresent(nuri -> {
                                                    Neighbor neighbor = newNeighbor(nuri, n.isFlagged());
                                                    addNeighbor(neighbor);
                                                    neighborIpCache.put(hostname, ip);
                                                });
                                            });
                                        } else {
                                            log.info("IP CHANGED for {}! Skipping... DNS_REFRESHER_ENABLED is false.", hostname);
                                        }
                                    }
                                }
                            });
                        });

                        while (dnsCounter++ < 60 * 30 && !shuttingDown.get()) {
                            Thread.sleep(1000);
                        }
                    } catch (final Exception e) {
                        log.error("Neighbor DNS Refresher Thread Exception:", e);
                    }
                }
                log.info("Shutting down Neighbor DNS Refresher Thread");
            } else {
                log.info("Ignoring DNS Refresher Thread... DNS_RESOLUTION_ENABLED is false");
            }
        };
    }

    private Optional<String> checkIp(final String dnsName) {

        if (StringUtils.isEmpty(dnsName)) {
            return Optional.empty();
        }

        InetAddress inetAddress;
        try {
            inetAddress = java.net.InetAddress.getByName(dnsName);
        } catch (UnknownHostException e) {
            return Optional.empty();
        }

        final String hostAddress = inetAddress.getHostAddress();

        if (StringUtils.equals(dnsName, hostAddress)) { // not a DNS...
            return Optional.empty();
        }

        return Optional.of(hostAddress);
    }

    public void preProcessReceivedData(byte[] receivedData, SocketAddress senderAddress, String uriScheme) {
        TransactionViewModel receivedTransactionViewModel = null;
        Hash receivedTransactionHash = null;

        boolean addressMatch = false;
        boolean cached = false;
        double pDropTransaction = configuration.getpDropTransaction();

        for (final Neighbor neighbor : getNeighbors()) {
            addressMatch = neighbor.matches(senderAddress);
            if (addressMatch) {
                //Validate transaction
                neighbor.incAllTransactions();
                if (rnd.nextDouble() < pDropTransaction) {
                    //log.info("Randomly dropping transaction. Stand by... ");
                    break;
                }
                try {

                    //Transaction bytes
                    ByteBuffer digest = getBytesDigest(receivedData);

                    //check if cached
                    synchronized (recentSeenBytes) {
                        cached = (receivedTransactionHash = recentSeenBytes.get(digest)) != null;
                    }

                    if (!cached) {
                        //if not, then validate
                        receivedTransactionViewModel = new TransactionViewModel(receivedData, TransactionHash.calculate(receivedData, TransactionViewModel.TRINARY_SIZE, SpongeFactory.create(SpongeFactory.Mode.CURLP81)));
                        receivedTransactionHash = receivedTransactionViewModel.getHash();
                        transactionValidator.runValidation(receivedTransactionViewModel, transactionValidator.getMinWeightMagnitude());

                        synchronized (recentSeenBytes) {
                            recentSeenBytes.put(digest, receivedTransactionHash);
                        }

                        //if valid - add to receive queue (receivedTransactionViewModel, neighbor)
                        addReceivedDataToReceiveQueue(receivedTransactionViewModel, neighbor);

                    }

                } catch (NoSuchAlgorithmException e) {
                    log.error("MessageDigest: " + e);
                } catch (final TransactionValidator.StaleTimestampException e) {
                    log.debug(e.getMessage());
                    try {
                        transactionRequester.clearTransactionRequest(receivedTransactionHash);
                    } catch (Exception e1) {
                        log.error(e1.getMessage());
                    }
                    neighbor.incInvalidTransactions();
                } catch (final RuntimeException e) {
                    log.error(e.getMessage());
                    log.error("Received an Invalid TransactionViewModel. Dropping it...");
                    neighbor.incInvalidTransactions();
                    break;
                }

                //Request bytes

                //add request to reply queue (requestedHash, neighbor)
                Hash requestedHash = HashFactory.TRANSACTION.create(receivedData, TransactionViewModel.SIZE, reqHashSize);
                if (requestedHash.equals(receivedTransactionHash)) {
                    //requesting a random tip
                    requestedHash = Hash.NULL_HASH;
                }

                addReceivedDataToReplyQueue(requestedHash, neighbor);

                //recentSeenBytes statistics

                if (log.isDebugEnabled()) {
                    long hitCount, missCount;
                    if (cached) {
                        hitCount = recentSeenBytesHitCount.incrementAndGet();
                        missCount = recentSeenBytesMissCount.get();
                    } else {
                        hitCount = recentSeenBytesHitCount.get();
                        missCount = recentSeenBytesMissCount.incrementAndGet();
                    }
                    if (((hitCount + missCount) % 50000L == 0)) {
                        log.info("RecentSeenBytes cache hit/miss ratio: " + hitCount + "/" + missCount);
                        messageQ.publish("hmr %d/%d", hitCount, missCount);
                        recentSeenBytesMissCount.set(0L);
                        recentSeenBytesHitCount.set(0L);
                    }
                }

                break;
            }
        }

        if (!addressMatch && configuration.isTestnet()) {
            int maxPeersAllowed = configuration.getMaxPeers();
            String uriString = uriScheme + ":/" + senderAddress.toString();
            if (Neighbor.getNumPeers() < maxPeersAllowed) {
                log.info("Adding non-tethered neighbor: " + uriString);
                messageQ.publish("antn %s", uriString);
                try {
                    final URI uri = new URI(uriString);
                    // 3rd parameter false (not tcp), 4th parameter true (configured tethering)
                    final Neighbor newneighbor = newNeighbor(uri, false);
                    if (!getNeighbors().contains(newneighbor)) {
                        getNeighbors().add(newneighbor);
                        Neighbor.incNumPeers();
                    }
                } catch (URISyntaxException e) {
                    log.error("Invalid URI string: " + uriString);
                }
            } else {
                if (rejectedAddresses.size() > 20) {
                    // Avoid ever growing list in case of an attack.
                    rejectedAddresses.clear();
                } else if (rejectedAddresses.add(uriString)) {
                    messageQ.publish("rntn %s %s", uriString, String.valueOf(maxPeersAllowed));
                    log.info("Refused non-tethered neighbor: " + uriString +
                            " (max-peers = " + String.valueOf(maxPeersAllowed) + ")");
                }
            }
        }
    }

    public void addReceivedDataToReceiveQueue(TransactionViewModel receivedTransactionViewModel, Neighbor neighbor) {
        receiveQueue.add(new ImmutablePair<>(receivedTransactionViewModel, neighbor));
        if (receiveQueue.size() > RECV_QUEUE_SIZE) {
            receiveQueue.pollLast();
        }

    }

    public void addReceivedDataToReplyQueue(Hash requestedHash, Neighbor neighbor) {
        replyQueue.add(new ImmutablePair<>(requestedHash, neighbor));
        if (replyQueue.size() > REPLY_QUEUE_SIZE) {
            replyQueue.pollLast();
        }
    }


    public void processReceivedDataFromQueue() {
        final Pair<TransactionViewModel, Neighbor> receivedData = receiveQueue.pollFirst();
        if (receivedData != null) {
            processReceivedData(receivedData.getLeft(), receivedData.getRight());
        }
    }

    public void replyToRequestFromQueue() {
        final Pair<Hash, Neighbor> receivedData = replyQueue.pollFirst();
        if (receivedData != null) {
            replyToRequest(receivedData.getLeft(), receivedData.getRight());
        }
    }

    public void processReceivedData(TransactionViewModel receivedTransactionViewModel, Neighbor neighbor) {

        boolean stored = false;

        //store new transaction
        try {
            stored = receivedTransactionViewModel.store(tangle);
        } catch (Exception e) {
            log.error("Error accessing persistence store.", e);
            neighbor.incInvalidTransactions();
        }

        //if new, then broadcast to all neighbors
        if (stored) {
            receivedTransactionViewModel.setArrivalTime(System.currentTimeMillis());
            try {
                transactionValidator.updateStatus(receivedTransactionViewModel);
                receivedTransactionViewModel.updateSender(neighbor.getAddress().toString());
                receivedTransactionViewModel.update(tangle, "arrivalTime|sender");
            } catch (Exception e) {
                log.error("Error updating transactions.", e);
            }
            neighbor.incNewTransactions();
            broadcast(receivedTransactionViewModel);
        }

    }

    public void replyToRequest(Hash requestedHash, Neighbor neighbor) {

        TransactionViewModel transactionViewModel = null;
        Hash transactionPointer;

        //retrieve requested transaction
        if (requestedHash.equals(Hash.NULL_HASH)) {
            //Random Tip Request
            try {
                if (transactionRequester.numberOfTransactionsToRequest() > 0
                        && rnd.nextDouble() < configuration.getpReplyRandomTip()) {
                    neighbor.incRandomTransactionRequests();
                    transactionPointer = getRandomTipPointer();
                    transactionViewModel = TransactionViewModel.fromHash(tangle, transactionPointer);
                } else {
                    //no tx to request, so no random tip will be sent as a reply.
                    return;
                }
            } catch (Exception e) {
                log.error("Error getting random tip.", e);
            }
        } else {
            //find requested trytes
            try {
                //transactionViewModel = TransactionViewModel.find(Arrays.copyOf(requestedHash.bytes(), TransactionRequester.REQUEST_HASH_SIZE));
                transactionViewModel = TransactionViewModel.fromHash(tangle, HashFactory.TRANSACTION.create(requestedHash.bytes(), 0, reqHashSize));
                //log.debug("Requested Hash: " + requestedHash + " \nFound: " + transactionViewModel.getHash());
            } catch (Exception e) {
                log.error("Error while searching for transaction.", e);
            }
        }

        if (transactionViewModel != null && transactionViewModel.getType() == TransactionViewModel.FILLED_SLOT) {
            //send trytes back to neighbor
            try {
                sendPacket(sendingPacket, transactionViewModel, neighbor);

                ByteBuffer digest = getBytesDigest(transactionViewModel.getBytes());
                synchronized (recentSeenBytes) {
                    recentSeenBytes.put(digest, transactionViewModel.getHash());
                }
            } catch (Exception e) {
                log.error("Error fetching transaction to request.", e);
            }
        } else {
            //trytes not found
            if (!requestedHash.equals(Hash.NULL_HASH) && rnd.nextDouble() < configuration.getpPropagateRequest()) {
                //request is an actual transaction and missing in request queue add it.
                try {
                    transactionRequester.requestTransaction(requestedHash, false);

                } catch (Exception e) {
                    log.error("Error adding transaction to request.", e);
                }

            }
        }

    }

    private Hash getRandomTipPointer() throws Exception {
        Hash tip = rnd.nextDouble() < configuration.getpSendMilestone() ? milestoneTracker.latestMilestone : tipsViewModel.getRandomSolidTipHash();
        return tip == null ? Hash.NULL_HASH : tip;
    }

    public void sendPacket(DatagramPacket sendingPacket, TransactionViewModel transactionViewModel, Neighbor neighbor) throws Exception {

        //limit amount of sends per second
        long now = System.currentTimeMillis();
        if ((now - sendPacketsTimer.get()) > 1000L) {
            //reset counter every second
            sendPacketsCounter.set(0);
            sendPacketsTimer.set(now);
        }
        if (sendLimit >= 0 && sendPacketsCounter.get() > sendLimit) {
            //if exceeded limit - don't send
            //log.info("exceeded limit - don't send - {}",sendPacketsCounter.get());
            return;
        }

        synchronized (sendingPacket) {
            System.arraycopy(transactionViewModel.getBytes(), 0, sendingPacket.getData(), 0, TransactionViewModel.SIZE);
            Hash hash = transactionRequester.transactionToRequest(rnd.nextDouble() < configuration.getpSelectMilestoneChild());
            System.arraycopy(hash != null ? hash.bytes() : transactionViewModel.getHash().bytes(), 0,
                    sendingPacket.getData(), TransactionViewModel.SIZE, reqHashSize);
            neighbor.send(sendingPacket);
        }

        sendPacketsCounter.getAndIncrement();
    }

    private Runnable spawnBroadcasterThread() {
        return () -> {

            log.info("Spawning Broadcaster Thread");

            while (!shuttingDown.get()) {

                try {
                    final TransactionViewModel transactionViewModel = broadcastQueue.pollFirst();
                    if (transactionViewModel != null) {

                        for (final Neighbor neighbor : neighbors) {
                            try {
                                sendPacket(sendingPacket, transactionViewModel, neighbor);
                            } catch (final Exception e) {
                                // ignore
                            }
                        }
                    }
                    Thread.sleep(PAUSE_BETWEEN_TRANSACTIONS);
                } catch (final Exception e) {
                    log.error("Broadcaster Thread Exception:", e);
                }
            }
            log.info("Shutting down Broadcaster Thread");
        };
    }

    private Runnable spawnTipRequesterThread() {
        return () -> {

            log.info("Spawning Tips Requester Thread");
            long lastTime = 0;
            while (!shuttingDown.get()) {

                try {
                    final TransactionViewModel transactionViewModel = TransactionViewModel.fromHash(tangle, milestoneTracker.latestMilestone);
                    System.arraycopy(transactionViewModel.getBytes(), 0, tipRequestingPacket.getData(), 0, TransactionViewModel.SIZE);
                    System.arraycopy(transactionViewModel.getHash().bytes(), 0, tipRequestingPacket.getData(), TransactionViewModel.SIZE,
                           reqHashSize);
                    //Hash.SIZE_IN_BYTES);

                    neighbors.forEach(n -> n.send(tipRequestingPacket));

                    long now = System.currentTimeMillis();
                    if ((now - lastTime) > 10000L) {
                        lastTime = now;
                        messageQ.publish("rstat %d %d %d %d %d",
                                getReceiveQueueSize(), getBroadcastQueueSize(),
                                transactionRequester.numberOfTransactionsToRequest(), getReplyQueueSize(),
                                TransactionViewModel.getNumberOfStoredTransactions(tangle));
                        log.info("toProcess = {} , toBroadcast = {} , toRequest = {} , toReply = {} / totalTransactions = {}",
                                getReceiveQueueSize(), getBroadcastQueueSize(),
                                transactionRequester.numberOfTransactionsToRequest(), getReplyQueueSize(),
                                TransactionViewModel.getNumberOfStoredTransactions(tangle));
                    }

                    Thread.sleep(5000);
                } catch (final Exception e) {
                    log.error("Tips Requester Thread Exception:", e);
                }
            }
            log.info("Shutting down Requester Thread");
        };
    }

    private Runnable spawnProcessReceivedThread() {
        return () -> {

            log.info("Spawning Process Received Data Thread");

            while (!shuttingDown.get()) {

                try {
                    processReceivedDataFromQueue();
                    Thread.sleep(1);
                } catch (final Exception e) {
                    log.error("Process Received Data Thread Exception:", e);
                }
            }
            log.info("Shutting down Process Received Data Thread");
        };
    }

    private Runnable spawnReplyToRequestThread() {
        return () -> {

            log.info("Spawning Reply To Request Thread");

            while (!shuttingDown.get()) {

                try {
                    replyToRequestFromQueue();
                    Thread.sleep(1);
                } catch (final Exception e) {
                    log.error("Reply To Request Thread Exception:", e);
                }
            }
            log.info("Shutting down Reply To Request Thread");
        };
    }


    private static ConcurrentSkipListSet<TransactionViewModel> weightQueue() {
        return new ConcurrentSkipListSet<>((transaction1, transaction2) -> {
            if (transaction1.weightMagnitude == transaction2.weightMagnitude) {
                for (int i = Hash.SIZE_IN_BYTES; i-- > 0; ) {
                    if (transaction1.getHash().bytes()[i] != transaction2.getHash().bytes()[i]) {
                        return transaction2.getHash().bytes()[i] - transaction1.getHash().bytes()[i];
                    }
                }
                return 0;
            }
            return transaction2.weightMagnitude - transaction1.weightMagnitude;
        });
    }

    //TODO generalize these weightQueues
    private static ConcurrentSkipListSet<Pair<Hash, Neighbor>> weightQueueHashPair() {
        return new ConcurrentSkipListSet<Pair<Hash, Neighbor>>((transaction1, transaction2) -> {
            Hash tx1 = transaction1.getLeft();
            Hash tx2 = transaction2.getLeft();

            for (int i = Hash.SIZE_IN_BYTES; i-- > 0; ) {
                if (tx1.bytes()[i] != tx2.bytes()[i]) {
                    return tx2.bytes()[i] - tx1.bytes()[i];
                }
            }
            return 0;

        });
    }

    private static ConcurrentSkipListSet<Pair<TransactionViewModel, Neighbor>> weightQueueTxPair() {
        return new ConcurrentSkipListSet<Pair<TransactionViewModel, Neighbor>>((transaction1, transaction2) -> {
            TransactionViewModel tx1 = transaction1.getLeft();
            TransactionViewModel tx2 = transaction2.getLeft();

            if (tx1.weightMagnitude == tx2.weightMagnitude) {
                for (int i = Hash.SIZE_IN_BYTES; i-- > 0; ) {
                    if (tx1.getHash().bytes()[i] != tx2.getHash().bytes()[i]) {
                        return tx2.getHash().bytes()[i] - tx1.getHash().bytes()[i];
                    }
                }
                return 0;
            }
            return tx2.weightMagnitude - tx1.weightMagnitude;
        });
    }


    public void broadcast(final TransactionViewModel transactionViewModel) {
        broadcastQueue.add(transactionViewModel);
        if (broadcastQueue.size() > BROADCAST_QUEUE_SIZE) {
            broadcastQueue.pollLast();
        }
    }

    public void shutdown() throws InterruptedException {
        shuttingDown.set(true);
        executor.awaitTermination(6, TimeUnit.SECONDS);
    }

    private ByteBuffer getBytesDigest(byte[] receivedData) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(receivedData, 0, TransactionViewModel.SIZE);
        return ByteBuffer.wrap(digest.digest());
    }

    // helpers methods

    public boolean removeNeighbor(final URI uri, boolean isConfigured) {
        final Neighbor neighbor = newNeighbor(uri, isConfigured);
        if (uri.getScheme().equals("tcp")) {
            neighbors.stream().filter(n -> n instanceof TCPNeighbor)
                    .map(n -> ((TCPNeighbor) n))
                    .filter(n -> n.equals(neighbor))
                    .forEach(TCPNeighbor::clear);
        }
        return neighbors.remove(neighbor);
    }

    public boolean addNeighbor(Neighbor neighbor) {
        return !getNeighbors().contains(neighbor) && getNeighbors().add(neighbor);
    }

    public boolean isUriValid(final URI uri) {
        if (uri != null) {
            if (uri.getScheme().equals("tcp") || uri.getScheme().equals("udp")) {
                if ((new InetSocketAddress(uri.getHost(), uri.getPort()).getAddress() != null)) {
                    return true;
                }
            }
            log.error("'{}' is not a valid uri schema or resolvable address.", uri);
            return false;
        }
        log.error("Cannot read uri schema, please check neighbor config!");
        return false;
    }

    public Neighbor newNeighbor(final URI uri, boolean isConfigured) {
        if (isUriValid(uri)) {
            if (uri.getScheme().equals("tcp")) {
                return new TCPNeighbor(new InetSocketAddress(uri.getHost(), uri.getPort()), isConfigured);
            }
            if (uri.getScheme().equals("udp")) {
                return new UDPNeighbor(new InetSocketAddress(uri.getHost(), uri.getPort()), udpSocket, isConfigured);
            }
        }
        throw new RuntimeException(uri.toString());
    }

    public static Optional<URI> uri(final String uri) {
        try {
            return Optional.of(new URI(uri));
        } catch (URISyntaxException e) {
            log.error("Uri {} raised URI Syntax Exception", uri);
        }
        return Optional.empty();
    }

    private void parseNeighborsConfig() {
        configuration.getNeighbors().stream().distinct()
                .filter(s -> !s.isEmpty())
                .map(Node::uri).map(Optional::get)
                .filter(u -> isUriValid(u))
                .map(u -> newNeighbor(u, true))
                .peek(u -> {
                    log.info("-> Adding neighbor : {} ", u.getAddress());
                    messageQ.publish("-> Adding Neighbor : %s", u.getAddress());
                }).forEach(neighbors::add);
    }

    public int queuedTransactionsSize() {
        return broadcastQueue.size();
    }

    public int howManyNeighbors() {
        return getNeighbors().size();
    }

    public List<Neighbor> getNeighbors() {
        return neighbors;
    }

    public int getBroadcastQueueSize() {
        return broadcastQueue.size();
    }

    public int getReceiveQueueSize() {
        return receiveQueue.size();
    }

    public int getReplyQueueSize() {
        return replyQueue.size();
    }

    public class FIFOCache<K, V> {

        private final int capacity;
        private final double dropRate;
        private LinkedHashMap<K, V> map;
        private final SecureRandom rnd = new SecureRandom();

        public FIFOCache(int capacity, double dropRate) {
            this.capacity = capacity;
            this.dropRate = dropRate;
            this.map = new LinkedHashMap<>();
        }

        public V get(K key) {
            V value = this.map.get(key);
            if (value != null && (rnd.nextDouble() < this.dropRate)) {
                this.map.remove(key);
                return null;
            }
            return value;
        }

        public V put(K key, V value) {
            if (this.map.containsKey(key)) {
                return value;
            }
            if (this.map.size() >= this.capacity) {
                Iterator<K> it = this.map.keySet().iterator();
                it.next();
                it.remove();
            }
            return this.map.put(key, value);
        }
    }

}
