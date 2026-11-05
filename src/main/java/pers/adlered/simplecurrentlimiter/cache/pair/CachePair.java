/*
 * Bolo - A stable and beautiful blogging system based in Solo.
 * Copyright (c) 2020-present, https://github.com/bolo-blog
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package pers.adlered.simplecurrentlimiter.cache.pair;

/**
 * <h3>SimpleCurrentLimiter</h3>
 * <p>字符串和次数 Bean</p>
 *
 * @author : https://github.com/adlered
 * @date : 2019-10-10 22:52
 **/
public class CachePair {
    private volatile long frequency;
    private volatile long timeStamp;

    public CachePair() {
    }

    public CachePair(final long frequency, final long timeStamp) {
        this.frequency = frequency;
        this.timeStamp = timeStamp;
    }

    public synchronized long incrementAndGet(final long currentTimeStamp, final long expireTime) {
        if (expireTime != -1 && (currentTimeStamp - this.timeStamp) > expireTime) {
            this.frequency = 1L;
            this.timeStamp = currentTimeStamp;
        } else {
            this.frequency++;
        }
        return this.frequency;
    }

    public long getFrequency() {
        return frequency;
    }

    public synchronized void setFrequency(long frequency) {
        this.frequency = frequency;
    }

    public long getTimeStamp() {
        return timeStamp;
    }

    public synchronized void setTimeStamp(long timeStamp) {
        this.timeStamp = timeStamp;
    }
}
