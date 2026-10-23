package com.jordansamhi.androspecter.network;

import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisManagerTest {
    @Test
    void tryWithResourcesClosesOwnedConnection() {
        class StubJedis extends Jedis {
            boolean closed;

            @Override
            public void close() {
                closed = true;
            }
        }
        StubJedis jedis = new StubJedis();
        try (RedisManager ignored = new RedisManager(jedis)) {
            // No connection is opened for this injected client.
        }
        assertTrue(jedis.closed);
    }
}
