package com.orchestrator.utils

import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.util.UUID

/**
 * Structured logging wrapper with correlation ID support.
 */
class Logger(private val name: String) {
    
    private val logger = LoggerFactory.getLogger(name)
    
    /**
     * Log at TRACE level.
     */
    fun trace(message: String, vararg args: Any?) {
        if (logger.isTraceEnabled) {
            logger.trace(message, *args)
        }
    }
    
    /**
     * Log at DEBUG level.
     */
    fun debug(message: String, vararg args: Any?) {
        if (logger.isDebugEnabled) {
            logger.debug(message, *args)
        }
    }
    
    /**
     * Log at INFO level.
     */
    fun info(message: String, vararg args: Any?) {
        if (logger.isInfoEnabled) {
            logger.info(message, *args)
        }
    }
    
    /**
     * Log at WARN level.
     */
    fun warn(message: String, vararg args: Any?) {
        if (logger.isWarnEnabled) {
            logger.warn(message, *args)
        }
    }
    
    /**
     * Log at ERROR level.
     */
    fun error(message: String, vararg args: Any?) {
        logger.error(message, *args)
    }
    
    /**
     * Log at ERROR level with exception.
     */
    fun error(message: String, throwable: Throwable) {
        logger.error(message, throwable)
    }
    
    /**
     * Log structured data at INFO level.
     */
    fun info(message: String, data: Map<String, Any?>) {
        withStructuredData(data) {
            logger.info(message)
        }
    }
    
    /**
     * Log structured data at ERROR level.
     */
    fun error(message: String, data: Map<String, Any?>, throwable: Throwable? = null) {
        withStructuredData(data) {
            if (throwable != null) {
                logger.error(message, throwable)
            } else {
                logger.error(message)
            }
        }
    }
    
    /**
     * Execute block with correlation ID in MDC.
     */
    fun <T> withCorrelationId(correlationId: String, block: () -> T): T {
        val previousId = MDC.get(CORRELATION_ID_KEY)
        try {
            MDC.put(CORRELATION_ID_KEY, correlationId)
            return block()
        } finally {
            if (previousId != null) {
                MDC.put(CORRELATION_ID_KEY, previousId)
            } else {
                MDC.remove(CORRELATION_ID_KEY)
            }
        }
    }
    
    /**
     * Execute block with new correlation ID.
     */
    fun <T> withNewCorrelationId(block: () -> T): T {
        return withCorrelationId(Logger.generateCorrelationId(), block)
    }
    
    /**
     * Get current correlation ID from MDC.
     */
    fun getCorrelationId(): String? = MDC.get(CORRELATION_ID_KEY)
    
    @PublishedApi
    internal fun withStructuredData(data: Map<String, Any?>, block: () -> Unit) {
        val previousValues = mutableMapOf<String, String?>()
        try {
            data.forEach { (key, value) ->
                previousValues[key] = MDC.get(key)
                MDC.put(key, value?.toString() ?: "null")
            }
            block()
        } finally {
            data.keys.forEach { key ->
                val previousValue = previousValues[key]
                if (previousValue != null) {
                    MDC.put(key, previousValue)
                } else {
                    MDC.remove(key)
                }
            }
        }
    }
    
    companion object {
        private const val CORRELATION_ID_KEY = "correlationId"
        
        /**
         * Get logger for class.
         */
        inline fun <reified T> logger(): Logger = Logger(T::class.java.name)
        
        /**
         * Get logger by name.
         */
        fun logger(name: String): Logger = Logger(name)
        
        /**
         * Generate new correlation ID.
         */
        fun generateCorrelationId(): String = UUID.randomUUID().toString()
        
        /**
         * Set correlation ID in MDC.
         */
        fun setCorrelationId(correlationId: String) {
            MDC.put(CORRELATION_ID_KEY, correlationId)
        }
        
        /**
         * Clear correlation ID from MDC.
         */
        fun clearCorrelationId() {
            MDC.remove(CORRELATION_ID_KEY)
        }
        
        /**
         * Get current correlation ID.
         */
        fun getCorrelationId(): String? = MDC.get(CORRELATION_ID_KEY)
    }
}

/**
 * Extension function to get logger for any class.
 */
inline fun <reified T> T.logger(): Logger = Logger.logger<T>()
