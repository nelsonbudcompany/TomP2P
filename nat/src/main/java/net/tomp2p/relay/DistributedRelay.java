package net.tomp2p.relay;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReferenceArray;

import net.tomp2p.connection.ConnectionConfiguration;
import net.tomp2p.connection.PeerConnection;
import net.tomp2p.futures.BaseFutureAdapter;
import net.tomp2p.futures.FutureDone;
import net.tomp2p.futures.FutureForkJoin;
import net.tomp2p.futures.FuturePeerConnection;
import net.tomp2p.futures.FutureResponse;
import net.tomp2p.message.Message;
import net.tomp2p.message.Message.Type;
import net.tomp2p.message.NeighborSet;
import net.tomp2p.p2p.Peer;
import net.tomp2p.peers.PeerAddress;
import net.tomp2p.peers.PeerSocketAddress;
import net.tomp2p.relay.android.AndroidRelayConnection;
import net.tomp2p.relay.android.MessageBuffer;
import net.tomp2p.relay.android.MessageBufferListener;
import net.tomp2p.relay.android.gcm.GCMMessageHandler;
import net.tomp2p.relay.tcp.OpenTCPRelayConnection;
import net.tomp2p.rpc.RPC;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The relay manager is responsible for setting up and maintaining connections
 * to relay peers and contains all information about the relays.
 * 
 * @author Raphael Voellmy
 * @author Thomas Bocek
 * @author Nico Rutishauser
 * 
 */
public class DistributedRelay implements GCMMessageHandler {

	private final static Logger LOG = LoggerFactory.getLogger(DistributedRelay.class);

	private final Peer peer;
	private final RelayRPC relayRPC;
	private final ConnectionConfiguration config;

	private final List<BaseRelayConnection> relays;
	private final Set<PeerAddress> failedRelays;

	private final Collection<RelayListener> relayListeners;
	private final RelayConfig relayConfig;

	// optional field, only when an android device and buffering is requested
	private MessageBuffer<String> gcmBuffer;

	/**
	 * @param peer
	 *            the unreachable peer
	 * @param relayRPC
	 *            the relay RPC
	 * @param maxFail 
	 * @param relayConfig 
	 * @param maxRelays
	 *            maximum number of relay peers to set up
	 * @param relayType
	 *            the kind of the relay connection
	 */
	public DistributedRelay(final Peer peer, RelayRPC relayRPC, ConnectionConfiguration config, RelayConfig relayConfig) {
		this.peer = peer;
		this.relayRPC = relayRPC;
		this.config = config;
		this.relayConfig = relayConfig;

		relays = Collections.synchronizedList(new ArrayList<BaseRelayConnection>());
		failedRelays = new ConcurrentCacheSet<PeerAddress>(relayConfig.failedRelayWaitTime());
		relayListeners = Collections.synchronizedList(new ArrayList<RelayListener>(1));
		
		// buffering is currently only allowed at Android devices
		if(relayConfig.type() == RelayType.ANDROID && relayConfig.bufferConfiguration() != null) {
			gcmBuffer = new MessageBuffer<String>(relayConfig.bufferConfiguration());
			gcmBuffer.addListener(new MessageBufferListener<String>() {
				
				@Override
				public void bufferFull(List<String> messages) {
					Set<String> relayIds = new HashSet<String>(messages);
					for (String peerId : relayIds) {
						sendBufferRequest(peerId);
					}
				}
			});
		}
	}

	public RelayConfig relayConfig() {
		return relayConfig;
	}

	/**
	 * Returns connections to current relay peers
	 * 
	 * @return List of PeerAddresses of the relay peers (copy)
	 */
	public List<BaseRelayConnection> relays() {
		synchronized (relays) {
			// make a copy
			return Collections.unmodifiableList(new ArrayList<BaseRelayConnection>(relays));
		}
	}

	public void addRelayListener(RelayListener relayListener) {
		synchronized (relayListeners) {
			relayListeners.add(relayListener);
		}
	}

	public FutureForkJoin<FutureDone<Void>> shutdown() {
		final AtomicReferenceArray<FutureDone<Void>> futureDones;
		synchronized (relays) {
			futureDones = new AtomicReferenceArray<FutureDone<Void>>(relays.size());
			for (int i = 0; i < relays.size(); i++) {
				futureDones.set(i, relays.get(i).shutdown());
			}
		}

		synchronized (relayListeners) {
			relayListeners.clear();
		}

		return new FutureForkJoin<FutureDone<Void>>(futureDones);
	}

