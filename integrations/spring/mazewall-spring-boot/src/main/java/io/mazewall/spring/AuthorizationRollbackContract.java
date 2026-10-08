package io.mazewall.spring;

import java.lang.reflect.Method;
import org.springframework.transaction.interceptor.TransactionAttribute;

/** Optional advice validates its actual denial types without linking Security into the default boundary. */
interface AuthorizationRollbackContract {
    void validateRollback(TransactionAttribute attributes, Method method, Class<?> target);
}
