/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2013, Red Hat, Inc., and individual contributors
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

package org.jboss.as.ejb3.remote;

import com.arjuna.ats.arjuna.common.CoreEnvironmentBean;
import com.arjuna.ats.jbossatx.jta.RecoveryManagerService;
import org.jboss.ejb.client.EJBClientContext;
import org.jboss.ejb.client.EJBClientContextListener;
import org.jboss.ejb.client.EJBClientManagedTransactionContext;
import org.jboss.ejb.client.EJBReceiverContext;
import org.jboss.logging.Logger;
import org.jboss.msc.inject.Injector;
import org.jboss.msc.service.Service;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.jboss.msc.value.InjectedValue;
import org.jboss.tm.XAResourceRecovery;

import javax.transaction.xa.XAResource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @author Jaikiran Pai
 */
public class EJBTransactionRecoveryService implements Service<EJBTransactionRecoveryService>, XAResourceRecovery, EJBClientContextListener {

    private static final Logger logger = Logger.getLogger(EJBTransactionRecoveryService.class);

    public static final ServiceName SERVICE_NAME = ServiceName.JBOSS.append("ejb").append("tx-recovery-service");
    public static final EJBTransactionRecoveryService INSTANCE = new EJBTransactionRecoveryService();


    private final List<EJBReceiverContext> receiverContexts = Collections.synchronizedList(new ArrayList<EJBReceiverContext>());
    private final InjectedValue<RecoveryManagerService> recoveryManagerService = new InjectedValue<RecoveryManagerService>();
    //    private final InjectedValue<ServerEnvironment> serverEnvironment = new InjectedValue<ServerEnvironment>();
    private final InjectedValue<CoreEnvironmentBean> arjunaTxCoreEnvironmentBean = new InjectedValue<CoreEnvironmentBean>();

    private EJBTransactionRecoveryService() {
    }

    @Override
    public void start(StartContext startContext) throws StartException {
        // register ourselves to the recovery manager service
        recoveryManagerService.getValue().addXAResourceRecovery(this);
        logger.debug("Registered " + this + " with the transaction recovery manager");
    }

    @Override
    public void stop(StopContext stopContext) {
        // we no longer bother about the XAResource(s)
        this.receiverContexts.clear();
        // un-register ourselves from the recovery manager service
        recoveryManagerService.getValue().removeXAResourceRecovery(this);
        logger.debug("Un-registered " + this + " from the transaction recovery manager");
    }

    @Override
    public EJBTransactionRecoveryService getValue() throws IllegalStateException, IllegalArgumentException {
        return this;
    }

    @Override
    public XAResource[] getXAResources() {
        final XAResource[] xaResources = new XAResource[receiverContexts.size()];
        synchronized (receiverContexts) {
            for (int i = 0; i < receiverContexts.size(); i++) {
                xaResources[i] = new EJBClientManagedTransactionContext.RecoverableXAResource(arjunaTxCoreEnvironmentBean.getValue().getNodeIdentifier(), receiverContexts.get(i));
            }
        }
        return xaResources;
    }

    @Override
    public void contextClosed(EJBClientContext ejbClientContext) {
    }

    @Override
    public void receiverRegistered(final EJBReceiverContext receiverContext) {
        this.receiverContexts.add(receiverContext);
    }

    @Override
    public void receiverUnRegistered(final EJBReceiverContext receiverContext) {
        this.receiverContexts.remove(receiverContext);
    }

    public Injector<RecoveryManagerService> getRecoveryManagerServiceInjector() {
        return this.recoveryManagerService;
    }

    public Injector<CoreEnvironmentBean> getCoreEnvironmentBeanInjector() {
        return this.arjunaTxCoreEnvironmentBean;
    }
}
