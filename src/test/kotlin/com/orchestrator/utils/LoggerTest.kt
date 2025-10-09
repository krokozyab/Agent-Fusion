package com.orchestrator.utils

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.slf4j.MDC

class LoggerTest {
    
    @AfterEach
    fun cleanup() {
        Logger.clearCorrelationId()
        MDC.clear()
    }
    
    @Test
    fun `should create logger for class`() {
        val logger = Logger.logger<LoggerTest>()
        assertNotNull(logger)
    }
    
    @Test
    fun `should create logger by name`() {
        val logger = Logger.logger("test.logger")
        assertNotNull(logger)
    }
    
    @Test
    fun `should create logger via extension function`() {
        val logger = this.logger()
        assertNotNull(logger)
    }
    
    @Test
    fun `should log at different levels`() {
        val logger = Logger.logger<LoggerTest>()
        
        // Should not throw exceptions
        logger.trace("Trace message")
        logger.debug("Debug message")
        logger.info("Info message")
        logger.warn("Warning message")
        logger.error("Error message")
    }
    
    @Test
    fun `should log with arguments`() {
        val logger = Logger.logger<LoggerTest>()
        
        logger.info("Message with args: {} {}", "arg1", "arg2")
        logger.error("Error with args: {}", "error")
    }
    
    @Test
    fun `should log with exception`() {
        val logger = Logger.logger<LoggerTest>()
        val exception = RuntimeException("Test exception")
        
        logger.error("Error occurred", exception)
    }
    
    @Test
    fun `should log structured data`() {
        val logger = Logger.logger<LoggerTest>()
        
        val data = mapOf(
            "userId" to "user-123",
            "action" to "login",
            "status" to "success"
        )
        
        logger.info("User action", data)
        
        // MDC should be cleared after logging
        assertNull(MDC.get("userId"))
        assertNull(MDC.get("action"))
    }
    
    @Test
    fun `should log structured error with exception`() {
        val logger = Logger.logger<LoggerTest>()
        val exception = RuntimeException("Test error")
        
        val data = mapOf(
            "errorCode" to "ERR_001",
            "component" to "test"
        )
        
        logger.error("Error occurred", data, exception)
    }
    
    @Test
    fun `should set and get correlation ID`() {
        val correlationId = "test-correlation-id"
        
        Logger.setCorrelationId(correlationId)
        
        assertEquals(correlationId, Logger.getCorrelationId())
    }
    
    @Test
    fun `should clear correlation ID`() {
        Logger.setCorrelationId("test-id")
        Logger.clearCorrelationId()
        
        assertNull(Logger.getCorrelationId())
    }
    
    @Test
    fun `should generate correlation ID`() {
        val id1 = Logger.generateCorrelationId()
        val id2 = Logger.generateCorrelationId()
        
        assertNotNull(id1)
        assertNotNull(id2)
        assertNotEquals(id1, id2)
    }
    
    @Test
    fun `should execute block with correlation ID`() {
        val logger = Logger.logger<LoggerTest>()
        val correlationId = "test-correlation-id"
        
        val result = logger.withCorrelationId(correlationId) {
            assertEquals(correlationId, logger.getCorrelationId())
            "result"
        }
        
        assertEquals("result", result)
        assertNull(logger.getCorrelationId())
    }
    
    @Test
    fun `should execute block with new correlation ID`() {
        val logger = Logger.logger<LoggerTest>()
        
        val result = logger.withNewCorrelationId {
            val id = logger.getCorrelationId()
            assertNotNull(id)
            id
        }
        
        assertNotNull(result)
        assertNull(logger.getCorrelationId())
    }
    
    @Test
    fun `should restore previous correlation ID after block`() {
        val logger = Logger.logger<LoggerTest>()
        val originalId = "original-id"
        
        Logger.setCorrelationId(originalId)
        
        logger.withCorrelationId("temporary-id") {
            assertEquals("temporary-id", logger.getCorrelationId())
        }
        
        assertEquals(originalId, logger.getCorrelationId())
    }
    
    @Test
    fun `should handle nested correlation IDs`() {
        val logger = Logger.logger<LoggerTest>()
        
        logger.withCorrelationId("outer") {
            assertEquals("outer", logger.getCorrelationId())
            
            logger.withCorrelationId("inner") {
                assertEquals("inner", logger.getCorrelationId())
            }
            
            assertEquals("outer", logger.getCorrelationId())
        }
        
        assertNull(logger.getCorrelationId())
    }
    
    @Test
    fun `should handle exception in block with correlation ID`() {
        val logger = Logger.logger<LoggerTest>()
        val correlationId = "test-id"
        
        assertThrows(RuntimeException::class.java) {
            logger.withCorrelationId(correlationId) {
                throw RuntimeException("Test exception")
            }
        }
        
        // Correlation ID should be cleared even after exception
        assertNull(logger.getCorrelationId())
    }
    
    @Test
    fun `should restore MDC after structured logging`() {
        val logger = Logger.logger<LoggerTest>()
        
        MDC.put("existingKey", "existingValue")
        
        logger.info("Test message", mapOf("newKey" to "newValue"))
        
        assertEquals("existingValue", MDC.get("existingKey"))
        assertNull(MDC.get("newKey"))
    }
    
    @Test
    fun `should handle null values in structured data`() {
        val logger = Logger.logger<LoggerTest>()
        
        val data = mapOf(
            "key1" to "value1",
            "key2" to null
        )
        
        logger.info("Test message", data)
        
        // Should not throw exception
        assertNull(MDC.get("key1"))
        assertNull(MDC.get("key2"))
    }
    
    @Test
    fun `should use static correlation ID methods`() {
        val correlationId = "static-test-id"
        
        Logger.setCorrelationId(correlationId)
        assertEquals(correlationId, Logger.getCorrelationId())
        
        Logger.clearCorrelationId()
        assertNull(Logger.getCorrelationId())
    }
    
    @Test
    fun `should get correlation ID from instance method`() {
        val logger = Logger.logger<LoggerTest>()
        val correlationId = "instance-test-id"
        
        Logger.setCorrelationId(correlationId)
        
        assertEquals(correlationId, logger.getCorrelationId())
    }
}
