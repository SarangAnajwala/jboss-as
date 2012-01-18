package org.jboss.as.test.multinode.transaction;

import javax.annotation.Resource;
import javax.ejb.*;
import javax.transaction.SystemException;
import javax.transaction.UserTransaction;

/**
 * @author Stuart Douglas
 * @author Ivo Studensky
 */
@Remote(TransactionalRemote.class)
@Stateless
public class TransactionalStatelessBean implements TransactionalRemote {

    @Resource
    private UserTransaction userTransaction;

    @TransactionAttribute(TransactionAttributeType.SUPPORTS)
    public int transactionStatus() {
        try {
            return userTransaction.getStatus();
        } catch (SystemException e) {
            throw new RuntimeException(e);
        }
    }

}
