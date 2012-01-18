package org.jboss.as.test.multinode.transaction;

import javax.ejb.EJBObject;
import java.rmi.RemoteException;

/**
 * @author Stuart Douglas
 */
public interface TransactionalStatefulRemote {

    int transactionStatus() throws RemoteException;

    public Boolean getCommitSuceeded() throws RemoteException;

    public boolean isBeforeCompletion() throws RemoteException;

    public void resetStatus() throws RemoteException;

    public void sameTransaction(boolean first) throws RemoteException;

    void rollbackOnly() throws RemoteException;

    public void setRollbackOnlyBeforeCompletion(boolean rollbackOnlyInBeforeCompletion) throws RemoteException;

}
