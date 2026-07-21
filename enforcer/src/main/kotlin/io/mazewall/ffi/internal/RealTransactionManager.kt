package io.mazewall.ffi.internal

import io.mazewall.NativeTransaction
import io.mazewall.TransactionManager

/**
 * Default implementation of TransactionManager using a static singleton transaction context.
 */
public object RealTransactionManager : TransactionManager {
    @JvmSynthetic
    @PublishedApi
    internal val TRANSACTION_INSTANCE: NativeTransaction = object : NativeTransaction {}

    override fun <T> withTransaction(block: NativeTransaction.() -> T): T {
        return TRANSACTION_INSTANCE.block()
    }
}
