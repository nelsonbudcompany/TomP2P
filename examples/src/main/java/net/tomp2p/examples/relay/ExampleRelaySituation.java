package net.tomp2p.examples.relay;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;

import net.tomp2p.connection.ChannelServerConfiguration;
import net.tomp2p.connection.Ports;
import net.tomp2p.dht.PeerBuilderDHT;
import net.tomp2p.dht.PeerDHT;
import net.tomp2p.futures.BaseFuture;
import net.tomp2p.nat.FutureRelayNAT;
import net.tomp2p.nat.PeerBuilderNAT;
import net.tomp2p.nat.PeerNAT;
import net.tomp2p.p2p.Peer;
import net.tomp2p.p2p.PeerBuilder;
import net.tomp2p.p2p.builder.BootstrapBuilder;
import net.tomp2p.peers.Number160;
import net.tomp2p.peers.Number640;
import net.tomp2p.peers.PeerAddress;
import net.tomp2p.relay.RelayConfig;
import net.tomp2p.relay.RelayType;
import net.tomp2p.relay.android.MessageBufferConfiguration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts three kinds of Peers (in this order):
 * <ul>
 * <li>Relay Node(s)</li>
 * <li>Firewalled Node(s)</li>
 * <li>Query Node(s)</li>
 * </ul>
 * 
 * The firewalled nodes are relayed by the relay nodes. The query nodes try to make put / get / remove
 * requests on these nodes.
 * 
 * The Google Cloud Messaging Authentication Key is required as argument when using {@link RelayConfig#ANDROID}
 * 
 * @author Nico Rutishauser
 * 
 */
public class ExampleRelaySituation {

	private static final Logger LOG = LoggerFactory.getLogger(ExampleRelaySituation.class);

	private static final int NUM_RELAY_PEERS = 1;
	private static final int NUM_MOBILE_PEERS = 0;
	private static final int NUM_QUERY_PEERS = 3;

	public static void main(String[] args) throws IOException, InterruptedException {
		String gcmKey = null;
		if (args.length < 1) {
			LOG.warn("Need the GCM Authentication key as arguments when using with Android.");
		} else {
			LOG.debug("{} is the GCM key used", args[0]);
			gcmKey = args[0];
		}

		// setup relay peers and start querying
		ExampleRelaySituation situation = new ExampleRelaySituation(NUM_RELAY_PEERS, NUM_MOBILE_PEERS, NUM_QUERY_PEERS,
				gcmKey);
		situation.setupPeers();

		// wait for finding each other
		Thread.sleep(5000);
		situation.startQueries();
	}

	/**
	 * Port configuration
	 */
	private static final int RELAY_START_PORT = 4000;
	private static final int MOBILE_START_PORT = 8000;
	private static final int QUERY_START_PORT = 6000;

	/**
	 * Relay buffer configuration
	 */
	private static final int MAX_MESSAGE_NUM = 10;
	private static final long MAX_BUFFER_SIZE = Long.MAX_VALUE;
	private static final long MAX_BUFFER_AGE = 10 * 1000;
	private static final int GCM_SEND_RETIES = 5;
	
	/**
	 * Unreachable peer configuration
	 */
	private static final String GCM_REGISTRATION_ID = "abc";
	private static final RelayType RELAY_TYPE = RelayType.ANDROID;
	private final String gcmKey;

	/**
	 * Query peer configuration
	 */
	private static final long MEDIUM_SLEEP_TIME_MS = 5000;
	private static final int MEDIUM_DATA_SIZE_BYTES = 1024;

	/**
	 * Number of peers of each kind
	 */
	private final int relayPeers;
	private final int mobilePeers;
	private final int queryPeers;

	/**
	 * Holds active peers by each kind
	 */
	private final List<PeerNAT> relays;
	private final List<PeerNAT> mobiles;
	private final List<QueryNode> queries;

	private final MessageBufferConfiguration bufferConfig;


	public ExampleRelaySituation(int relayPeers, int mobilePeers, int queryPeers, String gcmKey) {
		this.gcmKey = gcmKey;
		assert relayPeers > 0 && relayPeers <= RELAY_TYPE.maxRelayCount();
		this.relayPeers = relayPeers;
		this.mobilePeers = mobilePeers;
		this.queryPeers = queryPeers;

		this.relays = new ArrayList<PeerNAT>(relayPeers);
		this.mobiles = new ArrayList<PeerNAT>(mobilePeers);
		this.queries = new ArrayList<QueryNode>(queryPeers);
		
		bufferConfig = new MessageBufferConfiguration().bufferAgeLimit(MAX_BUFFER_AGE).bufferCountLimit(MAX_MESSAGE_NUM)
				.bufferSizeLimit(MAX_BUFFER_SIZE);
	}

	public void setupPeers() throws IOException {
		/**
		 * Init the relay peers first
		 */
		for (int i = 0; i < relayPeers; i++) {
			Peer peer = createPeer(RELAY_START_PORT + i);
			// Note: Does not work if relay does not have a PeerDHT
			new PeerBuilderDHT(peer).storageLayer(new LoggingStorageLayer("RELAY", false)).start();
			PeerNAT peerNAT = new PeerBuilderNAT(peer).bufferConfiguration(bufferConfig).gcmAuthenticationKey(gcmKey).gcmSendRetries(GCM_SEND_RETIES).start();

			relays.add(peerNAT);
			LOG.debug("Relay peer {} started", i);
		}
		
		/**
		 * Then init the mobile peers
		 */
		for (int i = 0; i < mobilePeers; i++) {
			Peer peer = createPeer(MOBILE_START_PORT + i);
			bootstrap(peer);

			// start DHT capability
			new PeerBuilderDHT(peer).storageLayer(new LoggingStorageLayer("UNREACHABLE", true)).start();

			LOG.debug("Connecting to Relay now");
			Set<PeerAddress> relayAddresses = new HashSet<PeerAddress>(relayPeers);
			for (PeerNAT relay : relays) {
				relayAddresses.add(relay.peer().peerAddress());
			}

			RelayConfig config;
			PeerBuilderNAT builder = new PeerBuilderNAT(peer);
			if (RELAY_TYPE == RelayType.ANDROID) {
				config = RelayConfig.Android(GCM_REGISTRATION_ID).manualRelays(relayAddresses);
			} else {
				config = RelayConfig.OpenTCP();
			}

			PeerNAT peerNat = builder.start();
			FutureRelayNAT futureRelayNAT = peerNat.startRelay(config, relays.get(0).peer().peerAddress()).awaitUninterruptibly();
			if (!futureRelayNAT.isSuccess()) {
				LOG.error("Cannot connect to Relay(s). Reason: {}", futureRelayNAT.failedReason());
				return;
			}

			mobiles.add(peerNat);
			LOG.debug("Mobile peer {} connected to DHT and Relay(s)", i);
		}

		/**
		 * Finally init the query peers
		 */
		for (int i = 0; i < queryPeers; i++) {
			Peer peer = createPeer(QUERY_START_PORT + i);
			bootstrap(peer);

			PeerDHT peerDHT = new PeerBuilderDHT(peer).storageLayer(new LoggingStorageLayer("QUERY", false)).start();
			new PeerBuilderNAT(peer).start();

			queries.add(new QueryNode(peerDHT, MEDIUM_SLEEP_TIME_MS, MEDIUM_DATA_SIZE_BYTES));
			LOG.debug("Query peer {} started", i);
		}
		
	}

	private Peer createPeer(int port) throws IOException {
		ChannelServerConfiguration csc = PeerBuilder.createDefaultChannelServerConfiguration();
		csc.ports(new Ports(port, port));
		csc.portsForwarding(new Ports(port, port));
		csc.connectionTimeoutTCPMillis(10 * 1000);
		csc.idleTCPSeconds(10);
		csc.idleUDPSeconds(10);
		
		return new PeerBuilder(new Number160(port)).ports(port).channelServerConfiguration(csc).start();
	}

	private boolean bootstrap(Peer peer) throws UnknownHostException {
		// bootstrap
		InetAddress bootstrapTo = InetAddress.getLocalHost();
		BootstrapBuilder bootstrapBuilder = peer.bootstrap().inetAddress(bootstrapTo).ports(RELAY_START_PORT);
		BaseFuture bootstrap = bootstrapBuilder.start().awaitUninterruptibly();

		if (bootstrap == null) {
			LOG.error("Cannot bootstrap");
			return false;
		} else if (bootstrap.isFailed()) {
			LOG.error("Cannot bootstrap. Reason: {}", bootstrap.failedReason());
			return false;
		}

		LOG.debug("Peer has bootstrapped");
		return true;
	}

	public void startQueries() {
		for (final QueryNode queryPeer : queries) {
			new Thread(new Runnable() {
				@Override
				public void run() {
					try {
						queryPeer.querySpecific(new Number640(getRandomUnreachable().peerId(), Number160.ZERO,
								new Number160(new Random()), Number160.ZERO));
					} catch (Exception e) {
						LOG.error("Cannot put / get / remove", e);
					}
				}
			}, queryPeer.toString()).start();
		}
	}

	private PeerAddress getRandomUnreachable() {
		Random rnd = new Random();
		if (mobiles.isEmpty()) {
			List<PeerNAT> relayCopy = new ArrayList<PeerNAT>(relays);
			while (!relayCopy.isEmpty()) {
				PeerNAT relay = relayCopy.remove(rnd.nextInt(relayCopy.size()));
				Set<PeerAddress> unreachablePeers = relay.relayRPC().unreachablePeers();
				Iterator<PeerAddress> iterator = unreachablePeers.iterator();
				if (iterator.hasNext()) {
					return iterator.next();
				}
			}
			// no connected unreachable peer found
			return null;
		} else {
			return mobiles.get(rnd.nextInt(mobilePeers)).peer().peerAddress();
		}
	}
}
