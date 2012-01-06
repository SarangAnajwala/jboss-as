/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.ejb3.remote.protocol.versionone;


import org.jboss.as.clustering.ClusterNode;
import org.jboss.as.clustering.GroupMembershipListener;
import org.jboss.as.clustering.GroupMembershipNotifier;
import org.jboss.as.clustering.GroupMembershipNotifierRegistry;
import org.jboss.as.ejb3.deployment.DeploymentModuleIdentifier;
import org.jboss.as.ejb3.deployment.DeploymentRepository;
import org.jboss.as.ejb3.deployment.DeploymentRepositoryListener;
import org.jboss.as.ejb3.deployment.ModuleDeployment;
import org.jboss.as.ejb3.remote.EJBRemoteTransactionsRepository;
import org.jboss.logging.Logger;
import org.jboss.marshalling.MarshallerFactory;
import org.jboss.remoting3.Channel;
import org.jboss.remoting3.CloseHandler;
import org.jboss.remoting3.MessageInputStream;
import org.xnio.IoUtils;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * @author Jaikiran Pai
 */
public class VersionOneProtocolChannelReceiver implements Channel.Receiver, DeploymentRepositoryListener,
        GroupMembershipNotifierRegistry.Listener {

    /**
     * Logger
     */
    private static final Logger logger = Logger.getLogger(VersionOneProtocolChannelReceiver.class);

    private static final byte HEADER_SESSION_OPEN_REQUEST = 0x01;
    private static final byte HEADER_INVOCATION_REQUEST = 0x03;
    private static final byte HEADER_TX_COMMIT_REQUEST = 0x0F;
    private static final byte HEADER_TX_ROLLBACK_REQUEST = 0x10;
    private static final byte HEADER_TX_PREPARE_REQUEST = 0x11;
    private static final byte HEADER_TX_FORGET_REQUEST = 0x12;
    private static final byte HEADER_TX_BEFORE_COMPLETION_REQUEST = 0x13;

    private final Channel channel;
    private final DeploymentRepository deploymentRepository;
    private final EJBRemoteTransactionsRepository transactionsRepository;
    private final MarshallerFactory marshallerFactory;
    private final ExecutorService executorService;
    private final GroupMembershipNotifierRegistry groupMembershipNotifierRegistry;

    public VersionOneProtocolChannelReceiver(final Channel channel, final DeploymentRepository deploymentRepository,
                                             final EJBRemoteTransactionsRepository transactionsRepository, final GroupMembershipNotifierRegistry groupMembershipNotifierRegistry,
                                             final MarshallerFactory marshallerFactory, final ExecutorService executorService) {
        this.marshallerFactory = marshallerFactory;
        this.channel = channel;
        this.executorService = executorService;
        this.deploymentRepository = deploymentRepository;
        this.transactionsRepository = transactionsRepository;
        this.groupMembershipNotifierRegistry = groupMembershipNotifierRegistry;
    }

    public void startReceiving() {
        this.channel.addCloseHandler(new ChannelCloseHandler());

        this.channel.receiveMessage(this);
        // listen to module availability/unavailability events
        this.deploymentRepository.addListener(this);
        // listen to new clusters (a.k.a groups) being started/stopped
        this.groupMembershipNotifierRegistry.addListener(this);
        // Send the cluster topology for existing clusters in the registry
        // and for each of these clusters added ourselves as a listener for cluster
        // topology changes (members added/removed events in the cluster)
        final Iterable<GroupMembershipNotifier> clusters = this.groupMembershipNotifierRegistry.getGroupMembershipNotifiers();
        try {
            this.sendNewClusterFormedMessage(clusters);
        } catch (IOException ioe) {
            // just log and don't throw an error
            logger.warn("Could not send cluster formation message to the client on channel " + channel, ioe);
        }
        for (final GroupMembershipNotifier cluster : clusters) {
            // add the listener
            cluster.registerGroupMembershipListener(new ClusterTopologyUpdateListener(cluster.getGroupName(), this));
        }
    }

    @Override
    public void handleError(Channel channel, IOException error) {
        try {
            channel.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            this.deploymentRepository.removeListener(this);
            this.groupMembershipNotifierRegistry.removeListener(this);
        }
    }

    @Override
    public void handleEnd(Channel channel) {
        try {
            channel.close();
        } catch (IOException e) {
            // ignore
        } finally {
            this.deploymentRepository.removeListener(this);
            this.groupMembershipNotifierRegistry.removeListener(this);
        }
    }

    @Override
    public void handleMessage(Channel channel, MessageInputStream messageInputStream) {
        try {
            // read the first byte to see what type of a message it is
            final int header = messageInputStream.read();
            if (logger.isTraceEnabled()) {
                logger.trace("Got message with header 0x" + Integer.toHexString(header) + " on channel " + channel);
            }
            MessageHandler messageHandler = null;
            switch (header) {
                case HEADER_INVOCATION_REQUEST:
                    messageHandler = new MethodInvocationMessageHandler(this.deploymentRepository, this.marshallerFactory, this.executorService);
                    break;
                case HEADER_SESSION_OPEN_REQUEST:
                    messageHandler = new SessionOpenRequestHandler(this.deploymentRepository, this.marshallerFactory, this.executorService);
                    break;
                case HEADER_TX_COMMIT_REQUEST:
                    messageHandler = new TransactionRequestHandler(this.transactionsRepository, this.marshallerFactory, this.executorService, TransactionRequestHandler.TransactionRequestType.COMMIT);
                    break;
                case HEADER_TX_ROLLBACK_REQUEST:
                    messageHandler = new TransactionRequestHandler(this.transactionsRepository, this.marshallerFactory, this.executorService, TransactionRequestHandler.TransactionRequestType.ROLLBACK);
                    break;
                case HEADER_TX_FORGET_REQUEST:
                    messageHandler = new TransactionRequestHandler(this.transactionsRepository, this.marshallerFactory, this.executorService, TransactionRequestHandler.TransactionRequestType.FORGET);
                    break;
                case HEADER_TX_PREPARE_REQUEST:
                    messageHandler = new TransactionRequestHandler(this.transactionsRepository, this.marshallerFactory, this.executorService, TransactionRequestHandler.TransactionRequestType.PREPARE);
                    break;
                case HEADER_TX_BEFORE_COMPLETION_REQUEST:
                    messageHandler = new TransactionRequestHandler(this.transactionsRepository, this.marshallerFactory, this.executorService, TransactionRequestHandler.TransactionRequestType.BEFORE_COMPLETION);
                    break;
                default:
                    logger.warn("Received unsupported message header 0x" + Integer.toHexString(header) + " on channel " + channel);
                    return;
            }
            // process the message
            messageHandler.processMessage(channel, messageInputStream);
            // enroll for next message (whenever it's available)
            channel.receiveMessage(this);

        } catch (IOException e) {
            // log it
            logger.errorf(e, "Exception on channel %s from message %s", channel, messageInputStream);
            // no more messages can be sent or received on this channel
            IoUtils.safeClose(channel);
        } finally {
            IoUtils.safeClose(messageInputStream);
        }
    }

    @Override
    public void listenerAdded(DeploymentRepository repository) {
        // get the initial available modules and send a message to the client
        final Map<DeploymentModuleIdentifier, ModuleDeployment> availableModules = this.deploymentRepository.getModules();
        if (availableModules != null && !availableModules.isEmpty()) {
            try {
                logger.debug("Sending initial module availabilty message, containing " + availableModules.size() + " module(s) to channel " + this.channel);
                this.sendModuleAvailability(availableModules.keySet().toArray(new DeploymentModuleIdentifier[availableModules.size()]));
            } catch (IOException e) {
                logger.warn("Could not send initial module availability report to channel " + this.channel, e);
            }
        }
    }

    @Override
    public void deploymentAvailable(DeploymentModuleIdentifier deploymentModuleIdentifier, ModuleDeployment moduleDeployment) {
        try {
            this.sendModuleAvailability(new DeploymentModuleIdentifier[]{deploymentModuleIdentifier});
        } catch (IOException e) {
            logger.warn("Could not send module availability notification of module " + deploymentModuleIdentifier + " to channel " + this.channel, e);
        }
    }

    @Override
    public void deploymentRemoved(DeploymentModuleIdentifier deploymentModuleIdentifier) {
        try {
            this.sendModuleUnAvailability(new DeploymentModuleIdentifier[]{deploymentModuleIdentifier});
        } catch (IOException e) {
            logger.warn("Could not send module un-availability notification of module " + deploymentModuleIdentifier + " to channel " + this.channel, e);
        }
    }

    private void sendModuleAvailability(DeploymentModuleIdentifier[] availableModules) throws IOException {
        final DataOutputStream outputStream = new DataOutputStream(this.channel.writeMessage());
        final ModuleAvailabilityWriter moduleAvailabilityWriter = new ModuleAvailabilityWriter();
        try {
            moduleAvailabilityWriter.writeModuleAvailability(outputStream, availableModules);
        } finally {
            outputStream.close();
        }
    }

    private void sendModuleUnAvailability(DeploymentModuleIdentifier[] availableModules) throws IOException {
        final DataOutputStream outputStream = new DataOutputStream(this.channel.writeMessage());
        final ModuleAvailabilityWriter moduleAvailabilityWriter = new ModuleAvailabilityWriter();
        try {
            moduleAvailabilityWriter.writeModuleUnAvailability(outputStream, availableModules);
        } finally {
            outputStream.close();
        }
    }

    @Override
    public void newGroupMembershipNotifierRegistered(final GroupMembershipNotifier groupMembershipNotifier) {
        try {
            logger.debug("Received new cluster formation notification for cluster " + groupMembershipNotifier.getGroupName());
            this.sendNewClusterFormedMessage(groupMembershipNotifier);
        } catch (IOException ioe) {
            logger.warn("Could not send a cluster formation message for cluster: " + groupMembershipNotifier.getGroupName()
                    + " to the client on channel " + channel, ioe);
        }
    }

    @Override
    public void groupMembershipNotifierUnregistered(final GroupMembershipNotifier groupMembershipNotifier) {
        try {
            logger.debug("Received cluster removal notification for cluster " + groupMembershipNotifier.getGroupName());
            this.sendClusterRemovedMessage(groupMembershipNotifier);
        } catch (IOException ioe) {
            logger.warn("Could not send a cluster removal message for cluster: " + groupMembershipNotifier.getGroupName()
                    + " to the client on channel " + channel, ioe);
        }
    }

    private void sendNewClusterFormedMessage(final Iterable<GroupMembershipNotifier> groupMembershipNotifiers) throws IOException {
        final Collection<GroupMembershipNotifier> clusters = new ArrayList<GroupMembershipNotifier>();
        for (final GroupMembershipNotifier cluster : groupMembershipNotifiers) {
            clusters.add(cluster);
        }
        this.sendNewClusterFormedMessage(clusters.toArray(new GroupMembershipNotifier[clusters.size()]));
    }


    /**
     * Sends a cluster formation message for the passed clusters, over the remoting channel
     *
     * @param clusters The new clusters
     * @throws IOException If any exception occurs while sending the message over the channel
     */
    private void sendNewClusterFormedMessage(final GroupMembershipNotifier... clusters) throws IOException {
        final DataOutputStream outputStream = new DataOutputStream(this.channel.writeMessage());
        final ClusterTopologyWriter clusterTopologyWriter = new ClusterTopologyWriter();
        try {
            logger.debug("Writing out cluster formation message for " + clusters.length + " clusters, to channel " + this.channel);
            clusterTopologyWriter.writeCompleteClusterTopology(outputStream, clusters);
        } finally {
            outputStream.close();
        }
    }

    /**
     * Sends out a cluster removal message for the passed cluster, over the remoting channel
     *
     * @param cluster The cluster which was removed
     * @throws IOException If any exception occurs while sending the message over the channel
     */
    private void sendClusterRemovedMessage(final GroupMembershipNotifier cluster) throws IOException {
        final DataOutputStream outputStream = new DataOutputStream(this.channel.writeMessage());
        final ClusterTopologyWriter clusterTopologyWriter = new ClusterTopologyWriter();
        try {
            logger.debug("Cluster " + cluster.getGroupName() + " removed, writing cluster removal message to channel " + this.channel);
            clusterTopologyWriter.writeClusterRemoved(outputStream, cluster);
        } finally {
            outputStream.close();
        }
    }

    private class ChannelCloseHandler implements CloseHandler<Channel> {

        @Override
        public void handleClose(Channel closedChannel, IOException exception) {
            logger.debug("Channel " + closedChannel + " closed");
            VersionOneProtocolChannelReceiver.this.deploymentRepository.removeListener(VersionOneProtocolChannelReceiver.this);
            VersionOneProtocolChannelReceiver.this.groupMembershipNotifierRegistry.removeListener(VersionOneProtocolChannelReceiver.this);
        }

    }

    /**
     * A {@link GroupMembershipListener} which writes out messages to the client, over a {@link Channel remoting channel}
     * upon cluster topology updates
     */
    private class ClusterTopologyUpdateListener implements GroupMembershipListener {
        private final String clusterName;
        private final VersionOneProtocolChannelReceiver channelReceiver;

        ClusterTopologyUpdateListener(final String clusterName, final VersionOneProtocolChannelReceiver channelReceiver) {
            this.channelReceiver = channelReceiver;
            this.clusterName = clusterName;
        }

        @Override
        public void membershipChanged(List<ClusterNode> deadMembers, List<ClusterNode> newMembers, List<ClusterNode> allMembers) {
            // check removed nodes
            if (deadMembers != null && !deadMembers.isEmpty()) {
                try {
                    this.sendClusterNodesRemoved(deadMembers);
                } catch (IOException ioe) {
                    logger.warn("Could not write a cluster node removal message to channel " + this.channelReceiver.channel, ioe);
                }
            }
            // check added nodes
            if (newMembers != null && !newMembers.isEmpty()) {
                try {
                    this.sendClusterNodesAdded(newMembers);
                } catch (IOException ioe) {
                    logger.warn("Could not write a new cluster node addition message to channel " + this.channelReceiver.channel, ioe);
                }
            }
        }

        @Override
        public void membershipChangedDuringMerge(List<ClusterNode> deadMembers, List<ClusterNode> newMembers, List<ClusterNode> allMembers, List<List<ClusterNode>> originatingGroups) {
            // TODO: This merge logic needs to be better understood. I am not sure if we need to send a removal notification
            // for the new nodes, for the originating group from where these nodes were merged

            // check removed nodes
            if (deadMembers != null && !deadMembers.isEmpty()) {
                try {
                    this.sendClusterNodesRemoved(deadMembers);
                } catch (IOException ioe) {
                    logger.warn("Could not write a cluster node removal message to channel " + this.channelReceiver.channel, ioe);
                }
            }
            // check added nodes
            if (newMembers != null && !newMembers.isEmpty()) {
                try {
                    this.sendClusterNodesAdded(newMembers);
                } catch (IOException ioe) {
                    logger.warn("Could not write a new cluster node addition message to channel " + this.channelReceiver.channel, ioe);
                }
            }
        }

        private void sendClusterNodesRemoved(final List<ClusterNode> removedNodes) throws IOException {
            final DataOutputStream outputStream = new DataOutputStream(this.channelReceiver.channel.writeMessage());
            final ClusterTopologyWriter clusterTopologyWriter = new ClusterTopologyWriter();
            try {
                logger.debug(removedNodes.size() + " nodes removed from cluster " + clusterName + ", writing a protocol message to channel " + this.channelReceiver.channel);
                clusterTopologyWriter.writeNodesRemoved(outputStream, clusterName, removedNodes);
            } finally {
                outputStream.close();
            }
        }

        private void sendClusterNodesAdded(final List<ClusterNode> addedNodes) throws IOException {
            final DataOutputStream outputStream = new DataOutputStream(this.channelReceiver.channel.writeMessage());
            final ClusterTopologyWriter clusterTopologyWriter = new ClusterTopologyWriter();
            try {
                logger.debug(addedNodes.size() + " nodes added to cluster " + clusterName + ", writing a protocol message to channel " + this.channelReceiver.channel);
                clusterTopologyWriter.writeNewNodesAdded(outputStream, clusterName, addedNodes);
            } finally {
                outputStream.close();
            }

        }

    }
}