	/**
	 * Sets up relay connections to other peers. The number of relays to set up
	 * is determined by {@link PeerAddress#MAX_RELAYS} or passed to the
	 * constructor of this class. It is important that we call this after we
	 * bootstrapped and have recent information in our peer map.
	 * 
	 * @return RelayFuture containing a {@link DistributedRelay} instance
	 */
	public FutureRelay setupRelays(final FutureRelay futureRelay) {
		final List<PeerAddress> relayCandidates;
		if (relayConfig.manualRelays().isEmpty()) {
			// Get the neighbors of this peer that could possibly act as relays. Relay
			// candidates are neighboring peers that are not relayed themselves and have
			// not recently failed as relay or denied acting as relay.
			relayCandidates = peer.distributedRouting().peerMap().all();
			// remove those who we know have failed
			relayCandidates.removeAll(failedRelays);
		} else {
			// if the user sets manual relays, the failed relays are not removed, as this has to be done by
			// the user
			relayCandidates = new ArrayList<PeerAddress>(relayConfig.manualRelays());
		}

		filterRelayCandidates(relayCandidates);
		setupPeerConnections(futureRelay, relayCandidates);
		return futureRelay;
	}

	/**
	 * Remove recently failed relays, peers that are relayed themselves and
	 * peers that are already relays
	 */
	private void filterRelayCandidates(Collection<PeerAddress> relayCandidates) {
		for (Iterator<PeerAddress> iterator = relayCandidates.iterator(); iterator.hasNext();) {
			PeerAddress pa = iterator.next();

			// filter peers that are relayed themselves
			if (pa.isRelayed()) {
				iterator.remove();
				continue;
			}

			// filter relays that are already connected
			synchronized (relays) {
				for (BaseRelayConnection relay : relays) {
					if (relay.relayAddress().equals(pa)) {
						iterator.remove();
						break;
					}
				}
			}
		}

		LOG.trace("Found {} addtional relay candidates", relayCandidates.size());
	}

	/**
	 * Sets up N peer connections to relay candidates, where N is maxRelays
	 * minus the current relay count.

	 * @return FutureDone
	 */
	private void setupPeerConnections(final FutureRelay futureRelay, List<PeerAddress> relayCandidates) {
		final int nrOfRelays = Math.min(relayConfig.type().maxRelayCount() - relays.size(), relayCandidates.size());
		if (nrOfRelays > 0) {
			LOG.debug("Setting up {} relays", nrOfRelays);

			@SuppressWarnings("unchecked")
			FutureDone<PeerConnection>[] futureDones = new FutureDone[nrOfRelays];
			AtomicReferenceArray<FutureDone<PeerConnection>> relayConnectionFutures = new AtomicReferenceArray<FutureDone<PeerConnection>>(
					futureDones);
			setupPeerConnectionsRecursive(relayConnectionFutures, relayCandidates, nrOfRelays, futureRelay, 0, new StringBuilder());
		} else {
			if (relayCandidates.isEmpty()) {
				// no candidates
				futureRelay.failed("done");
			} else {
				// nothing to do
				futureRelay.done(Collections.<BaseRelayConnection> emptyList());
			}
		}
	}

