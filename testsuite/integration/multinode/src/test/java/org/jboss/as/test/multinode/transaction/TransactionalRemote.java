package org.jboss.as.test.multinode.transaction;

import javax.ejb.EJBObject;
import java.rmi.RemoteException;

/**
 * @author Stuart Douglas
 */
public interface TransactionalRemote {

    int transactionStatus() throws RemoteException;
}
