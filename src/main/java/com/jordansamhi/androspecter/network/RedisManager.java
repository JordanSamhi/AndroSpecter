package com.jordansamhi.androspecter.network;

import redis.clients.jedis.Jedis;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/*-
 * #%L
 * AndroSpecter
 *
 * %%
 * Copyright (C) 2023 Jordan Samhi
 * All rights reserved
 *
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 2.1 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-2.1.html>.
 * #L%
 */

/**
 * Synchronous Redis operations over one owned Jedis connection. Instances are not
 * thread-safe; use one manager per worker and close it when finished. Connection,
 * authentication, and Redis command errors propagate as Jedis runtime exceptions.
 * Keys and values must be non-null unless a method explicitly states otherwise.
 * <p>List-push and set-add methods select database 0 before executing; other methods
 * use the connection's currently selected database. The public constructor starts
 * in database 0. String/binary overloads share UTF-8-encoded keys; string getters
 * decode returned values as UTF-8 and should not be used to recover arbitrary bytes.
 *
 * @author Jordan Samhi
 */
public class RedisManager implements AutoCloseable {

    private final Jedis jedis;

    /**
     * Constructs a new RedisManager object and connects to the specified Redis server using the given connection parameters.
     *
     * @param server the server name or IP address of the Redis server; must not be null
     * @param port   the port number on which the Redis server is listening; must not be null
     * @param auth   the authentication password for the Redis server; may be null or empty if authentication is not required
     * @throws IllegalArgumentException if server or port is null, or the constructed Redis URI is invalid
     * @throws redis.clients.jedis.exceptions.JedisException if connection or authentication fails
     */
    public RedisManager(String server, String port, String auth) {
        if (server == null || port == null) {
            throw new IllegalArgumentException("Server and port must not be null");
        }
        this.jedis = new Jedis(String.format("redis://%s:%s", server, port));
        try {
            if (auth != null && !auth.isEmpty()) {
                this.jedis.auth(auth);
            }
            this.jedis.connect();
        } catch (RuntimeException e) {
            this.jedis.close();
            throw e;
        }
    }

    /**
     * Wraps an existing client without connecting or authenticating it. This manager
     * takes ownership and closes it through {@link #close()}; do not concurrently use
     * or independently close the supplied client.
     *
     * @param jedis non-null client whose lifecycle is transferred to this manager
     * @throws NullPointerException if {@code jedis} is null
     */
    protected RedisManager(Jedis jedis) {
        this.jedis = Objects.requireNonNull(jedis, "jedis");
    }

    /**
     * Releases the owned Redis connection. The caller owns this manager's lifecycle
     * and should stop using it after closing; this wrapper does not maintain a closed-state guard.
     */
    @Override
    public void close() {
        this.jedis.close();
    }

    /**
     * Prepends a value to a Redis list in database 0, creating the list if absent.
     *
     * @param list the name of the Redis list to which to send the value
     * @param val  the value to send to the Redis list
     */
    public void lpush(String list, String val) {
        this.jedis.select(0);
        this.jedis.lpush(list, val);
    }

    /**
     * Removes and returns a random member from a Redis set using the connection established by this RedisManager.
     *
     * @param set the name of the Redis set from which to remove and return a random member
     * @return the randomly removed member, or null when the set is empty or absent
     */
    public String spop(String set) {
        return this.jedis.spop(set);
    }

    /**
     * Adds a member to a Redis set in database 0.
     * <p>
     * If the specified set does not exist, a new set is created and the member is added to it.
     * If the set already exists and contains the specified member, the command has no effect.
     * Note that the set is not ordered and each member of a set is unique.
     *
     * @param set the name of the Redis set to which to add the member
     * @param val the member to add to the Redis set
     */
    public void sadd(String set, String val) {
        this.jedis.select(0);
        this.jedis.sadd(set, val);
    }

    /**
     * Checks if a member is part of a Redis set.
     *
     * @param set the name of the Redis set
     * @param val the member to check
     * @return true if the member is part of the set, false otherwise
     */
    public boolean sismember(String set, String val) {
        return this.jedis.sismember(set, val);
    }

    /**
     * Gets the length of a Redis list.
     *
     * @param list the name of the Redis list
     * @return the length of the list, or zero if the key is absent
     */
    public long llen(String list) {
        return this.jedis.llen(list);
    }

    /**
     * Gets the number of members in a Redis set.
     *
     * @param set the name of the Redis set
     * @return the number of members in the set, or zero if the key is absent
     */
    public long scard(String set) {
        return this.jedis.scard(set);
    }

    /**
     * Gets a range of elements from a Redis list.
     *
     * @param list  the name of the Redis list
     * @param start inclusive zero-based index; negative indices count from the tail
     * @param end inclusive zero-based index; {@code -1} means the last element
     * @return a detached list in Redis list order, empty for an absent key or empty range;
     *         later changes to the returned list do not update Redis
     */
    public List<String> lrange(String list, long start, long end) {
        return this.jedis.lrange(list, start, end);
    }

    /**
     * Removes occurrences of a value from a list. Positive counts scan from head to tail,
     * negative counts scan from tail to head, and zero removes every matching element.
     *
     * @param list  the name of the Redis list
     * @param count signed maximum removal count, or zero for all occurrences
     * @param value the value to be removed
     * @return the number of removed elements
     */
    public long lrem(String list, long count, String value) {
        return this.jedis.lrem(list, count, value);
    }

    /**
     * Gets the value of the specified key.
     *
     * @param key the key
     * @return the string value, or null if the key is absent
     */
    public String get(String key) {
        return this.jedis.get(key);
    }

    /**
     * Sets or replaces a string value, clearing any existing expiration on the key.
     *
     * @param key   the key
     * @param value the value
     */
    public void set(String key, String value) {
        this.jedis.set(key, value);
    }

    /**
     * Deletes one or more keys.
     *
     * @param keys the keys
     * @return The number of keys that were removed.
     */
    public long del(String... keys) {
        return this.jedis.del(keys);
    }

    /**
     * Prepends a binary value to a Redis list in database 0, encoding its key as UTF-8.
     *
     * @param list the name of the Redis list to which to send the byte array
     * @param val  the byte array to send to the Redis list
     */
    public void lpush(String list, byte[] val) {
        this.jedis.select(0);
        this.jedis.lpush(list.getBytes(StandardCharsets.UTF_8), val);
    }

    /**
     * Adds a binary value to a Redis set in database 0, encoding its key as UTF-8.
     * <p>
     * If the specified set does not exist, a new set is created and the byte array is added to it.
     * If the set already exists and contains the specified byte array, the command has no effect.
     * Note that the set is not ordered and each byte array in a set is unique.
     *
     * @param set the name of the Redis set to which to add the byte array
     * @param val the byte array to add to the Redis set
     */
    public void sadd(String set, byte[] val) {
        this.jedis.select(0);
        this.jedis.sadd(set.getBytes(StandardCharsets.UTF_8), val);
    }

    /**
     * Sets or replaces a binary value using a UTF-8 key, clearing any existing expiration.
     * {@link #get(String)} decodes values as UTF-8; it is not a lossless binary getter.
     *
     * @param key   the key
     * @param value the byte array
     */
    public void set(String key, byte[] value) {
        this.jedis.set(key.getBytes(StandardCharsets.UTF_8), value);
    }

    /**
     * Gets a set of elements from a Redis set.
     *
     * @param set the name of the Redis set
     * @return detached members with no ordering guarantee, empty if the key is absent;
     *         changes to the returned set do not update Redis
     */
    public Set<String> smembers(String set) {
        return this.jedis.smembers(set);
    }
}
