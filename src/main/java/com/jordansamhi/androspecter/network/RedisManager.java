package com.jordansamhi.androspecter.network;

import redis.clients.jedis.Jedis;

import java.util.List;

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
 * RedisManager is a utility class for sending values to a Redis server. It connects to a specified Redis server using the
 * given connection parameters, and can send a value to a specified list in the server.
 *
 * @author Jordan Samhi
 */
public class RedisManager {

    private final Jedis jedis;

    /**
     * Constructs a new RedisManager object and connects to the specified Redis server using the given connection parameters.
     *
     * @param server the server name or IP address of the Redis server
     * @param port   the port number on which the Redis server is listening
     * @param auth   the authentication password for the Redis server (may be null if authentication is not required)
     */
    public RedisManager(String server, String port, String auth) {
        this.jedis = new Jedis(String.format("redis://%s:%s", server, port));
        this.jedis.auth(auth);
        this.jedis.connect();
    }

    /**
     * Sends a value to a Redis list using the connection established by this RedisManager.
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
     * @return the randomly removed member from the Redis set
     */
    public String spop(String set) {
        return this.jedis.spop(set);
    }

    /**
     * Adds a member to a Redis set using the connection established by this RedisManager.
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
     * @return the length of the list
     */
    public long llen(String list) {
        return this.jedis.llen(list);
    }

    /**
     * Gets the number of members in a Redis set.
     *
     * @param set the name of the Redis set
     * @return the number of members in the set
     */
    public long scard(String set) {
        return this.jedis.scard(set);
    }

    /**
     * Gets a range of elements from a Redis list.
     *
     * @param list  the name of the Redis list
     * @param start the start index of the range
     * @param end   the end index of the range
     * @return a List of elements in the specified range
     */
    public List<String> lrange(String list, long start, long end) {
        return this.jedis.lrange(list, start, end);
    }

    /**
     * Removes the first count occurrences of elements equal to value from the list stored at key.
     *
     * @param list  the name of the Redis list
     * @param count the count of elements to be removed
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
     * @return the value of the specified key
     */
    public String get(String key) {
        return this.jedis.get(key);
    }

    /**
     * Sets the specified key to the specified value.
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
}
