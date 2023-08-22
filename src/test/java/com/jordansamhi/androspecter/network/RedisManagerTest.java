package com.jordansamhi.androspecter.network;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;


class RedisManagerTest {
    private static GenericContainer<?> redis;
    private RedisManager redisManager;

    @BeforeAll
    static void startRedisContainer() {
        redis = new GenericContainer<>("redis:latest")
                .withExposedPorts(6379);
        redis.start();
    }

    @BeforeEach
    void setUp() {
        String server = redis.getHost();
        String port = Integer.toString(redis.getFirstMappedPort());
        String auth = null;
        redisManager = new RedisManager(server, port, auth);
    }

    @Test
    void testLpushAndLlen() {
        String listName = "testList";
        String value = "value";
        redisManager.lpush(listName, value);
        assertEquals(1, redisManager.llen(listName));
    }

    @Test
    void testSaddAndSismemberAndScard() {
        String setName = "testSet";
        String value = "value";
        redisManager.sadd(setName, value);
        assertTrue(redisManager.sismember(setName, value));
        assertEquals(1, redisManager.scard(setName));
    }

    @Test
    void testSetAndGet() {
        String key = "testKey";
        String value = "testValue";
        redisManager.set(key, value);
        assertEquals(value, redisManager.get(key));
    }

    @Test
    void testLrem() {
        String listName = "testListRemoval";
        String value = "valueToRemove";
        redisManager.lpush(listName, value);
        redisManager.lpush(listName, value);
        assertEquals(2, redisManager.lrem(listName, 2, value));
    }

    @Test
    void testSpop() {
        String setName = "testSpop";
        String value = "spopValue";
        redisManager.sadd(setName, value);
        assertEquals(value, redisManager.spop(setName));
    }

    @Test
    void testLrange() {
        String listName = "testLrange";
        String value = "lrangeValue";
        redisManager.lpush(listName, value);
        List<String> result = redisManager.lrange(listName, 0, 0);
        assertEquals(1, result.size());
        assertEquals(value, result.get(0));
    }

    @Test
    void testDel() {
        String key1 = "testDel1";
        String key2 = "testDel2";
        redisManager.set(key1, "value1");
        redisManager.set(key2, "value2");
        assertEquals(2, redisManager.del(key1, key2));
    }

    @Test
    void testByteArrayMethods() {
        String listName = "testListBytes";
        String setName = "testSetBytes";
        String keyName = "testKeyBytes";
        byte[] value = "byteArrayValue".getBytes();

        redisManager.lpush(listName, value);
        assertEquals(1, redisManager.llen(listName));

        redisManager.sadd(setName, value);
        assertTrue(redisManager.sismember(setName, new String(value)));

        redisManager.set(keyName, value);
        assertArrayEquals(value, redisManager.get(keyName).getBytes());
    }
}