	/**
	 * Sets up connections to relay peers recursively. If the maximum number of
	 * relays is already reached, this method will do nothing.
	 * 
	 * @param futureRelayConnections
	 * @param relayCandidates
	 *            List of peers that could act as relays
	 * @param numberOfRelays
	 *            The number of relays to establish.
	 * @param futureDone
	 * @return
	 * @throws InterruptedException
	 */
	private void setupPeerConnectionsRecursive(final AtomicReferenceArray<FutureDone<PeerConnection>> futures,
			final List<PeerAddress> relayCandidates, final int numberOfRelays, final FutureRelay futureRelay,
			final int fail, final StringBuilder status) {
		int active = 0;
		for (int i = 0; i < numberOfRelays; i++) {
			if (futures.get(i) == null) {
				PeerAddress candidate = null;
				synchronized (relayCandidates) {
					if (!relayCandidates.isEmpty()) {
						candidate = relayCandidates.remove(0);
					}
				}
				if (candidate != null) {
					// contact the candiate and ask for being my relay
					FutureDone<PeerConnection> futureDone = sendMessage(candidate);
					futures.set(i, futureDone);
					active++;
				}
			} else {
				active++;
			}
		}
		if (active == 0) {
			updatePeerAddress();
			futureRelay.failed("No candidates: " + status.toString());
			return;
		} else if (fail > relayConfig.maxFail()) {
			updatePeerAddress();
			futureRelay.failed("Maxfail: " + status.toString());
			return;
		}

		FutureForkJoin<FutureDone<PeerConnection>> ffj = new FutureForkJoin<FutureDone<PeerConnection>>(active, false,
				futures);
		ffj.addListener(new BaseFutureAdapter<FutureForkJoin<FutureDone<PeerConnection>>>() {
			public void operationComplete(FutureForkJoin<FutureDone<PeerConnection>> futureForkJoin) throws Exception {
				if (futureForkJoin.isSuccess()) {
					updatePeerAddress();
					futureRelay.done(relays());
				} else if (!peer.isShutdown()) {
					setupPeerConnectionsRecursive(futures, relayCandidates, numberOfRelays, futureRelay, fail + 1,
							status.append(futureForkJoin.failedReason()).append(" "));
				} else {
					futureRelay.failed(futureForkJoin);
				}
			}
		});
	}

	/**
	 * Send the setup-message to the relay peer. If the peer that is asked to act as
	 * relay is relayed itself, the request will be denied.
	 * 
	 * @param candidate the relay's peer address
	 * @param relayConfig 
	 * @return FutureDone with a peer connection to the newly set up relay peer
	 */
	private FutureDone<PeerConnection> sendMessage(final PeerAddress candidate) {
		final FutureDone<PeerConnection> futureDone = new FutureDone<PeerConnection>();

		final Message message = relayRPC.createMessage(candidate, RPC.Commands.RELAY.getNr(), Type.REQUEST_1);

		// depend on the relay type whether to keep the connection open or close it after the setup.
		message.keepAlive(relayConfig.type().keepConnectionOpen());

		// encode the relay type in the message such that the relay node knows how to handle
		message.intValue(relayConfig.type().ordinal());

		// server credentials only used by Android peers
		if (relayConfig.type() == RelayType.ANDROID) {
			if (relayConfig.registrationId() == null) {
				LOG.error("Registration ID must be provided when using Android mode");
				return futureDone.failed("No GCM registration ID provided");
			} else {
				// add the registration ID, the GCM authentication key and the map update interval
				message.buffer(RelayUtils.encodeString(relayConfig.registrationId()));
				message.intValue(relayConfig.peerMapUpdateInterval());
			}
			
			if(relayConfig.gcmServers() != null && !relayConfig.gcmServers().isEmpty()) {
				// provide gcm servers at startup, later they will be updated using the map update task
				message.neighborsSet(new NeighborSet(-1, relayConfig.gcmServers()));
			}
		}

		LOG.debug("Setting up relay connection to peer {}, message {}", candidate, message);
		final FuturePeerConnection fpc = peer.createPeerConnection(candidate);
		fpc.addListener(new BaseFutureAdapter<FuturePeerConnection>() {
			public void operationComplete(final FuturePeerConnection futurePeerConnection) throws Exception {
				if (futurePeerConnection.isSuccess()) {
					// successfully created a connection to the relay peer
					final PeerConnection peerConnection = futurePeerConnection.object();

					// send the message
					FutureResponse response = RelayUtils.send(peerConnection, peer.peerBean(), peer.connectionBean(),
							config, message);
					response.addListener(new BaseFutureAdapter<FutureResponse>() {
						public void operationComplete(FutureResponse future) throws Exception {
							if (future.isSuccess()) {
								// finialize the relay setup
								setupAddRelays(candidate, peerConnection);
								futureDone.done(peerConnection);
							} else {
								LOG.debug("Peer {} denied relay request", candidate);
								failedRelays.add(candidate);
								futureDone.failed(future);
							}
						}
					});
				} else {
					LOG.debug("Unable to setup a connection to relay peer {}", candidate);
					failedRelays.add(candidate);
					futureDone.failed(futurePeerConnection);
				}
			}
		});

		return futureDone;
	}

