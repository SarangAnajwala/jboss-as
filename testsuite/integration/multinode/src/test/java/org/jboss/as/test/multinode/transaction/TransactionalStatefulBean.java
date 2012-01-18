package org.jboss.as.test.multinode.transaction;

import javax.annotation.Resource;
import javax.ejb.*;
import javax.transaction.SystemException;
import javax.transaction.TransactionSynchronizationRegistry;
import javax.transaction.UserTransaction;
import java.rmi.RemoteException;

/**
 * @author Stuart Douglas
 * @author Ivo Studensky
 */
@Remote(TransactionalStatefulRemote.class)
@Stateful
public class TransactionalStatefulBean implements SessionSynchronization, TransactionalStatefulRemote {

    private Boolean commitSuceeded;
    private boolean beforeCompletion = false;
    private Object transactionKey = null;
    private boolean rollbackOnlyBeforeCompletion = false;

    @Resource
    private UserTransaction userTransaction;

    @Resource
    private TransactionSynchronizationRegistry transactionSynchronizationRegistry;


    @TransactionAttribute(TransactionAttributeType.SUPPORTS)
    public int transactionStatus() {
        try {
            return userTransaction.getStatus();
        } catch (SystemException e) {
            throw new RuntimeException(e);
        }
    }

    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public void resetStatus() {
        commitSuceeded = null;
        beforeCompletion = false;
        transactionKey = null;
    }

    @TransactionAttribute(TransactionAttributeType.SUPPORTS)
    public void setRollbackOnlyBeforeCompletion(boolean rollbackOnlyBeforeCompletion) throws RemoteException {
        this.rollbackOnlyBeforeCompletion = rollbackOnlyBeforeCompletion;
    }

    @TransactionAttribute(TransactionAttributeType.MANDATORY)
    public void sameTransaction(boolean first) throws RemoteException {
        if (first) {
            transactionKey = transactionSynchronizationRegistry.getTransactionKey();
        } else {
            if (!transactionKey.equals(transactionSynchronizationRegistry.getTransactionKey())) {
                throw new RemoteException("Transaction on second call was not the same as on first call");
            }
        }
    }

    @TransactionAttribute(TransactionAttributeType.MANDATORY)
    public void rollbackOnly() throws RemoteException {
        try {
            userTransaction.setRollbackOnly();
        } catch (SystemException e) {
            throw new RemoteException("SystemException during setRollbackOnly", e);
        }
    }

    public void ejbCreate() {

    }

    public void afterBegin() throws EJBException, RemoteException {

    }

    public void beforeCompletion() throws EJBException, RemoteException {
        beforeCompletion = true;

        if (rollbackOnlyBeforeCompletion) {
            try {
                userTransaction.setRollbackOnly();
            } catch (SystemException e) {
                throw new RemoteException("SystemException during setRollbackOnly", e);
            }
        }
    }

    public void afterCompletion(final boolean committed) throws EJBException, RemoteException {
        commitSuceeded = committed;
    }

    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public Boolean getCommitSuceeded() {
        return commitSuceeded;
    }

    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public boolean isBeforeCompletion() {
        return beforeCompletion;
    }
}