	/**
	 * Is called when the setup with the relay worked. Adds the relay to the list.
	 */
	private void setupAddRelays(PeerAddress relayAddress, PeerConnection peerConnection) {
		synchronized (relays) {
			if (relays.size() >= relayConfig.type().maxRelayCount()) {
				LOG.warn("The maximum number ({}) of relays is reached", relayConfig.type().maxRelayCount());
				return;
			}
		}

		BaseRelayConnection connection = null;
		switch (relayConfig.type()) {
			case OPENTCP:
				connection = new OpenTCPRelayConnection(peerConnection, peer, config);
				break;
			case ANDROID:
				connection = new AndroidRelayConnection(relayAddress, relayRPC, peer, config);
				break;
			default:
				LOG.error("Unknown relay type {}", relayConfig);
				return;
		}

		addCloseListener(connection);

		synchronized (relays) {
			LOG.debug("Adding peer {} as a relay", relayAddress);
			relays.add(connection);
		}
	}

	/**
	 * Adds a close listener for an open peer connection, so that if the
	 * connection to the relay peer drops, a new relay is found and a new relay
	 * connection is established
	 * 
	 * @param connection
	 *            the relay connection on which to add a close listener
	 */
	private void addCloseListener(final BaseRelayConnection connection) {
		connection.addCloseListener(new RelayListener() {
			@Override
			public void relayFailed(PeerAddress relayAddress) {
				// used to remove a relay peer from the unreachable peers
				// peer address. It will <strong>not</strong> cut the
				// connection to an existing peer, but only update the
				// unreachable peer's PeerAddress if a relay peer failed.
				// It will also cancel the {@link PeerMapUpdateTask}
				// maintenance task if the last relay is removed.
				relays.remove(connection);
				failedRelays.add(relayAddress);
				updatePeerAddress();

				synchronized (relayListeners) {
					for (RelayListener relayListener : relayListeners) {
						relayListener.relayFailed(relayAddress);
					}
				}
			}
		});
	}

	/**
	 * Updates the peer's PeerAddress: Adds the relay addresses to the peer
	 * address, updates the firewalled flags, and bootstraps to announce its new
	 * relay peers.
	 */
	private void updatePeerAddress() {
		// add relay addresses to peer address
		boolean hasRelays = !relays.isEmpty();

		Collection<PeerSocketAddress> socketAddresses = new ArrayList<PeerSocketAddress>(relays.size());
		synchronized (relays) {
			for (BaseRelayConnection relay : relays) {
				PeerAddress pa = relay.relayAddress();
				socketAddresses.add(new PeerSocketAddress(pa.inetAddress(), pa.tcpPort(), pa.udpPort()));
			}
		}

		// update firewalled and isRelayed flags
		PeerAddress newAddress = peer.peerAddress().changeFirewalledTCP(!hasRelays).changeFirewalledUDP(!hasRelays)
				.changeRelayed(hasRelays).changePeerSocketAddresses(socketAddresses).changeSlow(hasRelays && relayConfig.type().isSlow());
		peer.peerBean().serverPeerAddress(newAddress);
		LOG.debug("Updated peer address {}, isrelay = {}", newAddress, hasRelays);
	}

	@Override
	public void onGCMMessageArrival(String peerId) {
		if(relayConfig.type() != RelayType.ANDROID) {
			throw new UnsupportedOperationException("Must be of type 'Android' to access this method");
		}
		
		if(gcmBuffer == null) {
			LOG.trace("GCM messages are unbuffered. Ask relay {} for messages now", peerId);
			sendBufferRequest(peerId);
		} else {
			LOG.trace("Add the GCM message into the buffer before processing it.");
			gcmBuffer.addMessage(peerId, 1);
		}
	}
	
	/**
	 * Helper method to find the correct connection for the given peerId and sends the request to get the
	 * buffered messages
	 * 
	 * @param relayPeerId the id of the peer as a String
	 */
	private void sendBufferRequest(String relayPeerId) {
		for (BaseRelayConnection relayConnection : relays()) {
			String peerId = relayConnection.relayAddress().peerId().toString();
			if (peerId.equals(relayPeerId) && relayConnection instanceof AndroidRelayConnection) {
				((AndroidRelayConnection) relayConnection).sendBufferRequest();
				return;
			}
		}

		LOG.warn("No connection to relay {} found. Ignoring the message.", relayPeerId);
	}
}
